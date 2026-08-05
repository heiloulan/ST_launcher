package com.wlav.stlauncher.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

class RuntimeBundleInstallerTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `runtime manifest parses pinned metadata and files`() {
        val manifest =
            RuntimeManifest.parse(
                """
                {
                  "formatVersion": 1,
                  "runtimeId": "st-test",
                  "sillyTavernVersion": "1.18.0",
                  "sillyTavernCommit": "abc",
                  "nodeVersion": "24.18.0",
                  "bundleSha256": "${"a".repeat(64)}",
                  "uncompressedBytes": 3,
                  "files": [
                    {
                      "path": "app/server.js",
                      "size": 3,
                      "sha256": "${"b".repeat(64)}"
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertEquals("st-test", manifest.runtimeId)
        assertEquals("1.18.0", manifest.sillyTavernVersion)
        assertEquals(3, manifest.uncompressedBytes)
        assertEquals("app/server.js", manifest.files.single().path)
    }

    @Test
    fun `runtime manifest rejects unsupported format`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeManifest.parse(
                """
                {
                  "formatVersion": 9,
                  "runtimeId": "st-test",
                  "sillyTavernVersion": "test",
                  "sillyTavernCommit": "test",
                  "nodeVersion": "test",
                  "bundleSha256": "${"a".repeat(64)}",
                  "uncompressedBytes": 1,
                  "files": [
                    {
                      "path": "app/server.js",
                      "size": 1,
                      "sha256": "${"b".repeat(64)}"
                    }
                  ]
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `runtime manifest validates identifiers digests sizes and paths`() {
        val digest = "a".repeat(64)
        val fileDigest = "b".repeat(64)
        val validFile = RuntimeFile("app/server.js", 1, fileDigest)

        listOf(
            { RuntimeManifest(1, "", "1", "commit", "24", digest, 1, listOf(validFile)) },
            {
                RuntimeManifest(
                    1,
                    "runtime",
                    "1",
                    "commit",
                    "24",
                    "invalid",
                    1,
                    listOf(validFile),
                )
            },
            {
                RuntimeManifest(
                    1,
                    "runtime",
                    "1",
                    "commit",
                    "24",
                    digest,
                    -1,
                    listOf(validFile),
                )
            },
            { RuntimeManifest(1, "runtime", "1", "commit", "24", digest, 0, emptyList()) },
            {
                RuntimeManifest(
                    1,
                    "runtime",
                    "1",
                    "commit",
                    "24",
                    digest,
                    2,
                    listOf(validFile, validFile),
                )
            },
            { RuntimeFile("../escape", 1, fileDigest) },
            { RuntimeFile("app/server.js", -1, fileDigest) },
            { RuntimeFile("app/server.js", 1, "invalid") },
        ).forEach { constructor ->
            assertThrows(IllegalArgumentException::class.java) { constructor() }
        }
    }

    @Test
    fun `runtime manifest parser rejects missing and invalid fields`() {
        val valid =
            """
            {
              "formatVersion": 1,
              "runtimeId": "runtime",
              "sillyTavernVersion": "1.18.0",
              "sillyTavernCommit": "commit",
              "nodeVersion": "24",
              "bundleSha256": "${"a".repeat(64)}",
              "uncompressedBytes": 1,
              "files": [
                {"path":"app/server.js","size":1,"sha256":"${"b".repeat(64)}"}
              ]
            }
            """.trimIndent()

        listOf(
            valid.replace("\"runtimeId\": \"runtime\",", ""),
            valid.replace("\"formatVersion\": 1", "\"formatVersion\": \"bad\""),
            valid.replace("\"uncompressedBytes\": 1", "\"uncompressedBytes\": \"bad\""),
            valid.replace(Regex(",\\s*\"files\"[\\s\\S]*"), "\n}"),
        ).forEach { source ->
            assertThrows(IllegalStateException::class.java) {
                RuntimeManifest.parse(source)
            }
        }
    }

    @Test
    fun `verified bundle installs atomically`() {
        val files =
            mapOf(
                "app/server.js" to "console.log('ready')".toByteArray(),
                "lib/libcrypto.so.3" to byteArrayOf(1, 2, 3),
            )
        val bundle = createZip(files)
        val manifest = manifestFor(bundle, files)

        val installed =
            RuntimeBundleInstaller(minimumFreeBytes = 0).install(
                ByteArrayInputStream(bundle),
                manifest,
                root,
            )

        assertEquals(manifest.versionHash, installed.fileName.toString())
        assertEquals("console.log('ready')", installed.resolve("app/server.js").readText())
        assertEquals(manifest.bundleSha256, installed.resolve(".complete").readText())
    }

    @Test
    fun `large filesystem does not inflate the fixed installation reserve`() {
        val files = mapOf("app/server.js" to "ready".toByteArray())
        val bundle = createZip(files)
        val manifest = manifestFor(bundle, files)
        var queriedPath: Path? = null
        val installer =
            RuntimeBundleInstaller(
                minimumFreeBytes = 512,
                storageSpaceProvider = { path ->
                    queriedPath = path
                    StorageSpace(totalBytes = 1_000_000, usableBytes = 517)
                },
            )

        installer.install(ByteArrayInputStream(bundle), manifest, root)

        assertEquals(root, queriedPath)
    }

    @Test
    fun `installer rejects actual usable space below payload and fixed reserve`() {
        val files = mapOf("app/server.js" to "ready".toByteArray())
        val bundle = createZip(files)
        val manifest = manifestFor(bundle, files)
        val installer =
            RuntimeBundleInstaller(
                minimumFreeBytes = 512,
                storageSpaceProvider = {
                    StorageSpace(totalBytes = 1_000_000, usableBytes = 516)
                },
            )

        val failure =
            assertThrows(NonRetryableRuntimeException::class.java) {
                installer.install(ByteArrayInputStream(bundle), manifest, root)
            }

        assertEquals(
            "Not enough free space: 517 bytes required, 516 bytes available",
            failure.cause?.message,
        )
    }

    @Test
    fun `portable storage provider does not depend on nio file store support`() {
        val space = FileStorageSpaceProvider.query(root)

        assertTrue(space.totalBytes > 0)
        assertTrue(space.usableBytes > 0)
        assertTrue(space.usableBytes <= space.totalBytes)
    }

    @Test
    fun `completed runtime is reused and stale staging is recovered`() {
        val files = mapOf("app/server.js" to "ready".toByteArray())
        val bundle = createZip(files)
        val manifest = manifestFor(bundle, files)
        val installer = RuntimeBundleInstaller(minimumFreeBytes = 0)
        val installed = installer.install(ByteArrayInputStream(bundle), manifest, root)
        installed.resolve("marker").writeText("preserved")
        val stale =
            root
                .resolve("versions/.install-interrupted")
                .also { it.toFile().mkdirs() }
        stale.resolve("partial").writeText("partial")

        installer.recover(root)
        val reused = installer.install(ByteArrayInputStream(byteArrayOf()), manifest, root)

        assertEquals(installed, reused)
        assertEquals("preserved", reused.resolve("marker").readText())
        assertFalse(stale.toFile().exists())
        RuntimeBundleInstaller().recover(root.resolve("missing"))
    }

    @Test
    fun `directory entries and progress are handled`() {
        val files = mapOf("app/server.js" to "ready".toByteArray())
        val bundle = createZip(files, directories = listOf("app/"))
        val manifest = manifestFor(bundle, files)
        var progress = 0

        RuntimeBundleInstaller(minimumFreeBytes = 0).install(
            ByteArrayInputStream(bundle),
            manifest,
            root,
        ) { progress = it }

        assertEquals(100, progress)
    }

    @Test
    fun `path traversal is rejected`() {
        val files = mapOf("../escape" to "bad".toByteArray())
        assertThrows(IllegalArgumentException::class.java) {
            manifestFor(createZip(files), files)
        }
    }

    @Test
    fun `tampered bundle is rejected`() {
        val declared = mapOf("app/server.js" to "expected".toByteArray())
        val actualBundle = createZip(mapOf("app/server.js" to "tampered".toByteArray()))
        val manifest = manifestFor(actualBundle, declared)

        assertThrows(NonRetryableRuntimeException::class.java) {
            RuntimeBundleInstaller(minimumFreeBytes = 0).install(
                ByteArrayInputStream(actualBundle),
                manifest,
                root,
            )
        }
    }

    @Test
    fun `bundle structure and aggregate verification failures are rejected`() {
        val one = mapOf("app/server.js" to "ready".toByteArray())
        val oneBundle = createZip(one)

        val unexpectedBundle = createZip(mapOf("other.js" to "ready".toByteArray()))
        assertInstallFails(unexpectedBundle, manifestFor(unexpectedBundle, one))

        val duplicateBundle =
            replaceAscii(
                createZip(
                    mapOf(
                        "app/server.js" to "ready".toByteArray(),
                        "app/second.js" to "ready".toByteArray(),
                    ),
                ),
                "app/second.js",
                "app/server.js",
            )
        assertInstallFails(duplicateBundle, manifestFor(duplicateBundle, one))

        val missingManifest =
            manifestFor(
                oneBundle,
                one + ("app/missing.js" to "missing".toByteArray()),
            )
        assertInstallFails(oneBundle, missingManifest)

        val wrongTotal = manifestFor(oneBundle, one).copy(uncompressedBytes = 999)
        assertInstallFails(oneBundle, wrongTotal)

        val wrongBundleDigest = manifestFor(oneBundle, one).copy(bundleSha256 = "0".repeat(64))
        assertInstallFails(oneBundle, wrongBundleDigest)

        val tooMany =
            RuntimeBundleInstaller(minimumFreeBytes = 0, maximumEntries = 1)
        val twoFiles =
            mapOf(
                "app/a.js" to byteArrayOf(1),
                "app/b.js" to byteArrayOf(2),
            )
        val twoBundle = createZip(twoFiles)
        assertThrows(NonRetryableRuntimeException::class.java) {
            tooMany.install(
                ByteArrayInputStream(twoBundle),
                manifestFor(twoBundle, twoFiles),
                root.resolve("entry-limit"),
            )
        }
    }

    @Test
    fun `delete guard never accepts a broad or unrelated target`() {
        val parent = root.resolve("versions").also { it.toFile().mkdirs() }
        val child = parent.resolve("child").also { it.toFile().mkdirs() }
        RuntimeBundleInstaller.safeDeleteTree(child, parent)
        assertFalse(child.toFile().exists())

        assertThrows(IllegalArgumentException::class.java) {
            RuntimeBundleInstaller.safeDeleteTree(parent, parent)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeBundleInstaller.safeDeleteTree(root.resolve("elsewhere"), parent)
        }
        assertTrue(parent.toFile().isDirectory)
    }

    private fun manifestFor(
        bundle: ByteArray,
        files: Map<String, ByteArray>,
    ) = RuntimeManifest(
        formatVersion = 1,
        runtimeId = "test-runtime",
        sillyTavernVersion = "test",
        sillyTavernCommit = "test",
        nodeVersion = "test",
        bundleSha256 = sha256(bundle),
        uncompressedBytes = files.values.sumOf { it.size.toLong() },
        files =
            files.map { (path, bytes) ->
                RuntimeFile(path, bytes.size.toLong(), sha256(bytes))
            },
    )

    private fun assertInstallFails(
        bundle: ByteArray,
        manifest: RuntimeManifest,
    ) {
        assertThrows(NonRetryableRuntimeException::class.java) {
            RuntimeBundleInstaller(minimumFreeBytes = 0).install(
                ByteArrayInputStream(bundle),
                manifest,
                root.resolve("failure-${System.nanoTime()}"),
            )
        }
    }

    private fun createZip(
        files: Map<String, ByteArray>,
        directories: List<String> = emptyList(),
    ): ByteArray = createZip(files.entries.map { it.key to it.value }, directories)

    private fun createZip(
        files: List<Pair<String, ByteArray>>,
        directories: List<String> = emptyList(),
    ): ByteArray =
        ByteArrayOutputStream()
            .also { buffer ->
                ZipOutputStream(buffer).use { zip ->
                    directories.forEach { directory ->
                        zip.putNextEntry(ZipEntry(directory))
                        zip.closeEntry()
                    }
                    files.forEach { (path, bytes) ->
                        zip.putNextEntry(ZipEntry(path))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
            }.toByteArray()

    private fun replaceAscii(
        source: ByteArray,
        from: String,
        to: String,
    ): ByteArray {
        require(from.length == to.length)
        val result = source.copyOf()
        val needle = from.toByteArray()
        val replacement = to.toByteArray()
        for (offset in 0..result.size - needle.size) {
            if (needle.indices.all { index -> result[offset + index] == needle[index] }) {
                replacement.copyInto(result, offset)
            }
        }
        return result
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
