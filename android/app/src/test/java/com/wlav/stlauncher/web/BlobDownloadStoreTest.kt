package com.wlav.stlauncher.web

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.Base64

class BlobDownloadStoreTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `streams ordered chunks to a temporary file`() =
        runTest {
            val store = BlobDownloadStore(temporaryDirectory, reserveBytes = 0)
            store.begin(
                id = "transfer-1",
                fileName = "../unsafe/name.txt",
                mimeType = "text/plain",
                expectedBytes = 6,
            )
            store.append("transfer-1", 0, Base64.getEncoder().encodeToString("abc".toByteArray()))
            store.append("transfer-1", 1, Base64.getEncoder().encodeToString("def".toByteArray()))

            val result = store.finish("transfer-1")

            assertEquals("name.txt", result.fileName)
            assertEquals("text/plain", result.mimeType)
            assertArrayEquals("abcdef".toByteArray(), File(result.path).readBytes())
        }

    @Test
    fun `rejects malformed or out of order chunks`() =
        runTest {
            val store = BlobDownloadStore(temporaryDirectory, reserveBytes = 0)
            store.begin("transfer-2", "data.bin", "", null)

            val outOfOrder =
                runCatching {
                    store.append(
                        "transfer-2",
                        1,
                        Base64.getEncoder().encodeToString(byteArrayOf(1)),
                    )
                }.exceptionOrNull()
            assertInstanceOf(IllegalArgumentException::class.java, outOfOrder)

            val malformed =
                runCatching { store.append("transfer-2", 0, "not base64!") }
                    .exceptionOrNull()
            assertInstanceOf(IOException::class.java, malformed)

            store.cancel("transfer-2")
            assertFalse(File(temporaryDirectory, "blob-downloads/transfer-2.part").exists())
        }

    @Test
    fun `cancel all removes every partial transfer`() =
        runTest {
            val store = BlobDownloadStore(temporaryDirectory, reserveBytes = 0)
            store.begin("first", "first.bin", "", null)
            store.begin("second", "second.bin", "", null)

            store.cancelAll()

            assertFalse(File(temporaryDirectory, "blob-downloads/first.part").exists())
            assertFalse(File(temporaryDirectory, "blob-downloads/second.part").exists())
        }

    @Test
    fun `finish rejects a truncated blob and deletes it`() =
        runTest {
            val store = BlobDownloadStore(temporaryDirectory, reserveBytes = 0)
            store.begin("truncated", "data.bin", "", expectedBytes = 10)
            store.append(
                "truncated",
                0,
                Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
            )

            val error = runCatching { store.finish("truncated") }.exceptionOrNull()

            assertInstanceOf(IOException::class.java, error)
            assertFalse(File(temporaryDirectory, "blob-downloads/truncated.part").exists())
        }
}
