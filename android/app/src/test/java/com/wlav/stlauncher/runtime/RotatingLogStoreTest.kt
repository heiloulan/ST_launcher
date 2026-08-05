package com.wlav.stlauncher.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RotatingLogStoreTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `rotates files and retains only the configured history`() {
        val store =
            RotatingLogStore(
                directory = temporaryDirectory,
                maximumFileBytes = 8,
                maximumFiles = 3,
            )

        repeat(8) { index ->
            store.openSession().use { it.append("line-$index") }
        }

        assertTrue(File(temporaryDirectory, "server.log").isFile)
        assertTrue(File(temporaryDirectory, "server.1.log").isFile)
        assertTrue(File(temporaryDirectory, "server.2.log").isFile)
        assertFalse(File(temporaryDirectory, "server.3.log").exists())
    }

    @Test
    fun `tail is bounded by line count`() {
        val store = RotatingLogStore(temporaryDirectory)
        store.openSession().use { session ->
            repeat(20) { session.append("message-$it") }
        }

        assertEquals(
            listOf("message-17", "message-18", "message-19"),
            store.tail(maximumLines = 3).lines(),
        )
    }

    @Test
    fun `tail spans rotated files so a fresh rotation does not hide the cause`() {
        val store =
            RotatingLogStore(
                directory = temporaryDirectory,
                maximumFileBytes = 16,
                maximumFiles = 3,
            )
        repeat(6) { index ->
            store.openSession().use { it.append("line-$index") }
        }

        // Rotation has moved the earlier lines out of server.log; the tail has
        // to walk backwards to find them.
        assertTrue(File(temporaryDirectory, "server.1.log").isFile)
        val tail = store.tail(maximumLines = 6).lines()

        assertEquals(listOf("line-1", "line-2", "line-3", "line-4", "line-5"), tail.takeLast(5))
        assertEquals(tail, tail.sortedBy { it.substringAfter('-').toInt() })
    }

    @Test
    fun `tail is still bounded by line count when spanning rotated files`() {
        val store =
            RotatingLogStore(
                directory = temporaryDirectory,
                maximumFileBytes = 16,
                maximumFiles = 3,
            )
        repeat(6) { index ->
            store.openSession().use { it.append("line-$index") }
        }

        assertEquals(2, store.tail(maximumLines = 2).lines().size)
    }

    @Test
    fun `tail returns an empty string when nothing has been logged`() {
        val store = RotatingLogStore(temporaryDirectory)

        // Deciding what the user should see here belongs to the UI layer.
        assertEquals("", store.tail())
    }

    @Test
    fun `a single oversized line never exceeds the file limit`() {
        val store =
            RotatingLogStore(
                directory = temporaryDirectory,
                maximumFileBytes = 32,
                maximumFiles = 2,
            )

        store.openSession().use { it.append("🍻".repeat(100)) }

        assertTrue(File(temporaryDirectory, "server.log").length() <= 32)
    }
}
