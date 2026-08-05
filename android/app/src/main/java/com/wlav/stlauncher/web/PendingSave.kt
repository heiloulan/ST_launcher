package com.wlav.stlauncher.web

sealed interface PendingSave {
    val fileName: String
    val mimeType: String

    data class RemoteUrl(
        override val fileName: String,
        override val mimeType: String,
        val url: String,
        val userAgent: String?,
        val cancelUrl: String? = null,
    ) : PendingSave

    data class TemporaryFile(
        override val fileName: String,
        override val mimeType: String,
        val path: String,
        val deleteAfterSave: Boolean = true,
    ) : PendingSave
}
