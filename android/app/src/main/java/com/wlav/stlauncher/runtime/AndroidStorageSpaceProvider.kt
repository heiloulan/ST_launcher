package com.wlav.stlauncher.runtime

import android.os.StatFs
import java.nio.file.Path

/**
 * Uses Android's stable filesystem statistics API rather than
 * Files.getFileStore(), which is unsupported by some Android NIO providers.
 */
class AndroidStorageSpaceProvider : StorageSpaceProvider {
    override fun query(path: Path): StorageSpace {
        val statistics = StatFs(path.toString())
        return StorageSpace(
            totalBytes = statistics.totalBytes,
            usableBytes = statistics.availableBytes,
        )
    }
}
