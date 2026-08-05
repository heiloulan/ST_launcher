package com.wlav.stlauncher

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.wlav.stlauncher.web.PendingSave

class LauncherViewModel(
    private val savedState: SavedStateHandle,
) : ViewModel() {
    var pendingSave: PendingSave?
        get() {
            val kind = savedState.get<String>(KEY_KIND) ?: return null
            val fileName = savedState.get<String>(KEY_FILE_NAME) ?: return null
            val mimeType = savedState.get<String>(KEY_MIME_TYPE) ?: return null
            return when (kind) {
                KIND_URL ->
                    PendingSave.RemoteUrl(
                        fileName = fileName,
                        mimeType = mimeType,
                        url = savedState[KEY_VALUE] ?: return null,
                        userAgent = savedState[KEY_USER_AGENT],
                        cancelUrl = savedState[KEY_CANCEL_URL],
                    )
                KIND_FILE ->
                    PendingSave.TemporaryFile(
                        fileName = fileName,
                        mimeType = mimeType,
                        path = savedState[KEY_VALUE] ?: return null,
                        deleteAfterSave = savedState[KEY_DELETE_AFTER] ?: true,
                    )
                else -> null
            }
        }
        set(value) {
            when (value) {
                null -> clearPendingSave()
                is PendingSave.RemoteUrl -> {
                    savedState[KEY_KIND] = KIND_URL
                    savedState[KEY_FILE_NAME] = value.fileName
                    savedState[KEY_MIME_TYPE] = value.mimeType
                    savedState[KEY_VALUE] = value.url
                    savedState[KEY_USER_AGENT] = value.userAgent
                    savedState[KEY_CANCEL_URL] = value.cancelUrl
                    savedState[KEY_DELETE_AFTER] = false
                }
                is PendingSave.TemporaryFile -> {
                    savedState[KEY_KIND] = KIND_FILE
                    savedState[KEY_FILE_NAME] = value.fileName
                    savedState[KEY_MIME_TYPE] = value.mimeType
                    savedState[KEY_VALUE] = value.path
                    savedState[KEY_USER_AGENT] = null
                    savedState[KEY_CANCEL_URL] = null
                    savedState[KEY_DELETE_AFTER] = value.deleteAfterSave
                }
            }
        }

    fun consumePendingSave(): PendingSave? = pendingSave.also { clearPendingSave() }

    private fun clearPendingSave() {
        listOf(
            KEY_KIND,
            KEY_FILE_NAME,
            KEY_MIME_TYPE,
            KEY_VALUE,
            KEY_USER_AGENT,
            KEY_CANCEL_URL,
            KEY_DELETE_AFTER,
        ).forEach { key -> savedState.remove<Any?>(key) }
    }

    companion object {
        private const val KEY_KIND = "pending_save_kind"
        private const val KEY_FILE_NAME = "pending_save_name"
        private const val KEY_MIME_TYPE = "pending_save_mime"
        private const val KEY_VALUE = "pending_save_value"
        private const val KEY_USER_AGENT = "pending_save_user_agent"
        private const val KEY_CANCEL_URL = "pending_save_cancel_url"
        private const val KEY_DELETE_AFTER = "pending_save_delete_after"
        private const val KIND_URL = "url"
        private const val KIND_FILE = "file"
    }
}
