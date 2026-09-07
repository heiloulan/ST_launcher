package com.wlav.stlauncher.runtime

import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

internal fun writeAtomicText(
    path: Path,
    contents: String,
) {
    path.parent.createDirectories()
    val temporary = path.resolveSibling("${path.fileName}.part")
    temporary.writeText(contents)
    FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
    try {
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
    }
}
