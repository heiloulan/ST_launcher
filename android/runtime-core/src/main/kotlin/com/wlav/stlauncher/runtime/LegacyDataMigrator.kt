package com.wlav.stlauncher.runtime

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

interface LegacyDataMigrator {
    fun migrate(
        legacyData: Path,
        legacyConfig: Path?,
        targetData: Path,
        targetConfig: Path,
        journal: Path,
        progress: (Int) -> Unit = {},
    ): MigrationResult

    fun recover(
        targetData: Path,
        journal: Path,
    )

    /**
     * Retires the verified legacy copy only after the caller has completed its
     * runtime health check. Returns true when a pending migration was committed.
     */
    fun commit(
        legacyData: Path,
        targetData: Path,
        journal: Path,
    ): Boolean
}

class JournaledLegacyDataMigrator(
    private val maximumCopyAttempts: Int = 3,
) : LegacyDataMigrator {
    init {
        require(maximumCopyAttempts > 0) { "At least one copy attempt is required" }
    }

    override fun migrate(
        legacyData: Path,
        legacyConfig: Path?,
        targetData: Path,
        targetConfig: Path,
        journal: Path,
        progress: (Int) -> Unit,
    ): MigrationResult {
        if (targetData.isDirectory()) {
            return MigrationResult.AlreadyMigrated
        }
        if (!legacyData.isDirectory()) {
            return MigrationResult.NoLegacyData
        }

        targetData.parent.createDirectories()
        targetConfig.parent.createDirectories()
        val staging = targetData.parent.resolve(".${targetData.name}.migrating-${UUID.randomUUID()}")
        val configStaging = targetConfig.resolveSibling(".${targetConfig.name}.migrating")
        RuntimeBundleInstaller.safeDeleteTree(staging, targetData.parent)
        Files.deleteIfExists(configStaging)
        Files.deleteIfExists(configStaging.resolveSibling(".${configStaging.name}.part"))
        staging.createDirectories()
        journal.parent.createDirectories()
        journal.writeText("copying:${staging.name}")

        try {
            val files = listRegularFiles(legacyData)
            listDirectories(legacyData).forEach { source ->
                staging.resolve(legacyData.relativize(source)).createDirectories()
            }
            files.forEachIndexed { index, source ->
                val relative = legacyData.relativize(source)
                val destination = staging.resolve(relative)
                destination.parent.createDirectories()
                stableCopy(source, destination)
                progress(((index + 1) * 100 / files.size.coerceAtLeast(1)).coerceIn(0, 100))
            }

            journal.writeText("verifying:${staging.name}")
            verifyTrees(legacyData, staging)

            if (!targetConfig.exists() &&
                legacyConfig != null &&
                Files.isRegularFile(legacyConfig, LinkOption.NOFOLLOW_LINKS)
            ) {
                stableCopy(legacyConfig, configStaging)
                RuntimeBundleInstaller.atomicMove(configStaging, targetConfig)
            }
            RuntimeBundleInstaller.atomicMove(staging, targetData)
            journal.writeText("complete")
            return MigrationResult.Migrated(files.size)
        } catch (error: Throwable) {
            RuntimeBundleInstaller.safeDeleteTree(staging, targetData.parent)
            Files.deleteIfExists(configStaging)
            Files.deleteIfExists(configStaging.resolveSibling(".${configStaging.name}.part"))
            journal.writeText("failed:${error::class.simpleName}")
            throw NonRetryableRuntimeException(
                code = "DATA_MIGRATION_FAILED",
                message = "Unable to migrate existing SillyTavern data safely",
                cause = error,
            )
        }
    }

    override fun recover(
        targetData: Path,
        journal: Path,
    ) {
        if (!journal.exists() || targetData.exists()) {
            return
        }
        val value = journal.readText()
        val stagingName = value.substringAfter(':', missingDelimiterValue = "")
        if (!stagingName.startsWith(".${targetData.name}.migrating-")) {
            return
        }
        RuntimeBundleInstaller.safeDeleteTree(
            targetData.parent.resolve(stagingName),
            targetData.parent,
        )
        journal.writeText("recovered")
    }

    override fun commit(
        legacyData: Path,
        targetData: Path,
        journal: Path,
    ): Boolean {
        if (!journal.exists()) {
            return false
        }
        val state = journal.readText()
        if (!Files.isDirectory(targetData, LinkOption.NOFOLLOW_LINKS)) {
            return false
        }
        val legacyExists = Files.exists(legacyData, LinkOption.NOFOLLOW_LINKS)
        if (state == "committed") {
            return !legacyExists
        }
        if (state != "complete" && state != "committing") {
            return false
        }
        if (legacyExists &&
            !Files.isDirectory(legacyData, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("Legacy data path is not a directory")
        }

        journal.writeText("committing")
        if (legacyExists) {
            RuntimeBundleInstaller.safeDeleteTree(legacyData, legacyData.parent)
        }
        journal.writeText("committed")
        return true
    }

    private fun stableCopy(
        source: Path,
        destination: Path,
    ) {
        repeat(maximumCopyAttempts) { attempt ->
            val before =
                Files.readAttributes(
                    source,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            if (!before.isRegularFile) {
                throw IOException("Only regular user-data files can be migrated: $source")
            }

            val temporary = destination.resolveSibling(".${destination.name}.part")
            Files.copy(
                source,
                temporary,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES,
                LinkOption.NOFOLLOW_LINKS,
            )
            val after =
                Files.readAttributes(
                    source,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )

            if (before.size() == after.size() &&
                before.lastModifiedTime() == after.lastModifiedTime() &&
                sha256(source) == sha256(temporary)
            ) {
                RuntimeBundleInstaller.atomicMove(temporary, destination)
                return
            }
            Files.deleteIfExists(temporary)
            if (attempt == maximumCopyAttempts - 1) {
                throw IOException("Source file kept changing during migration: $source")
            }
        }
    }

    private fun verifyTrees(
        source: Path,
        destination: Path,
    ) {
        val sourceFiles = listRegularFiles(source)
        val destinationFiles = listRegularFiles(destination)
        if (sourceFiles.size != destinationFiles.size) {
            throw IOException("Migrated data file count does not match")
        }
        sourceFiles.forEach { sourceFile ->
            val targetFile = destination.resolve(source.relativize(sourceFile))
            if (!Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS) ||
                sha256(sourceFile) != sha256(targetFile)
            ) {
                throw IOException("Migrated data verification failed: $sourceFile")
            }
        }
        val sourceDirectories =
            listDirectories(source).map(source::relativize).toSet()
        val destinationDirectories =
            listDirectories(destination).map(destination::relativize).toSet()
        if (sourceDirectories != destinationDirectories) {
            throw IOException("Migrated data directory list does not match")
        }
    }

    private fun listRegularFiles(root: Path): List<Path> =
        Files.walk(root).use { paths ->
            paths
                .filter { path ->
                    if (path == root) {
                        false
                    } else {
                        val attributes =
                            Files.readAttributes(
                                path,
                                BasicFileAttributes::class.java,
                                LinkOption.NOFOLLOW_LINKS,
                            )
                        if (attributes.isSymbolicLink || attributes.isOther) {
                            throw IOException("Unsupported entry in user data: $path")
                        }
                        attributes.isRegularFile
                    }
                }.sorted()
                .toList()
        }

    private fun listDirectories(root: Path): List<Path> =
        Files.walk(root).use { paths ->
            paths
                .filter { path ->
                    path != root &&
                        Files
                            .readAttributes(
                                path,
                                BasicFileAttributes::class.java,
                                LinkOption.NOFOLLOW_LINKS,
                            ).isDirectory
                }.sorted()
                .toList()
        }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

sealed interface MigrationResult {
    data object NoLegacyData : MigrationResult

    data object AlreadyMigrated : MigrationResult

    data class Migrated(
        val fileCount: Int,
    ) : MigrationResult
}
