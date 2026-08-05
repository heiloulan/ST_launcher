package com.wlav.stlauncher.web

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.CookieManager
import androidx.core.net.toUri
import com.wlav.stlauncher.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

interface DownloadCoordinator {
    suspend fun save(
        request: PendingSave,
        destination: Uri,
    )

    suspend fun uploadImport(
        source: Uri,
        baseUrl: String,
    ): String

    suspend fun cancel(request: PendingSave)
}

class AndroidDownloadCoordinator(
    context: Context,
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .build(),
) : DownloadCoordinator {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    override suspend fun save(
        request: PendingSave,
        destination: Uri,
    ) = withContext(Dispatchers.IO) {
        try {
            resolver.openOutputStream(destination, "w").use { output ->
                output ?: throw IOException(appContext.getString(R.string.download_open_target_failed))
                when (request) {
                    is PendingSave.RemoteUrl -> streamUrl(request, output)
                    is PendingSave.TemporaryFile -> {
                        File(request.path).inputStream().buffered().use { input ->
                            input.copyTo(output, COPY_BUFFER_SIZE)
                        }
                    }
                }
            }
            if (request is PendingSave.TemporaryFile && request.deleteAfterSave) {
                File(request.path).delete()
            }
        } catch (error: Throwable) {
            deleteIncompleteDocument(destination)
            throw error
        }
    }

    override suspend fun uploadImport(
        source: Uri,
        baseUrl: String,
    ): String =
        withContext(Dispatchers.IO) {
            val cookie = CookieManager.getInstance().getCookie(baseUrl)
            val csrfToken = requestCsrfToken(baseUrl, cookie)

            val contentLength = queryLength(source)
            val body =
                object : RequestBody() {
                    override fun contentType() = "application/gzip".toMediaTypeOrNull()

                    override fun contentLength(): Long = contentLength

                    override fun writeTo(sink: BufferedSink) {
                        resolver.openInputStream(source).use { input ->
                            input ?: throw IOException(appContext.getString(R.string.download_open_backup_failed))
                            val buffer = ByteArray(COPY_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                sink.write(buffer, 0, read)
                            }
                        }
                    }
                }

            val importRequest =
                Request
                    .Builder()
                    .url("$baseUrl/api/plugins/st-launcher/imports")
                    .header("X-CSRF-Token", csrfToken)
                    .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
                    .post(body)
                    .build()
            client.newCall(importRequest).execute().use { response ->
                response.requireSuccess()
                val payload = JSONObject(response.body.string())
                payload.getString("id")
            }
        }

    override suspend fun cancel(request: PendingSave) =
        withContext(Dispatchers.IO) {
            when (request) {
                is PendingSave.TemporaryFile -> {
                    if (request.deleteAfterSave) File(request.path).delete()
                }
                is PendingSave.RemoteUrl -> {
                    val cancelUrl = request.cancelUrl ?: return@withContext
                    val baseUrl = requireLoopbackOrigin(cancelUrl)
                    val cookie = CookieManager.getInstance().getCookie(baseUrl)
                    val csrfToken = requestCsrfToken(baseUrl, cookie)
                    val deleteRequest =
                        Request
                            .Builder()
                            .url(cancelUrl)
                            .header("X-CSRF-Token", csrfToken)
                            .apply {
                                if (!cookie.isNullOrBlank()) header("Cookie", cookie)
                            }.delete()
                            .build()
                    client.newCall(deleteRequest).execute().use { response ->
                        if (!response.isSuccessful && response.code !in setOf(404, 410)) {
                            response.requireSuccess()
                        }
                    }
                }
            }
        }

    private fun streamUrl(
        pending: PendingSave.RemoteUrl,
        output: java.io.OutputStream,
    ) {
        val request =
            Request
                .Builder()
                .url(pending.url)
                .apply {
                    pending.userAgent?.takeIf(String::isNotBlank)?.let { header("User-Agent", it) }
                    CookieManager
                        .getInstance()
                        .getCookie(pending.url)
                        ?.takeIf(String::isNotBlank)
                        ?.let { header("Cookie", it) }
                }.get()
                .build()
        client.newCall(request).execute().use { response ->
            response.requireSuccess()
            val copied =
                response.body.byteStream().use { input ->
                    input.copyTo(output, COPY_BUFFER_SIZE)
                }
            // OkHttp 5 always hands back a body, so a truncated or empty
            // download only shows up in the byte count.
            if (copied == 0L) {
                throw IOException(appContext.getString(R.string.download_empty_response))
            }
        }
    }

    private fun queryLength(uri: Uri): Long {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0)
            }
        }
        return -1
    }

    private fun requestCsrfToken(
        baseUrl: String,
        cookie: String?,
    ): String {
        val csrfRequest =
            Request
                .Builder()
                .url("$baseUrl/csrf-token")
                .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
                .get()
                .build()
        return client.newCall(csrfRequest).execute().use { response ->
            response.requireSuccess()
            JSONObject(response.body.string()).getString("token")
        }
    }

    private fun requireLoopbackOrigin(url: String): String {
        val parsed = url.toUri()
        require(
            parsed.scheme == "http" &&
                parsed.host == "127.0.0.1" &&
                parsed.port in 1..65_535 &&
                parsed.userInfo == null,
        ) {
            "Invalid local cancellation URL"
        }
        return "http://127.0.0.1:${parsed.port}"
    }

    private fun deleteIncompleteDocument(uri: Uri) {
        runCatching {
            if (DocumentsContract.isDocumentUri(appContext, uri)) {
                DocumentsContract.deleteDocument(resolver, uri)
            } else {
                resolver.delete(uri, null, null)
            }
        }
    }

    private fun Response.requireSuccess() {
        if (isSuccessful) return
        val detail = body.string().take(4_096)
        throw IOException(
            if (detail.isBlank()) {
                appContext.getString(R.string.download_http_error, code)
            } else {
                appContext.getString(R.string.download_http_error_detail, code, detail)
            },
        )
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 64 * 1024
    }
}
