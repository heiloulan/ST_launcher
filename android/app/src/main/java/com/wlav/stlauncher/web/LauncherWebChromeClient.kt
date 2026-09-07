package com.wlav.stlauncher.web

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * Handles the WebView's requests for host-side UI. In practice that is the file
 * chooser: this client turns SillyTavern's `<input type="file">` into a SAF
 * intent and hands it to the Activity, which owns the result launcher.
 */
class LauncherWebChromeClient(
    private val debug: Boolean,
    private val callbacks: Callbacks,
) : WebChromeClient() {
    interface Callbacks {
        /**
         * Launches [intent] and eventually completes [callback]. Returning
         * `false` tells the WebView the request was not handled, in which case
         * this client cancels the callback itself.
         */
        fun onFileChooserRequested(
            intent: Intent,
            callback: ValueCallback<Array<Uri>>,
            allowMultiple: Boolean,
        ): Boolean
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        if (debug) {
            Log.d(TAG, "WebView: ${consoleMessage?.message()}")
        }
        return true
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        val callback = filePathCallback ?: return false
        val allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
        val handled =
            callbacks.onFileChooserRequested(
                intent = buildIntent(fileChooserParams, allowMultiple),
                callback = callback,
                allowMultiple = allowMultiple,
            )
        if (!handled) callback.onReceiveValue(null)
        return handled
    }

    private fun buildIntent(
        params: FileChooserParams?,
        allowMultiple: Boolean,
    ): Intent {
        val accepts =
            params
                ?.acceptTypes
                .orEmpty()
                .flatMap { it.split(',') }
                .map(String::trim)
        // A filename extension may have no Android MIME mapping (.charx, .jsonl).
        // Keep the picker open to all files when any such suffix is requested.
        val hasUnmappedSuffix = accepts.any { it.startsWith('.') }
        val acceptedMimeTypes =
            accepts
                .map(String::trim)
                .filter { MIME_PATTERN.matches(it) }
                .distinct()
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (hasUnmappedSuffix) "*/*" else acceptedMimeTypes.singleOrNull() ?: "*/*"
            if (!hasUnmappedSuffix && acceptedMimeTypes.size > 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, acceptedMimeTypes.toTypedArray())
            }
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
        }
    }

    private companion object {
        const val TAG = "LauncherWebChrome"
        val MIME_PATTERN = Regex("[\\w!#$&^_.+-]+/[\\w!#$&^_.+*-]+")
    }
}
