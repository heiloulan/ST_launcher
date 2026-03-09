package com.wlav.stlauncher

import android.app.*
import android.content.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.*
import java.net.ServerSocket
import java.util.zip.GZIPInputStream
import android.system.Os

class NodeService : Service() {

    companion object {
        const val TAG = "NodeService"
        const val CHANNEL_ID = "node_service_channel_v2"
        const val NOTIFICATION_ID = 1
        const val ACTION_PORT_READY = "com.wlav.stlauncher.PORT_READY"
        const val EXTRA_PORT = "port"
    }

    private var nodeProcess: java.lang.Process? = null
    private var port: Int = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("少女祈祷中…")
        startForeground(NOTIFICATION_ID, notification)

        Thread {
            try {
                // 1. Extract node bundle from assets to filesDir
                val bundleDir = extractNodeBundle()
                Log.i(TAG, "Node bundle extracted to: ${bundleDir.absolutePath}")

                // 2. Find an available port
                port = findAvailablePort()
                Log.i(TAG, "Using port: $port")

                // 3. Extract SillyTavern assets
                val stDir = extractSillyTavern()
                Log.i(TAG, "SillyTavern extracted to: ${stDir.absolutePath}")

                // 3b. Extract git bundle
                extractGitBundle()

                // 4. Start Node.js process via linker64 trick (Termux approach)
                //    Instead of executing node directly (blocked by W^X on Android 10+),
                //    we execute /system/bin/linker64 which then loads our node binary.
                //    The kernel sees linker64 (a trusted system binary) being executed.
                val nodeBin = File(bundleDir, "node")
                nodeBin.setExecutable(true, false)

                val linker = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty())
                    "/system/bin/linker64" else "/system/bin/linker"
                
                val serverJs = File(stDir, "server.js")
                Log.i(TAG, "Using linker: $linker")
                Log.i(TAG, "Node binary: ${nodeBin.absolutePath} (exists=${nodeBin.exists()})")

                val pb = ProcessBuilder(linker, nodeBin.absolutePath, serverJs.absolutePath)
                pb.directory(stDir)
                pb.redirectErrorStream(true)
                pb.environment()["LD_LIBRARY_PATH"] = bundleDir.absolutePath
                pb.environment()["OPENSSL_CONF"] = ""
                pb.environment()["HOME"] = filesDir.absolutePath
                pb.environment()["SILLYTAVERN_PORT"] = port.toString()
                pb.environment()["NODE_ENV"] = "production"
                // Add bundleDir to PATH so child_process can find ffmpeg, etc.
                val pathDirs = mutableListOf(bundleDir.absolutePath)
                pb.environment()["PATH"] = pathDirs.joinToString(":") + ":" + (pb.environment()["PATH"] ?: "/system/bin")

                nodeProcess = pb.start()
                Log.i(TAG, "Node process started, PID: ${nodeProcess?.toString()}")

                // 5. Read stdout and write to log file
                val logFile = File(filesDir, "server.log")
                logFile.writeText("") // Clear previous log
                val logWriter = logFile.bufferedWriter()
                val reader = BufferedReader(InputStreamReader(nodeProcess!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: ""
                    Log.i(TAG, "Node: $currentLine")
                    logWriter.appendLine(currentLine)
                    logWriter.flush()
                    if (currentLine.contains("is listening on") || currentLine.contains("listening on port") || currentLine.contains("SERVER_READY:")) {
                        // Write port to file for polling fallback
                        File(filesDir, "node_port").writeText(port.toString())

                        // Notify the Activity that the port is ready
                        val broadcastIntent = Intent(ACTION_PORT_READY)
                        broadcastIntent.setPackage(packageName)
                        broadcastIntent.putExtra(EXTRA_PORT, port)
                        sendBroadcast(broadcastIntent)

                        // Update notification
                        val updatedNotification = buildNotification("酒馆营业中 🍻 (端口 $port)")
                        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(NOTIFICATION_ID, updatedNotification)
                        break
                    }
                }

                // Continue reading logs in background
                Thread {
                    try {
                        while (reader.readLine().also { line = it } != null) {
                            Log.d(TAG, "Node: $line")
                            logWriter.appendLine(line)
                            logWriter.flush()
                        }
                    } catch (e: IOException) {
                        Log.i(TAG, "Node process stream closed")
                    } finally {
                        try { logWriter.close() } catch (_: Exception) {}
                    }
                }.start()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Node.js", e)
            }
        }.start()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopNode()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopNode()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun extractSillyTavern(): File {
        val stBaseDir = File(filesDir, "sillytavern")
        val stDir = File(stBaseDir, "SillyTavern")
        val versionFile = File(stBaseDir, ".version")
        val currentVersion = "v1.16.0-isogit10"

        if (stBaseDir.exists() && versionFile.exists() && versionFile.readText() == currentVersion) {
            Log.i(TAG, "SillyTavern bundle already extracted (version $currentVersion)")
            return stDir
        }

        stBaseDir.deleteRecursively()
        stBaseDir.mkdirs()

        val tarAsset = "sillytavern.tar"
        Log.i(TAG, "Extracting $tarAsset from assets")

        try {
            assets.open(tarAsset).use { input ->
                val tarFile = File(filesDir, tarAsset)
                FileOutputStream(tarFile).use { output ->
                    input.copyTo(output)
                }
                
                // Use OS tar command to extract
                Log.i(TAG, "Running tar to extract SillyTavern...")
                val pb = ProcessBuilder("tar", "-xf", tarFile.absolutePath, "-C", stBaseDir.absolutePath)
                val process = pb.start()
                process.waitFor()
                if (process.exitValue() != 0) {
                    Log.e(TAG, "tar command failed with exit code ${process.exitValue()}")
                } else {
                    Log.i(TAG, "tar command succeeded")
                }
                tarFile.delete()

                // Generate config.yaml
                val configYaml = """
                    listen: false
                    port: 8000
                    autorun: false
                    enableIPv6: false
                    enableIPv4: true
                    securityOverride: false
                """.trimIndent()
                val configDir = File(stDir, "config.yaml")
                configDir.writeText(configYaml)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract SillyTavern: ${e.message}", e)
        }

        versionFile.writeText(currentVersion)
        Log.i(TAG, "SillyTavern extraction complete.")
        return stDir
    }

    private fun extractGitBundle() {
        val gitDir = File(filesDir, "git_bundle")
        val versionFile = File(gitDir, ".version")
        val currentVersion = "v6"

        if (gitDir.exists() && versionFile.exists() && versionFile.readText() == currentVersion) {
            Log.i(TAG, "Git bundle already extracted")
            return
        }

        gitDir.deleteRecursively()

        try {
            // Copy plain .tar from assets (same proven method as SillyTavern extraction)
            val tarFile = File(filesDir, "git_bundle.tar")
            assets.open("git_bundle.tar").use { input ->
                FileOutputStream(tarFile).use { output ->
                    input.copyTo(output)
                }
            }

            Log.i(TAG, "Extracting git bundle tar (${tarFile.length()} bytes)...")
            val pb = ProcessBuilder("tar", "-xf", tarFile.absolutePath, "-C", filesDir.absolutePath)
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (process.exitValue() != 0) {
                Log.e(TAG, "git tar -xf failed (exit ${process.exitValue()}): $output")
            } else {
                Log.i(TAG, "git tar -xf succeeded")
            }
            tarFile.delete()

            // Set executable permissions using system chmod (File.setExecutable unreliable on Android 10+)
            val chmodPb = ProcessBuilder("chmod", "-R", "755", gitDir.absolutePath)
            chmodPb.redirectErrorStream(true)
            val chmodProc = chmodPb.start()
            chmodProc.inputStream.bufferedReader().readText()
            chmodProc.waitFor()
            Log.i(TAG, "chmod result: ${chmodProc.exitValue()}")

            val binDir = File(gitDir, "bin")
            Log.i(TAG, "Git bundle done. bin exists: ${binDir.exists()}, git exists: ${File(binDir, "git").exists()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract git bundle: ${e.message}", e)
        }

        gitDir.mkdirs()
        versionFile.writeText(currentVersion)
    }

    private fun stopNode() {
        try {
            nodeProcess?.destroy()
            nodeProcess?.waitFor()
            Log.i(TAG, "Node process stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Node", e)
        }
        nodeProcess = null
    }

    private fun extractNodeBundle(): File {
        val bundleDir = File(filesDir, "node_bundle")
        val versionFile = File(bundleDir, ".version")
        val currentVersion = "v24.13.0-git-patch" // Bumped to include android_git_patch.js

        // Skip extraction if already done for this version
        if (bundleDir.exists() && versionFile.exists() && versionFile.readText() == currentVersion) {
            Log.i(TAG, "Node bundle already extracted (version $currentVersion)")
            return bundleDir
        }

        bundleDir.deleteRecursively()
        bundleDir.mkdirs()

        val assetManager = assets
        val files = assetManager.list("node_bundle") ?: emptyArray()
        Log.i(TAG, "Extracting ${files.size} files from assets/node_bundle")

        for (fileName in files) {
            val outFile = File(bundleDir, fileName)
            assetManager.open("node_bundle/$fileName").use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            // Make node binary and ffmpeg executable; other files read-only
            if (fileName == "node" || fileName == "ffmpeg") {
                outFile.setExecutable(true, false)
            }
            outFile.setReadable(true, false)
        }

        // Create symlinks for versioned .so files
        createSymlinks(bundleDir)

        // Write version marker
        versionFile.writeText(currentVersion)

        Log.i(TAG, "Node bundle extraction complete")
        return bundleDir
    }

    private fun createSymlinks(bundleDir: File) {
        val symlinks = mapOf(
            "libz.so.1" to "libz.so.1.3.1",
            "libicudata.so.78" to "libicudata.so.78.2",
            "libicui18n.so.78" to "libicui18n.so.78.2",
            "libicuuc.so.78" to "libicuuc.so.78.2",
            "libsqlite3.so" to "libsqlite3.so.3.52.0"
        )
        for ((linkName, targetName) in symlinks) {
            val target = File(bundleDir, targetName)
            val link = File(bundleDir, linkName)
            if (target.exists() && !link.exists()) {
                try {
                    Os.symlink(target.absolutePath, link.absolutePath)
                    link.setReadOnly()
                    Log.d(TAG, "Symlink: $linkName -> $targetName")
                } catch (e: Exception) {
                    // Fallback: copy file instead of symlink
                    target.copyTo(link, overwrite = true)
                    link.setReadOnly()
                    Log.d(TAG, "Copy (symlink fallback): $linkName -> $targetName")
                }
            }
        }
    }

    private fun findAvailablePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SillyTavern 服务",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Node.js 服务运行状态"
            setSound(null, null) // No sound for this persistent notification
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SillyTavern")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
