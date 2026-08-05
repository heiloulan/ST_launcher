package com.wlav.stlauncher.web

import android.webkit.DownloadListener
import android.webkit.URLUtil

/**
 * Routes downloads started by the page.
 *
 * `blob:` and `data:` URLs cannot be fetched from native code — they only exist
 * inside the renderer — so those are handed back to the page's own transfer
 * helper. Everything else becomes a SAF save.
 */
class LauncherDownloadListener(
    private val defaultMimeType: String,
    private val onInPageTransfer: (url: String, fileName: String, mimeType: String) -> Unit,
    private val onSaveRequested: (PendingSave) -> Unit,
) : DownloadListener {
    override fun onDownloadStart(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ) {
        val resolvedMime = mimeType ?: defaultMimeType
        val fileName = guessFileName(url, contentDisposition, mimeType)
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            onInPageTransfer(url, fileName, resolvedMime)
        } else {
            onSaveRequested(
                PendingSave.RemoteUrl(
                    fileName = fileName,
                    mimeType = resolvedMime,
                    url = url,
                    userAgent = userAgent,
                ),
            )
        }
    }

    private fun guessFileName(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
    ): String =
        URLUtil
            .guessFileName(url, contentDisposition, mimeType)
            // URLUtil's own placeholders carry no information; a timestamp at
            // least distinguishes repeated exports in the file picker.
            .takeUnless { it == "downloadfile" || it == "downloadfile.bin" }
            ?: "export_${System.currentTimeMillis()}.json"
}
