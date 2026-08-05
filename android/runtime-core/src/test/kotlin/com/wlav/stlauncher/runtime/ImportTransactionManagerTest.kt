package com.wlav.stlauncher.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ImportTransactionManagerTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `commit atomically replaces live data`() {
        val fixture = fixture()
        val transaction = fixture.manager.begin(fixture.id)
        assertEquals("imported", fixture.live.resolve("value").readText())

        fixture.manager.commit(transaction)

        assertEquals("imported", fixture.live.resolve("value").readText())
        assertFalse(fixture.operation.toFile().exists())
    }

    @Test
    fun `failed health check rolls back original data`() {
        val fixture = fixture()
        val transaction = fixture.manager.begin(fixture.id)
        fixture.live.resolve("new-write").writeText("new")

        fixture.manager.rollback(transaction)

        assertEquals("original", fixture.live.resolve("value").readText())
        assertFalse(
            fixture.live
                .resolve("new-write")
                .toFile()
                .exists(),
        )
    }

    @Test
    fun `crash recovery conservatively restores rollback`() {
        val fixture = fixture()
        fixture.manager.begin(fixture.id)

        fixture.manager.recover()

        assertEquals("original", fixture.live.resolve("value").readText())
        assertTrue(fixture.journal.readText().contains("recovered"))
    }

    @Test
    fun `crash recovery restores rollback when replacement data vanished`() {
        val fixture = fixture()
        fixture.manager.begin(fixture.id)
        RuntimeBundleInstaller.safeDeleteTree(fixture.live, fixture.live.parent)

        fixture.manager.recover()

        assertEquals("original", fixture.live.resolve("value").readText())
    }

    @Test
    fun `recovery finishes cleanup after a healthy import began committing`() {
        val fixture = fixture()
        val transaction = fixture.manager.begin(fixture.id)
        fixture.journal.writeText("""{"id":"${fixture.id}","state":"committing"}""")

        fixture.manager.recover()

        assertEquals("imported", fixture.live.resolve("value").readText())
        assertFalse(transaction.rollbackData.toFile().exists())
        assertFalse(transaction.operationRoot.toFile().exists())
        assertTrue(fixture.journal.readText().contains("complete"))
    }

    @Test
    fun `invalid and irrelevant journals are ignored`() {
        val fixture = fixture()
        fixture.journal.writeText("not-json")
        fixture.manager.recover()
        assertEquals("original", fixture.live.resolve("value").readText())

        fixture.journal.writeText("""{"id":{},"state":"swapping"}""")
        fixture.manager.recover()
        assertEquals("original", fixture.live.resolve("value").readText())

        fixture.journal.writeText("""{"id":"${fixture.id}","state":"complete"}""")
        fixture.manager.recover()
        assertEquals("original", fixture.live.resolve("value").readText())
    }

    @Test
    fun `journal without rollback is marked recovered without touching live data`() {
        val fixture = fixture()
        fixture.journal.writeText("""{"id":"${fixture.id}","state":"swapping"}""")

        fixture.manager.recover()

        assertEquals("original", fixture.live.resolve("value").readText())
        assertTrue(fixture.journal.readText().contains("recovered"))
    }

    @Test
    fun `begin validates identifiers files metadata and current data`() {
        val fixture = fixture()
        assertThrows(IllegalArgumentException::class.java) {
            fixture.manager.begin("../invalid")
        }

        fixture.operation.resolve("operation.json").writeText("not-json")
        assertThrows(IOException::class.java) { fixture.manager.begin(fixture.id) }

        fixture.operation.resolve("operation.json").writeText(
            """{"id":"${fixture.id}","kind":"export","status":"ready"}""",
        )
        assertThrows(IOException::class.java) { fixture.manager.begin(fixture.id) }

        fixture.operation.resolve("operation.json").writeText(
            """{"id":"${fixture.id}","kind":"import","status":"failed"}""",
        )
        assertThrows(IOException::class.java) { fixture.manager.begin(fixture.id) }

        RuntimeBundleInstaller.safeDeleteTree(
            fixture.operation.resolve("candidate"),
            fixture.operation,
        )
        assertThrows(IOException::class.java) { fixture.manager.begin(fixture.id) }
    }

    @Test
    fun `begin refuses an import when current data is missing`() {
        val fixture = fixture()
        RuntimeBundleInstaller.safeDeleteTree(fixture.live, fixture.live.parent)

        assertThrows(IOException::class.java) { fixture.manager.begin(fixture.id) }
    }

    @Test
    fun `rollback is idempotent after rollback data has gone`() {
        val fixture = fixture()
        val transaction = fixture.manager.begin(fixture.id)
        fixture.manager.rollback(transaction)

        fixture.manager.rollback(transaction)

        assertEquals("original", fixture.live.resolve("value").readText())
        assertTrue(fixture.journal.readText().contains("rolled_back"))
    }

    @Test
    fun `commit and rollback reject transactions from another manager`() {
        val fixture = fixture()
        val forged =
            ImportTransactionManager.Transaction(
                id = fixture.id,
                operationRoot = root.resolve("other-operation"),
                rollbackData = fixture.live,
            )

        assertThrows(IllegalArgumentException::class.java) {
            fixture.manager.commit(forged)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.manager.rollback(forged)
        }
        assertEquals("original", fixture.live.resolve("value").readText())
    }

    private fun fixture(): Fixture {
        val id = UUID.randomUUID().toString()
        val operations = root.resolve("operations").createDirectories()
        val operation = operations.resolve(id).createDirectories()
        val candidate = operation.resolve("candidate/data").createDirectories()
        candidate.resolve("value").writeText("imported")
        operation.resolve("operation.json").writeText(
            """{"id":"$id","kind":"import","status":"ready"}""",
        )
        val live = root.resolve("user-data").createDirectories()
        live.resolve("value").writeText("original")
        val journal = root.resolve("import.journal")
        return Fixture(
            id = id,
            operation = operation,
            live = live,
            journal = journal,
            manager = ImportTransactionManager(operations, live, journal),
        )
    }

    private data class Fixture(
        val id: String,
        val operation: Path,
        val live: Path,
        val journal: Path,
        val manager: ImportTransactionManager,
    )
}
