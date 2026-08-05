package com.wlav.stlauncher.runtime

import java.nio.file.Path

object SafeArchivePath {
    private const val MAX_PATH_LENGTH = 512

    fun requireSafe(rawPath: String): String {
        require(rawPath.isNotBlank()) { "Archive path is blank" }
        require(rawPath.length <= MAX_PATH_LENGTH) { "Archive path is too long" }
        require('\\' !in rawPath) { "Archive path contains a backslash" }
        require(!rawPath.startsWith('/')) { "Archive path is absolute" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(rawPath)) {
            "Archive path contains a drive prefix"
        }

        val normalized =
            Path
                .of(rawPath)
                .normalize()
                .toString()
                .replace('\\', '/')
        require(normalized != "." && normalized != "..") { "Archive path escapes its root" }
        require(!normalized.startsWith("../")) { "Archive path escapes its root" }
        require(normalized == rawPath.trimEnd('/')) {
            "Archive path is not normalized: $rawPath"
        }
        return normalized
    }

    fun requireUserDataPath(rawPath: String): String {
        val normalized = requireSafe(rawPath)
        require(normalized == "data" || normalized.startsWith("data/")) {
            "Backup entry is outside data/: $rawPath"
        }
        return normalized
    }
}
