package com.wlav.stlauncher.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SafeArchivePathTest {
    @Test
    fun `accepts normalized relative paths`() {
        assertEquals("app/server.js", SafeArchivePath.requireSafe("app/server.js"))
        assertEquals("data", SafeArchivePath.requireUserDataPath("data/"))
        assertEquals(
            "data/default-user/chats/chat.jsonl",
            SafeArchivePath.requireUserDataPath("data/default-user/chats/chat.jsonl"),
        )
    }

    @Test
    fun `rejects unsafe and non-normalized paths`() {
        listOf(
            "",
            " ",
            "x".repeat(513),
            "/data/file",
            "C:/data/file",
            "data\\file",
            ".",
            "..",
            "../data/file",
            "data/../../file",
            "data/./file",
            "data//file",
        ).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                SafeArchivePath.requireSafe(path)
            }
        }
    }

    @Test
    fun `backup path must remain under data root`() {
        listOf("database/file", "metadata.json", "data-other/file").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                SafeArchivePath.requireUserDataPath(path)
            }
        }
    }
}
