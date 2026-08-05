package com.wlav.stlauncher.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class LegacyDataMigratorTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `legacy data is copied and verified without deleting its source`() {
        val legacyRoot = root.resolve("legacy")
        val legacyData = legacyRoot.resolve("data").createDirectories()
        val legacyConfig = legacyRoot.resolve("config.yaml")
        legacyData.resolve("default-user/chats").createDirectories()
        legacyData.resolve("default-user/empty").createDirectories()
        legacyData.resolve("default-user/chats/chat.jsonl").writeText("""{"mes":"hello"}""")
        legacyConfig.writeText("listen: false")

        val targetData = root.resolve("new/user-data")
        val targetConfig = root.resolve("new/config/config.yaml")
        val result =
            JournaledLegacyDataMigrator().migrate(
                legacyData = legacyData,
                legacyConfig = legacyConfig,
                targetData = targetData,
                targetConfig = targetConfig,
                journal = root.resolve("migration.journal"),
            )

        assertEquals(MigrationResult.Migrated(1), result)
        assertEquals(
            """{"mes":"hello"}""",
            targetData.resolve("default-user/chats/chat.jsonl").readText(),
        )
        assertEquals("listen: false", targetConfig.readText())
        assertTrue(targetData.resolve("default-user/empty").toFile().isDirectory)
        assertTrue(legacyData.resolve("default-user/chats/chat.jsonl").toFile().isFile)

        assertTrue(
            JournaledLegacyDataMigrator().commit(
                legacyData = legacyData,
                targetData = targetData,
                journal = root.resolve("migration.journal"),
            ),
        )
        assertFalse(legacyData.toFile().exists())
        assertEquals(
            """{"mes":"hello"}""",
            targetData.resolve("default-user/chats/chat.jsonl").readText(),
        )
        assertTrue(
            JournaledLegacyDataMigrator().commit(
                legacyData = legacyData,
                targetData = targetData,
                journal = root.resolve("migration.journal"),
            ),
        )
    }

    @Test
    fun `existing target is never overwritten`() {
        val legacy = root.resolve("legacy/data").createDirectories()
        legacy.resolve("old").writeText("old")
        val target = root.resolve("new/user-data").createDirectories()
        target.resolve("current").writeText("current")

        val result =
            JournaledLegacyDataMigrator().migrate(
                legacyData = legacy,
                legacyConfig = null,
                targetData = target,
                targetConfig = root.resolve("new/config.yaml"),
                journal = root.resolve("migration.journal"),
            )

        assertEquals(MigrationResult.AlreadyMigrated, result)
        assertEquals("current", target.resolve("current").readText())
    }

    @Test
    fun `missing legacy data is a no-op`() {
        val result =
            JournaledLegacyDataMigrator().migrate(
                legacyData = root.resolve("missing"),
                legacyConfig = null,
                targetData = root.resolve("new/user-data"),
                targetConfig = root.resolve("new/config.yaml"),
                journal = root.resolve("migration.journal"),
            )

        assertEquals(MigrationResult.NoLegacyData, result)
        assertFalse(root.resolve("migration.journal").toFile().exists())
    }

    @Test
    fun `empty data migrates without replacing an existing config`() {
        val legacy = root.resolve("legacy/data").createDirectories()
        val legacyConfig = root.resolve("legacy/config.yaml").also { it.writeText("legacy") }
        val targetConfig =
            root
                .resolve("new/config.yaml")
                .also {
                    it.parent.createDirectories()
                    it.writeText("current")
                }

        val result =
            JournaledLegacyDataMigrator().migrate(
                legacyData = legacy,
                legacyConfig = legacyConfig,
                targetData = root.resolve("new/user-data"),
                targetConfig = targetConfig,
                journal = root.resolve("migration.journal"),
            )

        assertEquals(MigrationResult.Migrated(0), result)
        assertEquals("current", targetConfig.readText())
    }

    @Test
    fun `a source mutation aborts without exposing partial target data`() {
        val legacy = root.resolve("legacy/data").createDirectories()
        legacy.resolve("first").writeText("first")
        val target = root.resolve("new/user-data")
        val journal = root.resolve("migration.journal")

        assertThrows(NonRetryableRuntimeException::class.java) {
            JournaledLegacyDataMigrator().migrate(
                legacyData = legacy,
                legacyConfig = null,
                targetData = target,
                targetConfig = root.resolve("new/config.yaml"),
                journal = journal,
            ) { progress ->
                if (progress == 100) legacy.resolve("late-file").writeText("late")
            }
        }

        assertFalse(target.toFile().exists())
        assertTrue(legacy.resolve("first").toFile().isFile)
        assertTrue(journal.readText().startsWith("failed:"))
    }

    @Test
    fun `links in legacy data are rejected`() {
        val legacy = root.resolve("legacy/data").createDirectories()
        val outside = root.resolve("outside").also { it.writeText("secret") }
        Files.createSymbolicLink(legacy.resolve("link"), outside)

        assertThrows(NonRetryableRuntimeException::class.java) {
            JournaledLegacyDataMigrator().migrate(
                legacyData = legacy,
                legacyConfig = null,
                targetData = root.resolve("new/user-data"),
                targetConfig = root.resolve("new/config.yaml"),
                journal = root.resolve("migration.journal"),
            )
        }
    }

    @Test
    fun `interrupted staging is removed only for a trusted journal name`() {
        val target = root.resolve("new/user-data")
        target.parent.createDirectories()
        val journal = root.resolve("migration.journal")
        val staging = target.parent.resolve(".user-data.migrating-test").createDirectories()
        staging.resolve("partial").writeText("partial")
        journal.writeText("copying:${staging.fileName}")

        JournaledLegacyDataMigrator().recover(target, journal)

        assertFalse(staging.toFile().exists())
        assertEquals("recovered", journal.readText())

        val unrelated = target.parent.resolve("unrelated").createDirectories()
        journal.writeText("copying:${unrelated.fileName}")
        JournaledLegacyDataMigrator().recover(target, journal)
        assertTrue(unrelated.toFile().isDirectory)

        target.createDirectories()
        journal.writeText("copying:.user-data.migrating-other")
        JournaledLegacyDataMigrator().recover(target, journal)
        assertTrue(journal.readText().startsWith("copying:"))
    }

    @Test
    fun `commit never removes legacy data without a completed journal and verified target`() {
        val legacy = root.resolve("legacy/data").createDirectories()
        legacy.resolve("chat").writeText("preserve")
        val target = root.resolve("new/user-data").createDirectories()
        val journal = root.resolve("migration.journal")

        assertFalse(
            JournaledLegacyDataMigrator().commit(
                legacyData = legacy,
                targetData = target,
                journal = journal,
            ),
        )
        assertTrue(legacy.resolve("chat").toFile().isFile)

        journal.writeText("complete")
        RuntimeBundleInstaller.safeDeleteTree(target, target.parent)
        assertFalse(
            JournaledLegacyDataMigrator().commit(
                legacyData = legacy,
                targetData = target,
                journal = journal,
            ),
        )
        assertTrue(legacy.resolve("chat").toFile().isFile)
    }

    @Test
    fun `commit resumes an interrupted legacy cleanup`() {
        val legacy = root.resolve("legacy/data").createDirectories()
        legacy.resolve("partial").writeText("preserve until commit")
        val target = root.resolve("new/user-data").createDirectories()
        target.resolve("verified").writeText("current")
        val journal = root.resolve("migration.journal").also { it.writeText("committing") }

        assertTrue(
            JournaledLegacyDataMigrator().commit(
                legacyData = legacy,
                targetData = target,
                journal = journal,
            ),
        )

        assertFalse(legacy.toFile().exists())
        assertEquals("current", target.resolve("verified").readText())
        assertEquals("committed", journal.readText())
    }

    @Test
    fun `copy retry count must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            JournaledLegacyDataMigrator(maximumCopyAttempts = 0)
        }
    }
}
