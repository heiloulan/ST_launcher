package com.wlav.stlauncher.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

class ImportTransactionManager(
    private val operationsRoot: Path,
    private val liveData: Path,
    private val journal: Path,
    private val configRoot: Path? = null,
    private val extensionsRoot: Path? = null,
    private val journalWriter: (Path, String) -> Unit = ::writeAtomicText,
) {
    data class Transaction(
        val id: String,
        val operationRoot: Path,
        val rollbackData: Path,
    )

    class ActivationException(
        val originalRestored: Boolean,
        cause: Throwable,
    ) : IOException(
            if (originalRestored) {
                "Unable to activate import; original data restored"
            } else {
                "Import recovery is incomplete; original copies are retained for rescue"
            },
            cause,
        )

    private data class Component(
        val name: String,
        val live: Path,
        val hadOriginal: Boolean,
    ) {
        fun rollback(id: String): Path = live.resolveSibling(".${live.fileName}.rollback-$id")
    }

    fun recover() {
        if (!journal.exists()) return
        val payload = parseObject(journal) ?: throw IOException("Import journal is unreadable; export data before recovery")
        val id = payload.stringOrEmpty("id")
        val state = payload.stringOrEmpty("state")
        if (!ID_PATTERN.matches(id)) throw IOException("Import journal has an invalid operation ID")
        val transaction = transactionFor(id)
        if (state !in RECOVERABLE_STATES && state != COMMITTING_STATE) return
        val components = readComponents(payload)
        if (state == COMMITTING_STATE) {
            cleanup(transaction, components)
            writeJournal(id, "complete", components)
        } else {
            restore(transaction, components)
            writeJournal(id, "recovered", components)
            safeDelete(transaction.operationRoot, operationsRoot)
        }
    }

    fun begin(id: String): Transaction {
        require(ID_PATTERN.matches(id)) { "Invalid import operation ID" }
        val transaction = transactionFor(id)
        val metadata =
            parseObject(transaction.operationRoot.resolve("operation.json"))
                ?: throw IOException("Prepared import metadata is missing or invalid")
        val candidateRoot = transaction.operationRoot.resolve("candidate")
        if (metadata.stringOrEmpty("id") != id || metadata.stringOrEmpty("kind") != "import" ||
            metadata.stringOrEmpty("status") != "ready" || !candidateRoot.resolve("data").isDirectory(NOFOLLOW_LINKS)
        ) {
            throw IOException("Prepared import is missing or expired")
        }
        if (!liveData.isDirectory(NOFOLLOW_LINKS)) throw IOException("Current user data is missing")

        val components =
            destinations().mapNotNull { (name, live) ->
                if (!candidateRoot.resolve(name).isDirectory(NOFOLLOW_LINKS)) return@mapNotNull null
                if (live.exists(NOFOLLOW_LINKS) && !live.isDirectory(NOFOLLOW_LINKS)) {
                    throw IOException("Import destination is not a directory: $name")
                }
                Component(name, live, live.exists(NOFOLLOW_LINKS))
            }
        if (components.any { it.rollback(id).exists(NOFOLLOW_LINKS) }) {
            throw IOException("This import still has a rollback copy; recover it first")
        }
        writeJournal(id, "swapping", components)
        try {
            components.forEach { component ->
                component.live.parent.createDirectories()
                if (component.hadOriginal) atomicMove(component.live, component.rollback(id))
                atomicMove(candidateRoot.resolve(component.name), component.live)
            }
            writeJournal(id, "awaiting_health", components)
            return transaction
        } catch (error: Exception) {
            var restored = false
            try {
                restore(transaction, components)
                restored = true
                writeJournal(id, "rolled_back", components)
            } catch (recoveryError: Exception) {
                error.addSuppressed(recoveryError)
                // Keep the durable swapping journal and every remaining copy.
            }
            throw ActivationException(restored, error)
        }
    }

    fun commit(transaction: Transaction) {
        requireKnownTransaction(transaction)
        val components = componentsFor(transaction)
        writeJournal(transaction.id, COMMITTING_STATE, components)
        // Once health has been committed, cleanup errors must not roll back
        // authoritative data. The committing journal retries cleanup on start.
        runCatching {
            cleanup(transaction, components)
            writeJournal(transaction.id, "complete", components)
        }
    }

    fun rollback(transaction: Transaction) {
        requireKnownTransaction(transaction)
        val payload = parseObject(journal) ?: throw IOException("Import journal is unreadable")
        if (payload.stringOrEmpty("state") in setOf("rolled_back", "recovered")) return
        check(payload.stringOrEmpty("state") in RECOVERABLE_STATES) { "Import is already committed" }
        val components = componentsFor(transaction)
        restore(transaction, components)
        writeJournal(transaction.id, "rolled_back", components)
        safeDelete(transaction.operationRoot, operationsRoot)
    }

    private fun restore(
        transaction: Transaction,
        components: List<Component>,
    ) {
        components.asReversed().forEach { component ->
            val rollback = component.rollback(transaction.id)
            val candidate = transaction.operationRoot.resolve("candidate/${component.name}")
            val hasRollback = rollback.isDirectory(NOFOLLOW_LINKS)
            val removeNew = !component.hadOriginal && !candidate.exists(NOFOLLOW_LINKS)
            if (hasRollback || removeNew) {
                if (component.live.exists(NOFOLLOW_LINKS)) {
                    transaction.operationRoot.createDirectories()
                    val failed = transaction.operationRoot.resolve("failed-${component.name}")
                    safeDelete(failed, transaction.operationRoot)
                    atomicMove(component.live, failed)
                }
                if (hasRollback) atomicMove(rollback, component.live)
            } else if (component.hadOriginal && !component.live.isDirectory(NOFOLLOW_LINKS)) {
                throw IOException("Original ${component.name} is unavailable; recovery remains pending")
            }
        }
    }

    private fun cleanup(
        transaction: Transaction,
        components: List<Component>,
    ) {
        components.forEach { safeDelete(it.rollback(transaction.id), it.live.parent) }
        safeDelete(transaction.operationRoot, operationsRoot)
    }

    private fun destinations(): Map<String, Path> =
        buildMap {
            put("data", liveData)
            configRoot?.let { put("config", it) }
            extensionsRoot?.let { put("global-extensions", it) }
        }

    private fun componentsFor(transaction: Transaction): List<Component> {
        val payload = parseObject(journal) ?: throw IOException("Import journal is unreadable")
        check(payload.stringOrEmpty("id") == transaction.id) { "Import journal belongs to another operation" }
        return readComponents(payload)
    }

    private fun readComponents(payload: JsonObject): List<Component> {
        // Journals written by 0.26 only exchanged data/.
        val originals =
            payload["originals"]?.jsonObject
                ?: return listOf(Component("data", liveData, true))
        val targets = destinations()
        return originals.map { (name, value) ->
            Component(name, targets[name] ?: throw IOException("Unknown import component: $name"), value.jsonPrimitive.boolean)
        }
    }

    private fun transactionFor(id: String): Transaction =
        Transaction(
            id,
            operationsRoot.resolve(id),
            liveData.resolveSibling(".${liveData.fileName}.rollback-$id"),
        )

    private fun requireKnownTransaction(transaction: Transaction) {
        require(ID_PATTERN.matches(transaction.id) && transaction == transactionFor(transaction.id)) {
            "Transaction does not belong to this manager"
        }
    }

    private fun parseObject(path: Path) = runCatching { Json.parseToJsonElement(path.readText()).jsonObject }.getOrNull()

    private fun writeJournal(
        id: String,
        state: String,
        components: List<Component>,
    ) {
        journalWriter(
            journal,
            buildJsonObject {
                put("id", id)
                put("state", state)
                put("originals", buildJsonObject { components.forEach { put(it.name, it.hadOriginal) } })
            }.toString(),
        )
    }

    private fun atomicMove(
        source: Path,
        destination: Path,
    ) = RuntimeBundleInstaller.atomicMove(source, destination)

    private fun safeDelete(
        target: Path,
        expectedParent: Path,
    ) = RuntimeBundleInstaller.safeDeleteTree(target, expectedParent)

    companion object {
        private val ID_PATTERN = Regex("[0-9a-fA-F-]{36}")
        private val RECOVERABLE_STATES = setOf("swapping", "awaiting_health", "begin_failed")
        private const val COMMITTING_STATE = "committing"
    }
}

private fun JsonObject.stringOrEmpty(key: String): String = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()
