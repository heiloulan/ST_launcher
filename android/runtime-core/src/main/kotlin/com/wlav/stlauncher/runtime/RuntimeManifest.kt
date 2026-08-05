package com.wlav.stlauncher.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class RuntimeManifest(
    val formatVersion: Int,
    val runtimeId: String,
    val sillyTavernVersion: String,
    val sillyTavernCommit: String,
    val nodeVersion: String,
    val bundleSha256: String,
    val uncompressedBytes: Long,
    val files: List<RuntimeFile>,
) {
    val versionHash: String
        get() = bundleSha256

    init {
        require(formatVersion == CURRENT_FORMAT_VERSION) {
            "Unsupported runtime manifest format: $formatVersion"
        }
        require(runtimeId.matches(Regex("[a-zA-Z0-9._-]{1,96}"))) {
            "Invalid runtime ID"
        }
        require(bundleSha256.matches(Regex("[a-f0-9]{64}"))) {
            "Invalid runtime bundle digest"
        }
        require(uncompressedBytes >= 0)
        require(files.isNotEmpty())
        require(files.map { it.path }.toSet().size == files.size) {
            "Runtime manifest contains duplicate paths"
        }
    }

    companion object {
        const val CURRENT_FORMAT_VERSION = 1

        fun parse(source: String): RuntimeManifest {
            val root = Json.parseToJsonElement(source).jsonObject
            return RuntimeManifest(
                formatVersion = root.requiredInt("formatVersion"),
                runtimeId = root.requiredString("runtimeId"),
                sillyTavernVersion = root.requiredString("sillyTavernVersion"),
                sillyTavernCommit = root.requiredString("sillyTavernCommit"),
                nodeVersion = root.requiredString("nodeVersion"),
                bundleSha256 = root.requiredString("bundleSha256"),
                uncompressedBytes = root.requiredLong("uncompressedBytes"),
                files =
                    root["files"]?.jsonArray?.map { element ->
                        val file = element.jsonObject
                        RuntimeFile(
                            path = file.requiredString("path"),
                            size = file.requiredLong("size"),
                            sha256 = file.requiredString("sha256"),
                        )
                    } ?: error("Runtime manifest is missing files"),
            )
        }
    }
}

data class RuntimeFile(
    val path: String,
    val size: Long,
    val sha256: String,
) {
    init {
        SafeArchivePath.requireSafe(path)
        require(size >= 0)
        require(sha256.matches(Regex("[a-f0-9]{64}"))) {
            "Invalid digest for $path"
        }
    }
}

private fun JsonObject.requiredString(key: String): String = this[key]?.jsonPrimitive?.content ?: error("Runtime manifest is missing $key")

private fun JsonObject.requiredInt(key: String): Int = requiredString(key).toIntOrNull() ?: error("Runtime manifest has invalid $key")

private fun JsonObject.requiredLong(key: String): Long = requiredString(key).toLongOrNull() ?: error("Runtime manifest has invalid $key")
