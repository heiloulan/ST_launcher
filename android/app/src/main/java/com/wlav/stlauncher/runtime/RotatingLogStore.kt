package com.wlav.stlauncher.runtime

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

class RotatingLogStore(
    private val directory: File,
    private val maximumFileBytes: Long = 2L * 1024 * 1024,
    private val maximumFiles: Int = 5,
) {
    private val lock = Any()
    private val activeFile: File
        get() = File(directory, "server.log")

    init {
        require(maximumFileBytes > 1)
        require(maximumFiles > 0)
        directory.mkdirs()
    }

    fun openSession(): Session =
        synchronized(lock) {
            rotateIfNeeded(force = activeFile.length() >= maximumFileBytes)
            Session(activeOutput(), activeFile.length())
        }

    /**
     * The most recent [maximumLines] lines, reading no more than [maximumBytes]
     * from disk.
     *
     * Walks backwards through the rotated history rather than stopping at the
     * active file: a crash loop that has just rotated keeps the interesting part
     * in `server.1.log`.
     *
     * Returns an empty string when nothing has been logged yet. Deciding what to
     * show the user in that case belongs to the UI layer, not here.
     */
    fun tail(
        maximumLines: Int = 200,
        maximumBytes: Int = 256 * 1024,
    ): String =
        synchronized(lock) {
            val collected = ArrayDeque<String>()
            var remainingBytes = maximumBytes.toLong()
            for (file in newestFirst()) {
                if (collected.size >= maximumLines || remainingBytes <= 0) break
                val length = file.takeIf(File::isFile)?.length() ?: 0L
                if (length == 0L) continue
                readTail(file, remainingBytes).let { lines ->
                    var index = lines.lastIndex
                    while (index >= 0 && collected.size < maximumLines) {
                        collected.addFirst(lines[index])
                        index--
                    }
                }
                remainingBytes -= minOf(length, remainingBytes)
            }
            collected.joinToString("\n")
        }

    /** Newest first: the active file, then `server.1.log` … `server.N.log`. */
    private fun newestFirst(): List<File> =
        buildList {
            add(activeFile)
            for (index in 1 until maximumFiles) {
                add(File(directory, "server.$index.log"))
            }
        }

    private fun readTail(
        file: File,
        maximumBytes: Long,
    ): List<String> =
        RandomAccessFile(file, "r").use { handle ->
            val length = handle.length()
            val start = (length - maximumBytes).coerceAtLeast(0)
            handle.seek(start)
            val bytes = ByteArray((length - start).toInt())
            handle.readFully(bytes)
            val lines =
                bytes
                    .toString(StandardCharsets.UTF_8)
                    .trimEnd('\r', '\n')
                    .split('\n')
            // A partial read starts mid-line, and possibly mid-codepoint, so the
            // first fragment is dropped rather than shown mangled.
            if (start > 0 && lines.size > 1) lines.drop(1) else lines
        }

    inner class Session internal constructor(
        private var output: BufferedOutputStream,
        private var currentBytes: Long,
    ) : Closeable {
        private var pendingLines = 0
        private var closed = false

        fun append(line: String) =
            synchronized(lock) {
                if (closed) return@synchronized
                val encoded = boundedUtf8(line)
                val lineBytes = encoded.size + 1L
                if (currentBytes > 0 && currentBytes + lineBytes > maximumFileBytes) {
                    output.flush()
                    output.close()
                    rotateIfNeeded(force = true)
                    output = activeOutput()
                    currentBytes = 0
                }
                output.write(encoded)
                output.write('\n'.code)
                currentBytes += lineBytes
                pendingLines++
                if (pendingLines >= FLUSH_EVERY_LINES) {
                    output.flush()
                    pendingLines = 0
                }
            }

        override fun close() =
            synchronized(lock) {
                if (closed) return@synchronized
                closed = true
                output.flush()
                output.close()
            }
    }

    private fun boundedUtf8(line: String): ByteArray {
        val encoded = line.toByteArray(StandardCharsets.UTF_8)
        val limit =
            minOf(MAXIMUM_LINE_BYTES, maximumFileBytes - 1)
                .coerceAtLeast(0)
                .toInt()
        if (encoded.size <= limit) return encoded
        var end = limit
        while (end > 0 && end < encoded.size && (encoded[end].toInt() and 0xC0) == 0x80) {
            end--
        }
        return encoded.copyOf(end)
    }

    private fun rotateIfNeeded(force: Boolean) {
        if (!force || !activeFile.exists()) return
        File(directory, "server.${maximumFiles - 1}.log").delete()
        for (index in maximumFiles - 2 downTo 1) {
            val source = File(directory, "server.$index.log")
            if (source.exists()) {
                source.renameTo(File(directory, "server.${index + 1}.log"))
            }
        }
        activeFile.renameTo(File(directory, "server.1.log"))
    }

    private fun activeOutput(): BufferedOutputStream = BufferedOutputStream(FileOutputStream(activeFile, true), DEFAULT_BUFFER_SIZE)

    companion object {
        private const val FLUSH_EVERY_LINES = 20
        private const val MAXIMUM_LINE_BYTES = 64 * 1024L
    }
}
