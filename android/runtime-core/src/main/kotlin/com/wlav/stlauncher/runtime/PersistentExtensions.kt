package com.wlav.stlauncher.runtime

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/** One-time copies out of versioned runtimes; existing user copies win. */
class PersistentExtensions(
    private val target: Path,
    private val migrationRoot: Path,
) {
    fun migrate(sources: List<Pair<String, Path>>) {
        target.createDirectories()
        migrationRoot.createDirectories()
        for ((id, source) in sources) {
            require(id.matches(Regex("[a-zA-Z0-9._-]{1,120}")))
            val marker = migrationRoot.resolve("$id.complete")
            if (marker.exists() || !source.isDirectory(NOFOLLOW_LINKS)) continue
            Files.list(source).use { extensions ->
                extensions
                    .filter { it.isDirectory(NOFOLLOW_LINKS) && it.fileName.toString() != "st-launcher" }
                    .forEach { extension ->
                        val destination = target.resolve(extension.fileName.toString())
                        if (!destination.exists(NOFOLLOW_LINKS)) {
                            val staging = migrationRoot.resolve("copying-${extension.fileName}")
                            RuntimeBundleInstaller.safeDeleteTree(staging, migrationRoot)
                            copyTree(extension, staging)
                            RuntimeBundleInstaller.atomicMove(staging, destination)
                        }
                    }
            }
            writeAtomicText(marker, "complete")
        }
    }

    private fun copyTree(
        source: Path,
        destination: Path,
    ) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val targetPath = destination.resolve(source.relativize(path))
                when {
                    Files.isDirectory(path, NOFOLLOW_LINKS) -> targetPath.createDirectories()
                    Files.isRegularFile(path, NOFOLLOW_LINKS) -> Files.copy(path, targetPath, COPY_ATTRIBUTES, NOFOLLOW_LINKS)
                    else -> throw IOException("Extension contains an unsupported entry: $path")
                }
            }
        }
    }
}
