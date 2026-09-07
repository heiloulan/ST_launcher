package com.wlav.stlauncher.web

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.wlav.stlauncher.R
import com.wlav.stlauncher.runtime.BridgeProtocol
import com.wlav.stlauncher.runtime.BridgeProtocolException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.json.JSONObject
import java.io.IOException

class SecureWebBridge(
    private val webView: WebView,
    private val scope: CoroutineScope,
    private val blobStore: BlobDownloadStore,
    private val scriptSource: String,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onThemeColor(value: String)

        fun onToast(message: String)

        fun onSaveRequested(save: PendingSave)

        fun onImportRequested()

        /** Opens the native log screen. The bridge never ships log text itself. */
        fun onLogsRequested()

        fun onLauncherMenuRequested()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val viewContext = webView.context
    private var origin: String? = null
    private var scriptHandler: ScriptHandler? = null

    @SuppressLint("RequiresFeature")
    fun install(exactOrigin: String) {
        require(BridgeSourcePolicy.isExactLoopbackOrigin(exactOrigin)) {
            "Bridge origin must be an exact numeric loopback origin"
        }
        remove()
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            throw IOException(string(R.string.bridge_webview_outdated))
        }

        origin = exactOrigin
        WebViewCompat.addWebMessageListener(
            webView,
            OBJECT_NAME,
            setOf(exactOrigin),
        ) { _, message, sourceOrigin, isMainFrame, replyProxy ->
            if (!BridgeSourcePolicy.accepts(
                    expectedOrigin = exactOrigin,
                    sourceOrigin = sourceOrigin.asOrigin(),
                    isMainFrame = isMainFrame,
                )
            ) {
                replyError(replyProxy, null, "INVALID_ORIGIN", string(R.string.bridge_origin_rejected))
                return@addWebMessageListener
            }
            dispatch(message, replyProxy)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            scriptHandler =
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    scriptSource,
                    setOf(exactOrigin),
                )
        }
    }

    /**
     * Asks the page to stream a `blob:` or `data:` URL back over the bridge.
     * Those URLs only exist inside the renderer, so native code cannot fetch
     * them directly. The helper name is part of `launcher-bridge.js`, which this
     * class already owns.
     */
    fun requestInPageTransfer(
        url: String,
        fileName: String,
        mimeType: String,
    ) {
        val script =
            "window.__stLauncherDownloadBlob(" +
                "${JSONObject.quote(url)}," +
                "${JSONObject.quote(fileName)}," +
                "${JSONObject.quote(mimeType)}" +
                ").catch(console.error)"
        webView.evaluateJavascript(script, null)
    }

    fun injectFallbackIfNeeded() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            webView.evaluateJavascript(scriptSource, null)
        }
    }

    @SuppressLint("RequiresFeature")
    fun remove() {
        if (origin == null && scriptHandler == null) return
        scriptHandler?.remove()
        scriptHandler = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(webView, OBJECT_NAME)
        }
        origin = null
    }

    private fun dispatch(
        message: WebMessageCompat,
        replyProxy: JavaScriptReplyProxy,
    ) {
        val request =
            try {
                BridgeProtocol.parse(message.data ?: "")
            } catch (error: BridgeProtocolException) {
                replyError(replyProxy, null, error.code, error.message)
                return
            }
        try {
            when (request.type) {
                "theme.set" -> {
                    callbacks.onThemeColor(request.string("color"))
                    replySuccess(replyProxy, request.id)
                }
                "toast.show" -> {
                    callbacks.onToast(request.string("message").take(300))
                    replySuccess(replyProxy, request.id)
                }
                "download.url" -> {
                    val requestedOrigin = origin ?: return
                    val url = resolveHttpUrl(requestedOrigin, request.string("url"))
                    callbacks.onSaveRequested(
                        PendingSave.RemoteUrl(
                            fileName = request.string("fileName", "download"),
                            mimeType = request.string("mimeType", DEFAULT_MIME),
                            url = url,
                            userAgent = webView.settings.userAgentString,
                            cancelUrl =
                                request
                                    .stringOrNull("cancelUrl")
                                    ?.let { resolveLocalUrl(requestedOrigin, it) },
                        ),
                    )
                    replySuccess(replyProxy, request.id)
                }
                "blob.start" ->
                    launchRequest(replyProxy, request.id) {
                        blobStore.begin(
                            id = request.string("transferId"),
                            fileName = request.string("fileName", "download"),
                            mimeType = request.string("mimeType", DEFAULT_MIME),
                            expectedBytes = request.longOrNull("size")?.takeIf { it >= 0 },
                        )
                    }
                "blob.chunk" ->
                    launchRequest(replyProxy, request.id) {
                        blobStore.append(
                            id = request.string("transferId"),
                            sequence = request.int("sequence"),
                            encodedChunk = request.string("data"),
                        )
                    }
                "blob.finish" ->
                    launchRequest(replyProxy, request.id) {
                        val save = blobStore.finish(request.string("transferId"))
                        withContext(Dispatchers.Main) { callbacks.onSaveRequested(save) }
                    }
                "blob.cancel" ->
                    launchRequest(replyProxy, request.id) {
                        blobStore.cancel(request.string("transferId"))
                    }
                "backup.import.pick" -> {
                    callbacks.onImportRequested()
                    replySuccess(replyProxy, request.id)
                }
                "logs.open" -> {
                    callbacks.onLogsRequested()
                    replySuccess(replyProxy, request.id)
                }
                "launcher.menu" -> {
                    callbacks.onLauncherMenuRequested()
                    replySuccess(replyProxy, request.id)
                }
                else ->
                    replyError(
                        replyProxy,
                        request.id,
                        "UNKNOWN_MESSAGE",
                        string(R.string.bridge_unknown_message),
                    )
            }
        } catch (error: BridgeProtocolException) {
            replyError(replyProxy, request.id, error.code, error.message)
        } catch (error: Throwable) {
            replyError(
                replyProxy,
                request.id,
                "INVALID_REQUEST",
                error.message ?: string(R.string.bridge_invalid_request),
            )
        }
    }

    private fun launchRequest(
        replyProxy: JavaScriptReplyProxy,
        id: String,
        block: suspend () -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                block()
                replySuccess(replyProxy, id)
            } catch (error: Throwable) {
                replyError(
                    replyProxy,
                    id,
                    "REQUEST_FAILED",
                    error.message ?: string(R.string.bridge_request_failed),
                )
            }
        }
    }

    private fun replySuccess(
        proxy: JavaScriptReplyProxy,
        id: String,
        result: JsonObject = buildJsonObject {},
    ) {
        postReply(proxy, BridgeProtocol.success(id, result))
    }

    private fun replyError(
        proxy: JavaScriptReplyProxy,
        id: String?,
        code: String,
        message: String,
    ) {
        postReply(proxy, BridgeProtocol.error(id, code, message))
    }

    @SuppressLint("RequiresFeature")
    private fun postReply(
        proxy: JavaScriptReplyProxy,
        payload: String,
    ) {
        mainHandler.post { proxy.postMessage(payload) }
    }

    private fun string(
        @StringRes id: Int,
    ): String = viewContext.getString(id)

    private fun Uri.asOrigin(): String =
        buildString {
            append(scheme)
            append("://")
            append(host)
            if (port != -1) {
                append(':')
                append(port)
            }
        }

    private fun resolveHttpUrl(
        baseOrigin: String,
        value: String,
    ): String {
        val candidate =
            if (value.startsWith('/')) {
                (baseOrigin + value).toUri()
            } else {
                value.toUri()
            }
        require(candidate.scheme == "http" || candidate.scheme == "https") {
            string(R.string.bridge_http_only_download)
        }
        return candidate.toString()
    }

    private fun resolveLocalUrl(
        baseOrigin: String,
        value: String,
    ): String =
        resolveHttpUrl(baseOrigin, value).also { resolved ->
            val parsed = resolved.toUri()
            require(
                parsed.asOrigin() == baseOrigin &&
                    parsed.userInfo == null &&
                    CANCEL_PATH_PATTERN.matches(parsed.path.orEmpty()) &&
                    parsed.query == null &&
                    parsed.fragment == null,
            ) {
                string(R.string.bridge_cancel_url_local_only)
            }
        }

    companion object {
        private const val OBJECT_NAME = "STLauncher"
        private const val DEFAULT_MIME = "application/octet-stream"
        private val CANCEL_PATH_PATTERN =
            Regex("/api/plugins/st-launcher/operations/[0-9a-fA-F-]{36}")
    }
}

internal object BridgeSourcePolicy {
    fun isExactLoopbackOrigin(value: String): Boolean =
        runCatching {
            val uri = java.net.URI(value)
            uri.scheme == "http" &&
                uri.host == "127.0.0.1" &&
                uri.port in 1..65_535 &&
                uri.rawUserInfo == null &&
                uri.rawPath.isNullOrEmpty() &&
                uri.rawQuery == null &&
                uri.rawFragment == null
        }.getOrDefault(false)

    fun accepts(
        expectedOrigin: String,
        sourceOrigin: String,
        isMainFrame: Boolean,
    ): Boolean = isMainFrame && sourceOrigin == expectedOrigin
}
