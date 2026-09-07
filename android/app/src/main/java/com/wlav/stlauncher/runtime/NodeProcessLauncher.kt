package com.wlav.stlauncher.runtime

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class NodeLaunchRequest(
    val runtimeDirectory: File,
    val configFile: Path,
    val dataRoot: Path,
    val operationsRoot: Path,
    val port: Int,
    val instanceId: String,
    val attempt: Int,
)

fun interface NodeProcessLauncher {
    suspend fun launch(request: NodeLaunchRequest): ManagedRuntime
}

class LinkerNodeProcessLauncher(
    context: Context,
    private val scope: CoroutineScope,
    private val logStore: RotatingLogStore,
) : NodeProcessLauncher {
    private val appContext = context.applicationContext

    override suspend fun launch(request: NodeLaunchRequest): ManagedRuntime {
        var launchedRuntime: AndroidManagedRuntime? = null
        try {
            return withContext(Dispatchers.IO) {
                if (!Build.SUPPORTED_ABIS.contains("arm64-v8a")) {
                    throw NonRetryableRuntimeException(
                        code = "UNSUPPORTED_ABI",
                        message = "ST Launcher requires an arm64-v8a device",
                    )
                }

                val nodeExecutable =
                    File(appContext.applicationInfo.nativeLibraryDir, "libstnode.so")
                if (!nodeExecutable.isFile) {
                    throw NonRetryableRuntimeException(
                        code = "NODE_RUNTIME_MISSING",
                        message = "The packaged Node.js runtime is missing",
                    )
                }

                val serverFile = File(request.runtimeDirectory, "app/server.js")
                val libraryDirectory = File(request.runtimeDirectory, "lib")
                if (!serverFile.isFile || !libraryDirectory.isDirectory) {
                    throw NonRetryableRuntimeException(
                        code = "RUNTIME_INCOMPLETE",
                        message = "The installed SillyTavern runtime is incomplete",
                    )
                }

                val arguments =
                    listOf(
                        LINKER_64,
                        nodeExecutable.absolutePath,
                        serverFile.absolutePath,
                        "--configPath",
                        request.configFile.toAbsolutePath().toString(),
                        "--dataRoot",
                        request.dataRoot.toAbsolutePath().toString(),
                        "--port",
                        request.port.toString(),
                        "--listen",
                        "false",
                        "--enableIPv4",
                        "true",
                        "--enableIPv6",
                        "false",
                        "--browserLaunchEnabled",
                        "false",
                    )
                val builder =
                    ProcessBuilder(arguments)
                        .directory(File(request.runtimeDirectory, "app"))
                        .redirectErrorStream(true)
                builder.environment().apply {
                    this["LD_LIBRARY_PATH"] = libraryDirectory.absolutePath
                    this["OPENSSL_CONF"] = ""
                    this["HOME"] = appContext.filesDir.absolutePath
                    this["PATH"] = "/system/bin"
                    this["NODE_ENV"] = "production"
                    this["SILLYTAVERN_GIT_BACKEND"] = "builtin"
                    this["ST_LAUNCHER_CONFIG_ROOT"] = request.configFile.parent.toString()
                    this["ST_LAUNCHER_GLOBAL_EXTENSIONS"] = File(appContext.filesDir, "global-extensions").absolutePath
                    this["ST_LAUNCHER_DEFAULT_EXTENSIONS"] =
                        File(request.runtimeDirectory, "app/public/scripts/extensions/third-party").absolutePath
                    this["SILLYTAVERN_ENABLESERVERPLUGINS"] = "true"
                    this["SILLYTAVERN_ENABLESERVERPLUGINSAUTOUPDATE"] = "false"
                    this["ST_LAUNCHER_INSTANCE_ID"] = request.instanceId
                    this["ST_LAUNCHER_OPERATIONS_ROOT"] =
                        request.operationsRoot.toAbsolutePath().toString()
                }

                Log.i(
                    TAG,
                    "Launching runtime attempt ${request.attempt} on port ${request.port}",
                )
                val process =
                    try {
                        builder.start()
                    } catch (error: IOException) {
                        throw RuntimeExceptionWithCode(
                            code = "NODE_LAUNCH_FAILED",
                            message = "Unable to launch Node.js",
                            cause = error,
                        )
                    }
                AndroidManagedRuntime(
                    process = process,
                    port = request.port,
                    instanceId = request.instanceId,
                    scope = scope,
                    logStore = logStore,
                ).also { launchedRuntime = it }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                launchedRuntime?.terminate(0)
            }
            throw cancelled
        }
    }

    companion object {
        private const val TAG = "NodeProcessLauncher"
        private const val LINKER_64 = "/system/bin/linker64"
    }
}

private class AndroidManagedRuntime(
    private val process: Process,
    override val port: Int,
    override val instanceId: String,
    scope: CoroutineScope,
    logStore: RotatingLogStore,
) : ManagedRuntime {
    private val terminated = AtomicBoolean(false)
    private val processId: Int? =
        runCatching {
            val method =
                process.javaClass.methods.firstOrNull {
                    it.name == "pid" && it.parameterCount == 0
                } ?: return@runCatching null
            (method.invoke(process) as Number).toInt()
        }.getOrNull()
    private val logSession = logStore.openSession()
    private val outputJob: Job =
        scope.launch(Dispatchers.IO) {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        Log.i("SillyTavern", line)
                        logSession.append(line)
                    }
                }
            } catch (error: IOException) {
                Log.d("SillyTavern", "Runtime output closed: ${error.message}")
            } finally {
                logSession.close()
            }
        }

    override val isAlive: Boolean
        get() = process.isAlive

    override suspend fun awaitExit(): Int =
        withContext(Dispatchers.IO) {
            val code = process.waitFor()
            drainOutput()
            code
        }

    private suspend fun drainOutput() {
        if (withTimeoutOrNull(2_000) {
                outputJob.join()
                true
            } != true
        ) {
            runCatching { process.inputStream.close() }
            outputJob.cancel()
            logSession.close()
        }
    }

    override suspend fun terminate(graceMillis: Long) =
        withContext(Dispatchers.IO) {
            if (!terminated.compareAndSet(false, true)) {
                return@withContext
            }
            if (!process.isAlive) {
                drainOutput()
                return@withContext
            }

            val descendantsBeforeStop = processId?.let(ProcessTree::descendantsOf).orEmpty()
            processId
                ?.let { pid -> runCatching { Os.kill(pid, OsConstants.SIGTERM) } }
                ?.onFailure { process.destroy() }
                ?: process.destroy()
            if (!process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) {
                val descendants =
                    (
                        descendantsBeforeStop +
                            processId?.let(ProcessTree::descendantsOf).orEmpty()
                    ).distinct()
                descendants.reversed().forEach { pid ->
                    runCatching { Os.kill(pid, OsConstants.SIGKILL) }
                }
                processId
                    ?.let { pid -> runCatching { Os.kill(pid, OsConstants.SIGKILL) } }
                    ?.onFailure { process.destroyForcibly() }
                    ?: process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            drainOutput()
        }
}

private object ProcessTree {
    fun descendantsOf(rootPid: Int): List<Int> {
        val result = mutableListOf<Int>()
        val pending = ArrayDeque<Int>()
        pending.add(rootPid)
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            val childrenFile = File("/proc/$parent/task/$parent/children")
            val children =
                runCatching {
                    childrenFile
                        .readText()
                        .trim()
                        .split(Regex("\\s+"))
                        .mapNotNull(String::toIntOrNull)
                }.getOrDefault(emptyList())
            result += children
            children.forEach(pending::addLast)
        }
        return result
    }
}
