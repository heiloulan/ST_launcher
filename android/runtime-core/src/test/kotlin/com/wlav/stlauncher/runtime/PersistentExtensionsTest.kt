package com.wlav.stlauncher.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class PersistentExtensionsTest {
    @TempDir lateinit var root: Path

    @Test
    fun `migration preserves overrides and does not resurrect deleted user extensions`() {
        val old = root.resolve("old").createDirectories()
        old
            .resolve("helper")
            .createDirectories()
            .resolve("manifest.json")
            .writeText("old-helper")
        old
            .resolve("template")
            .createDirectories()
            .resolve("manifest.json")
            .writeText("old-template")
        val target = root.resolve("persistent").createDirectories()
        target
            .resolve("helper")
            .createDirectories()
            .resolve("manifest.json")
            .writeText("user-helper")
        val migration = PersistentExtensions(target, root.resolve("migration"))
        migration.migrate(listOf("legacy" to old))
        assertEquals("user-helper", target.resolve("helper/manifest.json").readText())
        assertEquals("old-template", target.resolve("template/manifest.json").readText())
        RuntimeBundleInstaller.safeDeleteTree(target.resolve("template"), target)
        migration.migrate(listOf("legacy" to old))
        assertFalse(target.resolve("template").toFile().exists())
        assertEquals("old-template", old.resolve("template/manifest.json").readText())
    }
}
