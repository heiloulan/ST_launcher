package com.wlav.stlauncher.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class RuntimeVersionRegistryTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `first install remains pending until health is committed`() {
        val registry = RuntimeVersionRegistry()
        val candidate = installed("runtime-new")

        val activation = registry.select(root, "runtime-new")

        assertTrue(activation.isCandidate)
        assertNull(activation.previousPath)
        assertFalse(root.resolve("active-version").toFile().exists())

        registry.commit(root, activation)
        assertEquals("runtime-new", root.resolve("active-version").readText().trim())
        assertFalse(root.resolve("pending-version.json").toFile().exists())
        assertEquals(candidate, activation.runtimePath)
    }

    @Test
    fun `candidate without a previous runtime cannot roll back`() {
        val registry = RuntimeVersionRegistry()
        installed("runtime-only")
        val activation = registry.select(root, "runtime-only")

        assertNull(registry.rollback(root, activation))

        val retriedAfterInterruption = registry.select(root, "runtime-only")
        assertTrue(retriedAfterInterruption.isCandidate)
        assertNull(retriedAfterInterruption.previousId)
    }

    @Test
    fun `failed candidate rolls back to active runtime and is remembered`() {
        val registry = RuntimeVersionRegistry()
        installed("runtime-old")
        registry.commit(root, registry.select(root, "runtime-old"))
        installed("runtime-new")
        val candidate = registry.select(root, "runtime-new")

        val fallback = registry.rollback(root, candidate)

        assertEquals("runtime-old", fallback?.runtimeId)
        assertFalse(fallback!!.isCandidate)
        assertEquals("runtime-old", root.resolve("active-version").readText().trim())
        assertEquals("runtime-new", root.resolve("failed-version").readText().trim())
        assertFalse(root.resolve("pending-version.json").toFile().exists())

        val selectedAgain = registry.select(root, "runtime-new")
        assertEquals("runtime-old", selectedAgain.runtimeId)
        assertFalse(selectedAgain.isCandidate)
    }

    @Test
    fun `interrupted pending activation conservatively selects old runtime`() {
        val registry = RuntimeVersionRegistry()
        installed("runtime-old")
        registry.commit(root, registry.select(root, "runtime-old"))
        installed("runtime-new")
        registry.select(root, "runtime-new")

        val afterRestart = RuntimeVersionRegistry().select(root, "runtime-new")

        assertEquals("runtime-old", afterRestart.runtimeId)
        assertFalse(afterRestart.isCandidate)
        assertEquals("runtime-new", root.resolve("failed-version").readText().trim())
    }

    @Test
    fun `successful candidate keeps one previous version and prunes older versions`() {
        val registry = RuntimeVersionRegistry()
        installed("runtime-ancient")
        registry.commit(root, registry.select(root, "runtime-ancient"))
        installed("runtime-old")
        registry.commit(root, registry.select(root, "runtime-old"))
        installed("runtime-new")
        root.resolve("versions/not-a-directory").writeText("ignored")
        root.resolve("versions/.install-leftover").createDirectories()

        registry.commit(root, registry.select(root, "runtime-new"))

        assertFalse(root.resolve("versions/runtime-ancient").toFile().exists())
        assertTrue(root.resolve("versions/runtime-old").toFile().isDirectory)
        assertTrue(root.resolve("versions/runtime-new").toFile().isDirectory)
        assertTrue(root.resolve("versions/not-a-directory").toFile().isFile)
        assertTrue(root.resolve("versions/.install-leftover").toFile().isDirectory)
        assertEquals("runtime-old", root.resolve("previous-version").readText().trim())
    }

    @Test
    fun `active candidate is reused without creating pending state`() {
        val registry = RuntimeVersionRegistry()
        installed("runtime-current")
        registry.commit(root, registry.select(root, "runtime-current"))

        val current = registry.select(root, "runtime-current")

        assertEquals("runtime-current", current.runtimeId)
        assertFalse(current.isCandidate)
        assertFalse(root.resolve("pending-version.json").toFile().exists())
    }

    @Test
    fun `invalid missing and forged activations are rejected`() {
        val registry = RuntimeVersionRegistry()
        assertThrows(IllegalArgumentException::class.java) {
            registry.select(root, "../escape")
        }
        assertThrows(IllegalStateException::class.java) {
            registry.select(root, "not-installed")
        }

        installed("runtime-safe")
        val activation = registry.select(root, "runtime-safe")
        val forged = activation.copy(runtimePath = root.resolve("outside"))
        assertThrows(IllegalArgumentException::class.java) {
            registry.commit(root, forged)
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.commit(
                root,
                RuntimeActivation("../invalid", root.resolve("outside"), isCandidate = true),
            )
        }
        assertNull(
            registry.rollback(
                root,
                activation.copy(previousId = "runtime-old", previousPath = null),
            ),
        )
        assertNull(
            registry.rollback(
                root,
                activation.copy(
                    previousId = "runtime-old",
                    previousPath = root.resolve("missing-previous"),
                ),
            ),
        )
        assertNull(registry.rollback(root, activation.copy(isCandidate = false)))

        Files.delete(activation.runtimePath.resolve(RuntimeBundleInstaller.COMPLETE_MARKER))
        assertThrows(IllegalArgumentException::class.java) {
            registry.commit(root, activation)
        }
    }

    @Test
    fun `corrupt markers cannot redirect runtime selection`() {
        val registry = RuntimeVersionRegistry()
        installed("runtime-safe")
        root.resolve("active-version").writeText("../escape")
        root.resolve("pending-version.json").writeText(
            """{"candidateId":"runtime-other","previousId":"../bad"}""",
        )

        val activation = registry.select(root, "runtime-safe")

        assertTrue(activation.isCandidate)
        assertNull(activation.previousPath)
    }

    private fun installed(id: String): Path =
        root
            .resolve("versions/$id")
            .createDirectories()
            .also {
                it.resolve(RuntimeBundleInstaller.COMPLETE_MARKER).writeText("digest")
            }
}
