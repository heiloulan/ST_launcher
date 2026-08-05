package com.wlav.stlauncher.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ImportTransactionManager(
    private val operationsRoot: Path,
    private val liveData: Path,
    private val journal: Path,
) {
    data class Transaction(
        val id: String,
        val operationRoot: Path,
        val rollbackData: Path,
    )

    fun recover() {
        if (!journal.exists()) return
        val payload = parseObject(journal) ?: return
        val id = payload.stringOrEmpty("id")
        val state = payload.stringOrEmpty("state")
        if (!ID_PATTERN.matches(id)) return

        val transaction = transactionFor(id)
        if (state == COMMITTING_STATE) {
            safeDelete(transaction.rollbackData, liveData.parent)
            safeDelete(transaction.operationRoot, operationsRoot)
            writeJournal(id, "complete")
            return
        }
        if (state !in RECOVERABLE_STATES) return
        if (transaction.rollbackData.isDirectory()) {
            if (liveData.exists()) {
                transaction.operationRoot.createDirectories()
                val failedData = transaction.operationRoot.resolve("failed-data")
                safeDelete(failedData, transaction.operationRoot)
                atomicMove(liveData, failedData)
            }
            atomicMove(transaction.rollbackData, liveData)
            safeDelete(transaction.operationRoot, operationsRoot)
        }
        writeJournal(id, "recovered")
    }

    fun begin(id: String): Transaction {
        require(ID_PATTERN.matches(id)) { "Invalid import operation ID" }
        val transaction = transactionFor(id)
        val metadata = transaction.operationRoot.resolve("operation.json")
        val candidateData = transaction.operationRoot.resolve("candidate/data")
        if (!metadata.toFile().isFile || !candidateData.isDirectory()) {
            throw IOException("Prepared import is missing or expired")
        }
        val operation =
            parseObject(metadata)
                ?: throw IOException("Prepared import metadata is invalid")
        if (operation.stringOrEmpty("id") != id ||
            operation.stringOrEmpty("kind") != "import" ||
            operation.stringOrEmpty("status") != "ready"
        ) {
            throw IOException("Prepared import metadata is invalid")
        }
        if (!liveData.isDirectory()) {
            throw IOException("Current user data is missing")
        }

        journal.parent.createDirectories()
        safeDelete(transaction.rollbackData, liveData.parent)
        writeJournal(id, "swapping")

        try {
            atomicMove(liveData, transaction.rollbackData)
            atomicMove(candidateData, liveData)
            writeJournal(id, "awaiting_health")
            return transaction
        } catch (error: Throwable) {
            if (!liveData.exists() && transaction.rollbackData.exists()) {
                atomicMove(transaction.rollbackData, liveData)
            }
            writeJournal(id, "begin_failed")
            throw IOException("Unable to activate imported data", error)
        }
    }

    fun commit(transaction: Transaction) {
        requireKnownTransaction(transaction)
        writeJournal(transaction.id, COMMITTING_STATE)
        // A healthy import is authoritative after this durable marker.
        // Cleanup failures are retried by recover() and must not trigger a
        // destructive rollback of already-validated data.
        runCatching { safeDelete(transaction.rollbackData, liveData.parent) }
        runCatching { safeDelete(transaction.operationRoot, operationsRoot) }
        runCatching { writeJournal(transaction.id, "complete") }
    }

    fun rollback(transaction: Transaction) {
        requireKnownTransaction(transaction)
        if (transaction.rollbackData.isDirectory()) {
            if (liveData.exists()) {
                transaction.operationRoot.createDirectories()
                val failedData = transaction.operationRoot.resolve("failed-data")
                safeDelete(failedData, transaction.operationRoot)
                atomicMove(liveData, failedData)
            }
            atomicMove(transaction.rollbackData, liveData)
        }
        safeDelete(transaction.operationRoot, operationsRoot)
        writeJournal(transaction.id, "rolled_back")
    }

    private fun transactionFor(id: String): Transaction =
        Transaction(
            id = id,
            operationRoot = operationsRoot.resolve(id),
            rollbackData = liveData.parent.resolve(".${liveData.fileName}.rollback-$id"),
        )

    private fun requireKnownTransaction(transaction: Transaction) {
        require(ID_PATTERN.matches(transaction.id) && transaction == transactionFor(transaction.id)) {
            "Transaction does not belong to this manager"
        }
    }

    private fun parseObject(path: Path) =
        runCatching {
            Json.parseToJsonElement(path.readText()).jsonObject
        }.getOrNull()

    private fun writeJournal(
        id: String,
        state: String,
    ) {
        val contents =
            buildJsonObject {
                put("id", id)
                put("state", state)
            }.toString()
        val temporary = journal.resolveSibling("${journal.fileName}.part")
        temporary.parent.createDirectories()
        temporary.writeText(contents)
        atomicMove(temporary, journal, replace = true)
    }

    private fun atomicMove(
        source: Path,
        destination: Path,
        replace: Boolean = false,
    ) {
        val options =
            buildList {
                add(StandardCopyOption.ATOMIC_MOVE)
                if (replace) add(StandardCopyOption.REPLACE_EXISTING)
            }.toTypedArray()
        try {
            Files.move(source, destination, *options)
        } catch (_: AtomicMoveNotSupportedException) {
            val fallback =
                if (replace) {
                    arrayOf(StandardCopyOption.REPLACE_EXISTING)
                } else {
                    emptyArray()
                }
            Files.move(source, destination, *fallback)
        }
    }

    private fun safeDelete(
        target: Path,
        expectedParent: Path,
    ) {
        val normalizedTarget = target.toAbsolutePath().normalize()
        val normalizedParent = expectedParent.toAbsolutePath().normalize()
        require(normalizedTarget.parent == normalizedParent) {
            "Refusing to delete outside transaction root"
        }
        if (!normalizedTarget.exists()) return
        Files.walk(normalizedTarget).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    companion object {
        private val ID_PATTERN = Regex("[0-9a-fA-F-]{36}")
        private val RECOVERABLE_STATES = setOf("swapping", "awaiting_health")
        private const val COMMITTING_STATE = "committing"
    }
}

private fun kotlinx.serialization.json.JsonObject.stringOrEmpty(key: String): String =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull }
        .getOrNull()
        .orEmpty()
