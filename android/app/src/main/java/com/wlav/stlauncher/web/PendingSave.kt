package com.wlav.stlauncher.web

import android.os.Bundle

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

fun PendingSave.toBundle(): Bundle =
    Bundle().apply {
        putString("name", fileName)
        putString("mime", mimeType)
        when (val save = this@toBundle) {
            is PendingSave.RemoteUrl -> {
                putString("kind", "url")
                putString("value", save.url)
                putString("agent", save.userAgent)
                putString("cancel", save.cancelUrl)
            }
            is PendingSave.TemporaryFile -> {
                putString("kind", "file")
                putString("value", save.path)
                putBoolean("delete", save.deleteAfterSave)
            }
        }
    }

fun pendingSaveFromBundle(bundle: Bundle): PendingSave {
    val name = requireNotNull(bundle.getString("name"))
    val mime = requireNotNull(bundle.getString("mime"))
    return when (bundle.getString("kind")) {
        "url" ->
            PendingSave.RemoteUrl(
                name,
                mime,
                requireNotNull(bundle.getString("value")),
                bundle.getString("agent"),
                bundle.getString("cancel"),
            )
        "file" -> PendingSave.TemporaryFile(name, mime, requireNotNull(bundle.getString("value")), bundle.getBoolean("delete", true))
        else -> error("Unknown save request")
    }
}
