package com.wlav.stlauncher.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class RuntimeActivation(
    val runtimeId: String,
    val runtimePath: Path,
    val isCandidate: Boolean,
    val previousId: String? = null,
    val previousPath: Path? = null,
)

/**
 * Keeps the active runtime pointer unchanged until a new candidate passes its
 * instance-bound health check. A process death with a pending candidate
 * conservatively selects the previous active version on the next start.
 */
class RuntimeVersionRegistry {
    fun select(
        runtimeRoot: Path,
        candidateId: String,
    ): RuntimeActivation {
        require(ID_PATTERN.matches(candidateId)) { "Invalid candidate runtime ID" }
        val versionsRoot = runtimeRoot.resolve("versions").createDirectories()
        val candidatePath =
            validVersion(versionsRoot, candidateId)
                ?: throw IllegalStateException("Candidate runtime is not completely installed")
        val activeId = readId(runtimeRoot.resolve(ACTIVE_VERSION))
        val activePath = activeId?.let { validVersion(versionsRoot, it) }
        val pending = readPending(runtimeRoot.resolve(PENDING_VERSION))
        val failedId = readId(runtimeRoot.resolve(FAILED_VERSION))

        if (pending?.candidateId == candidateId &&
            activeId != null &&
            activeId == pending.previousId &&
            activePath != null
        ) {
            writeId(runtimeRoot.resolve(FAILED_VERSION), candidateId)
            Files.deleteIfExists(runtimeRoot.resolve(PENDING_VERSION))
            return RuntimeActivation(activeId, activePath, isCandidate = false)
        }

        if (failedId == candidateId && activeId != null && activePath != null) {
            return RuntimeActivation(activeId, activePath, isCandidate = false)
        }

        if (activeId == candidateId && activePath != null) {
            Files.deleteIfExists(runtimeRoot.resolve(PENDING_VERSION))
            Files.deleteIfExists(runtimeRoot.resolve(FAILED_VERSION))
            return RuntimeActivation(candidateId, candidatePath, isCandidate = false)
        }

        writePending(
            runtimeRoot.resolve(PENDING_VERSION),
            candidateId = candidateId,
            previousId = activeId?.takeIf { activePath != null },
        )
        return RuntimeActivation(
            runtimeId = candidateId,
            runtimePath = candidatePath,
            isCandidate = true,
            previousId = activeId?.takeIf { activePath != null },
            previousPath = activePath,
        )
    }

    fun commit(
        runtimeRoot: Path,
        activation: RuntimeActivation,
    ) {
        requireKnownActivation(runtimeRoot, activation)
        writeId(runtimeRoot.resolve(ACTIVE_VERSION), activation.runtimeId)
        activation.previousId?.let { writeId(runtimeRoot.resolve(PREVIOUS_VERSION), it) }
            ?: Files.deleteIfExists(runtimeRoot.resolve(PREVIOUS_VERSION))
        Files.deleteIfExists(runtimeRoot.resolve(PENDING_VERSION))
        Files.deleteIfExists(runtimeRoot.resolve(FAILED_VERSION))
        pruneVersions(
            runtimeRoot,
            keepIds = setOfNotNull(activation.runtimeId, activation.previousId),
        )
    }

    fun rollback(
        runtimeRoot: Path,
        activation: RuntimeActivation,
    ): RuntimeActivation? {
        requireKnownActivation(runtimeRoot, activation)
        if (!activation.isCandidate ||
            activation.previousId == null ||
            activation.previousPath == null ||
            !activation.previousPath.isDirectory()
        ) {
            return null
        }
        writeId(runtimeRoot.resolve(ACTIVE_VERSION), activation.previousId)
        writeId(runtimeRoot.resolve(FAILED_VERSION), activation.runtimeId)
        Files.deleteIfExists(runtimeRoot.resolve(PENDING_VERSION))
        return RuntimeActivation(
            runtimeId = activation.previousId,
            runtimePath = activation.previousPath,
            isCandidate = false,
        )
    }

    private fun requireKnownActivation(
        runtimeRoot: Path,
        activation: RuntimeActivation,
    ) {
        require(ID_PATTERN.matches(activation.runtimeId)) { "Invalid runtime activation" }
        val versionsRoot = runtimeRoot.resolve("versions").toAbsolutePath().normalize()
        val expected =
            versionsRoot
                .resolve(activation.runtimeId)
                .toAbsolutePath()
                .normalize()
        require(activation.runtimePath.toAbsolutePath().normalize() == expected) {
            "Runtime activation does not belong to this registry"
        }
        require(validVersion(versionsRoot, activation.runtimeId) != null) {
            "Runtime activation is no longer installed"
        }
    }

    private fun pruneVersions(
        runtimeRoot: Path,
        keepIds: Set<String>,
    ) {
        val versionsRoot = runtimeRoot.resolve("versions")
        if (!versionsRoot.isDirectory()) return
        Files.list(versionsRoot).use { versions ->
            versions
                .filter { version ->
                    val name = version.fileName.toString()
                    version.isDirectory() &&
                        !name.startsWith(".") &&
                        name !in keepIds
                }.forEach { version ->
                    RuntimeBundleInstaller.safeDeleteTree(version, versionsRoot)
                }
        }
    }

    private fun validVersion(
        versionsRoot: Path,
        id: String,
    ): Path? {
        if (!ID_PATTERN.matches(id)) return null
        val path = versionsRoot.resolve(id)
        return path.takeIf {
            it.isDirectory() && it.resolve(RuntimeBundleInstaller.COMPLETE_MARKER).toFile().isFile
        }
    }

    private fun readId(path: Path): String? {
        if (!path.toFile().isFile) return null
        return runCatching { path.readText().trim() }
            .getOrNull()
            ?.takeIf(ID_PATTERN::matches)
    }

    private fun readPending(path: Path): Pending? {
        if (!path.toFile().isFile) return null
        return runCatching {
            val root = Json.parseToJsonElement(path.readText()).jsonObject
            val candidateId = root["candidateId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val previousId = root["previousId"]?.jsonPrimitive?.contentOrNull
            if (!ID_PATTERN.matches(candidateId) ||
                (previousId != null && !ID_PATTERN.matches(previousId))
            ) {
                return@runCatching null
            }
            Pending(candidateId, previousId)
        }.getOrNull()
    }

    private fun writePending(
        path: Path,
        candidateId: String,
        previousId: String?,
    ) {
        val contents =
            buildJsonObject {
                put("candidateId", candidateId)
                if (previousId == null) {
                    put("previousId", kotlinx.serialization.json.JsonNull)
                } else {
                    put("previousId", previousId)
                }
            }.toString()
        atomicWrite(path, contents)
    }

    private fun writeId(
        path: Path,
        id: String,
    ) {
        require(ID_PATTERN.matches(id))
        atomicWrite(path, "$id\n")
    }

    private fun atomicWrite(
        path: Path,
        contents: String,
    ) {
        path.parent.createDirectories()
        val temporary = path.resolveSibling("${path.fileName}.part")
        temporary.writeText(contents)
        try {
            Files.move(
                temporary,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private data class Pending(
        val candidateId: String,
        val previousId: String?,
    )

    companion object {
        private val ID_PATTERN = Regex("[a-zA-Z0-9._-]{1,96}")
        private const val ACTIVE_VERSION = "active-version"
        private const val PREVIOUS_VERSION = "previous-version"
        private const val PENDING_VERSION = "pending-version.json"
        private const val FAILED_VERSION = "failed-version"
    }
}
