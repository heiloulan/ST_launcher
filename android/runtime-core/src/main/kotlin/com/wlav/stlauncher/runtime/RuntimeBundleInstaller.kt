package com.wlav.stlauncher.runtime

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

interface RuntimeInstaller {
    fun install(
        bundle: InputStream,
        manifest: RuntimeManifest,
        runtimeRoot: Path,
        progress: (Int) -> Unit = {},
    ): Path

    fun recover(runtimeRoot: Path)
}

data class StorageSpace(
    val totalBytes: Long,
    val usableBytes: Long,
)

fun interface StorageSpaceProvider {
    fun query(path: Path): StorageSpace
}

/**
 * Portable fallback for JVM callers. Android injects its StatFs-backed
 * implementation because some Android NIO providers do not implement
 * FileSystemProvider.getFileStore.
 */
object FileStorageSpaceProvider : StorageSpaceProvider {
    override fun query(path: Path): StorageSpace {
        val file = path.toFile()
        return StorageSpace(
            totalBytes = file.totalSpace,
            usableBytes = file.usableSpace,
        )
    }
}

class RuntimeBundleInstaller(
    private val minimumFreeBytes: Long = 128L * 1024 * 1024,
    private val maximumEntries: Int = 200_000,
    private val storageSpaceProvider: StorageSpaceProvider = FileStorageSpaceProvider,
) : RuntimeInstaller {
    override fun install(
        bundle: InputStream,
        manifest: RuntimeManifest,
        runtimeRoot: Path,
        progress: (Int) -> Unit,
    ): Path {
        val versionsRoot = runtimeRoot.resolve("versions").createDirectories()
        val destination = versionsRoot.resolve(manifest.versionHash)
        val completionMarker = destination.resolve(COMPLETE_MARKER)
        if (completionMarker.exists() &&
            completionMarker.readText().trim() == manifest.bundleSha256
        ) {
            return destination
        }

        val staging = versionsRoot.resolve(".install-${manifest.versionHash}-${UUID.randomUUID()}")
        val journal = runtimeRoot.resolve(INSTALL_JOURNAL)
        safeDeleteTree(staging, versionsRoot)
        staging.createDirectories()
        journal.writeText("extracting:${staging.name}")

        try {
            ensureFreeSpace(runtimeRoot, manifest.uncompressedBytes)
            extractAndVerify(bundle, manifest, staging, progress)
            staging.resolve(COMPLETE_MARKER).writeText(manifest.bundleSha256)
            journal.writeText("committing:${staging.name}")

            if (destination.exists()) {
                safeDeleteTree(destination, versionsRoot)
            }
            atomicMove(staging, destination)
            journal.writeText("complete:${manifest.versionHash}")
            return destination
        } catch (error: Throwable) {
            safeDeleteTree(staging, versionsRoot)
            journal.writeText("failed:${error::class.simpleName}")
            throw NonRetryableRuntimeException(
                code = "RUNTIME_INSTALL_FAILED",
                message = "Unable to install the bundled runtime",
                cause = error,
            )
        }
    }

    override fun recover(runtimeRoot: Path) {
        val versionsRoot = runtimeRoot.resolve("versions")
        if (!versionsRoot.isDirectory()) {
            return
        }
        Files.list(versionsRoot).use { entries ->
            entries
                .filter { it.fileName.toString().startsWith(".install-") }
                .forEach { safeDeleteTree(it, versionsRoot) }
        }
    }

    private fun extractAndVerify(
        source: InputStream,
        manifest: RuntimeManifest,
        staging: Path,
        progress: (Int) -> Unit,
    ) {
        val expected = manifest.files.associateBy { it.path }
        val seen = HashSet<String>(expected.size)
        val bundleDigest = MessageDigest.getInstance("SHA-256")
        var entryCount = 0
        var extractedBytes = 0L

        DigestInputStream(BufferedInputStream(source), bundleDigest).use { digested ->
            ZipInputStream(digested).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > maximumEntries) {
                        throw IOException("Runtime bundle contains too many entries")
                    }

                    val normalized = SafeArchivePath.requireSafe(entry.name)
                    val target = staging.resolve(normalized).normalize()
                    if (!target.startsWith(staging)) {
                        throw IOException("Runtime entry escapes staging root")
                    }

                    if (entry.isDirectory) {
                        target.createDirectories()
                        zip.closeEntry()
                        continue
                    }

                    val expectedFile =
                        expected[normalized]
                            ?: throw IOException("Unexpected runtime file: $normalized")
                    if (!seen.add(normalized)) {
                        throw IOException("Duplicate runtime file: $normalized")
                    }

                    target.parent.createDirectories()
                    val fileDigest = MessageDigest.getInstance("SHA-256")
                    var fileBytes = 0L
                    BufferedOutputStream(Files.newOutputStream(target)).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            fileDigest.update(buffer, 0, read)
                            fileBytes += read
                            extractedBytes += read
                            if (fileBytes > expectedFile.size ||
                                extractedBytes > manifest.uncompressedBytes
                            ) {
                                throw IOException("Runtime entry exceeds declared size")
                            }
                        }
                    }

                    if (fileBytes != expectedFile.size ||
                        fileDigest.hex() != expectedFile.sha256
                    ) {
                        throw IOException("Runtime file verification failed: $normalized")
                    }
                    zip.closeEntry()
                    progress((seen.size * 100 / expected.size).coerceIn(0, 100))
                }
                // ZipInputStream stops at the central directory. Drain any
                // compressed bytes it did not already buffer so the digest
                // covers the complete bundle, not only local file records.
                val trailingBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (digested.read(trailingBuffer) >= 0) {
                    // DigestInputStream performs the work.
                }
            }
        }

        if (seen.size != expected.size) {
            throw IOException("Runtime bundle is missing ${expected.size - seen.size} files")
        }
        if (extractedBytes != manifest.uncompressedBytes) {
            throw IOException("Runtime uncompressed size does not match its manifest")
        }
        if (bundleDigest.hex() != manifest.bundleSha256) {
            throw IOException("Runtime bundle SHA-256 does not match its manifest")
        }
    }

    private fun ensureFreeSpace(
        root: Path,
        requiredBytes: Long,
    ) {
        val space = storageSpaceProvider.query(root)
        if (space.totalBytes <= 0 || space.usableBytes < 0) {
            throw IOException("Unable to determine available storage space")
        }
        val reserve = minimumFreeBytes
        if (requiredBytes > space.usableBytes ||
            space.usableBytes - requiredBytes < reserve
        ) {
            val requiredWithReserve =
                if (requiredBytes > Long.MAX_VALUE - reserve) {
                    Long.MAX_VALUE
                } else {
                    requiredBytes + reserve
                }
            throw IOException(
                "Not enough free space: $requiredWithReserve bytes required, " +
                    "${space.usableBytes} bytes available",
            )
        }
    }

    companion object {
        const val COMPLETE_MARKER = ".complete"
        const val INSTALL_JOURNAL = "install.journal"

        internal fun atomicMove(
            source: Path,
            destination: Path,
        ) {
            try {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source, destination)
            }
        }

        internal fun safeDeleteTree(
            target: Path,
            expectedParent: Path,
        ) {
            val normalizedTarget = target.toAbsolutePath().normalize()
            val normalizedParent = expectedParent.toAbsolutePath().normalize()
            require(normalizedTarget.parent == normalizedParent) {
                "Refusing to delete outside the expected parent: $target"
            }
            if (!normalizedTarget.exists()) {
                return
            }
            Files.walk(normalizedTarget).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}

private fun MessageDigest.hex(): String = digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
