package com.wlav.stlauncher.web

import android.net.Uri
import android.os.Build
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.net.toUri
import com.wlav.stlauncher.R

/**
 * Navigation policy for the launcher WebView.
 *
 * Main-frame navigation is confined to the loopback endpoint the bridge was
 * installed for. Anything else is either handed to the system browser (http/https)
 * or refused outright.
 */
class LauncherWebViewClient(
    private val callbacks: Callbacks,
) : WebViewClient() {
    interface Callbacks {
        /** The origin currently trusted for main-frame navigation, if any. */
        fun activeOrigin(): String?

        fun onLocalPageFinished()

        /** First paint of the local page: the loading screen can step aside. */
        fun onLocalPageVisible()

        fun onExternalNavigation(url: Uri)

        fun onBlockedNavigation()

        fun onLoadFailure(
            @StringRes titleRes: Int,
            detail: String?,
        )

        fun onRendererGone(view: WebView)
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        if (!request.isForMainFrame) return false
        if (request.url.originOrNull() == callbacks.activeOrigin()) return false
        if (request.url.scheme == "about") return false
        if (request.url.scheme == "http" || request.url.scheme == "https") {
            callbacks.onExternalNavigation(request.url)
            return true
        }
        callbacks.onBlockedNavigation()
        return true
    }

    override fun onPageFinished(
        view: WebView,
        url: String?,
    ) {
        super.onPageFinished(view, url)
        if (url?.toUri().originOrNull() == callbacks.activeOrigin()) {
            callbacks.onLocalPageFinished()
        }
    }

    override fun onPageCommitVisible(
        view: WebView,
        url: String?,
    ) {
        super.onPageCommitVisible(view, url)
        if (url?.toUri().originOrNull() == callbacks.activeOrigin()) {
            callbacks.onLocalPageVisible()
        }
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (request.isForMainFrame) {
            callbacks.onLoadFailure(
                R.string.error_page_load_failed,
                error.description?.toString(),
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    override fun onSafeBrowsingHit(
        view: WebView,
        request: WebResourceRequest,
        threatType: Int,
        callback: SafeBrowsingResponse,
    ) {
        callback.backToSafety(true)
        callbacks.onLoadFailure(R.string.error_unsafe_page, null)
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail,
    ): Boolean {
        callbacks.onRendererGone(view)
        return true
    }
}
