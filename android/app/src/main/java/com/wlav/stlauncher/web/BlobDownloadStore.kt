package com.wlav.stlauncher.web

import android.annotation.SuppressLint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64

class BlobDownloadStore(
    cacheDirectory: File,
    private val reserveBytes: Long = 512L * 1024 * 1024,
    private val retainedPath: String? = null,
) {
    private val root = File(cacheDirectory, "blob-downloads").apply { mkdirs() }
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, Session>()

    init {
        root
            .listFiles { file ->
                file.absolutePath != retainedPath &&
                    (
                        file.name.endsWith(".part") ||
                            (file.name.endsWith(".ready") && System.currentTimeMillis() - file.lastModified() > 7L * 24 * 60 * 60 * 1000)
                    )
            }?.forEach(File::delete)
    }

    suspend fun begin(
        id: String,
        fileName: String,
        mimeType: String,
        expectedBytes: Long?,
    ) = mutex.withLock {
        requireId(id)
        require(!sessions.containsKey(id)) { "A blob transfer with this ID already exists" }
        require(sessions.size < MAXIMUM_ACTIVE_TRANSFERS) { "Too many active blob transfers" }
        if (expectedBytes != null) {
            require(expectedBytes >= 0) { "Invalid blob size" }
            ensureSpace(expectedBytes)
        }

        val file = File(root, "$id.part")
        file.delete()
        sessions[id] =
            Session(
                fileName = sanitizeFileName(fileName),
                mimeType = mimeType.ifBlank { DEFAULT_MIME },
                file = file,
                output = BufferedOutputStream(file.outputStream()),
                expectedBytes = expectedBytes,
            )
    }

    suspend fun append(
        id: String,
        sequence: Int,
        encodedChunk: String,
    ) = mutex.withLock {
        val session = sessions[id] ?: throw IOException("Unknown blob transfer")
        require(sequence == session.nextSequence) { "Blob chunks arrived out of order" }
        require(encodedChunk.length <= MAXIMUM_ENCODED_CHUNK) { "Blob chunk is too large" }
        val bytes =
            try {
                Base64.getDecoder().decode(encodedChunk)
            } catch (error: IllegalArgumentException) {
                throw IOException("Blob chunk is not valid Base64", error)
            }
        require(bytes.size <= MAXIMUM_DECODED_CHUNK) { "Blob chunk is too large" }
        ensureSpace(bytes.size.toLong())
        session.output.write(bytes)
        session.totalBytes += bytes.size
        session.nextSequence++
    }

    suspend fun finish(id: String): PendingSave.TemporaryFile =
        mutex.withLock {
            val session = sessions.remove(id) ?: throw IOException("Unknown blob transfer")
            session.output.flush()
            session.output.close()
            if (session.expectedBytes != null &&
                session.totalBytes != session.expectedBytes
            ) {
                session.file.delete()
                throw IOException(
                    "Blob size mismatch: expected ${session.expectedBytes}, " +
                        "received ${session.totalBytes}",
                )
            }
            if (!session.file.isFile) {
                throw IOException("Blob transfer produced no file")
            }
            val ready = File(root, "$id.ready")
            if (!session.file.renameTo(ready)) throw IOException("Unable to finish blob download")
            PendingSave.TemporaryFile(
                fileName = session.fileName,
                mimeType = session.mimeType,
                path = ready.absolutePath,
            )
        }

    suspend fun cancel(id: String) =
        mutex.withLock {
            sessions.remove(id)?.let { session ->
                runCatching { session.output.close() }
                session.file.delete()
            }
        }

    suspend fun cancelAll() =
        mutex.withLock {
            sessions.values.forEach { session ->
                runCatching { session.output.close() }
                session.file.delete()
            }
            sessions.clear()
        }

    @SuppressLint("UsableSpace")
    private fun ensureSpace(incomingBytes: Long) {
        if (root.usableSpace - incomingBytes < reserveBytes) {
            throw IOException("Insufficient storage for download")
        }
    }

    private fun requireId(id: String) {
        require(ID_PATTERN.matches(id)) { "Invalid transfer ID" }
    }

    private fun sanitizeFileName(value: String): String =
        value
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f]"), "_")
            .take(180)
            .ifBlank { "download" }

    private data class Session(
        val fileName: String,
        val mimeType: String,
        val file: File,
        val output: BufferedOutputStream,
        val expectedBytes: Long?,
        var nextSequence: Int = 0,
        var totalBytes: Long = 0,
    )

    companion object {
        private val ID_PATTERN = Regex("[a-zA-Z0-9._-]{1,96}")
        private const val DEFAULT_MIME = "application/octet-stream"
        private const val MAXIMUM_ACTIVE_TRANSFERS = 2
        private const val MAXIMUM_DECODED_CHUNK = 256 * 1024
        private const val MAXIMUM_ENCODED_CHUNK = 360 * 1024
    }
}
