package com.wlav.stlauncher

import androidx.lifecycle.SavedStateHandle
import com.wlav.stlauncher.web.PendingSave
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LauncherViewModelTest {
    @Test
    fun `remote download survives activity state reconstruction`() {
        val originalState = SavedStateHandle()
        val original = LauncherViewModel(originalState)
        val pending =
            PendingSave.RemoteUrl(
                fileName = "backup.tar.gz",
                mimeType = "application/gzip",
                url = "http://127.0.0.1:8080/export",
                userAgent = "STLauncher/test",
                cancelUrl =
                    "http://127.0.0.1:8080/api/plugins/st-launcher/" +
                        "operations/12345678-1234-1234-1234-123456789abc",
            )

        original.pendingSave = pending
        val restored = LauncherViewModel(recreate(originalState))

        assertEquals(pending, restored.pendingSave)
        assertEquals(pending, restored.consumePendingSave())
        assertNull(restored.pendingSave)
    }

    @Test
    fun `temporary blob save preserves cleanup contract`() {
        val originalState = SavedStateHandle()
        val original = LauncherViewModel(originalState)
        val pending =
            PendingSave.TemporaryFile(
                fileName = "image.png",
                mimeType = "image/png",
                path = "/cache/blob-downloads/transfer.part",
                deleteAfterSave = true,
            )

        original.pendingSave = pending
        val restored = LauncherViewModel(recreate(originalState))

        assertEquals(pending, restored.pendingSave)
        restored.pendingSave = null
        assertNull(restored.pendingSave)
    }

    private fun recreate(source: SavedStateHandle): SavedStateHandle =
        SavedStateHandle(
            source.keys().associateWith { key -> source.get<Any?>(key) },
        )
}
