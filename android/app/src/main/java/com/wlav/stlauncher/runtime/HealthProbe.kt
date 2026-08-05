package com.wlav.stlauncher.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

fun interface HealthProbe {
    suspend fun awaitReady(
        runtime: ManagedRuntime,
        timeoutMillis: Long,
    ): RuntimeEndpoint
}

class LauncherPluginHealthProbe : HealthProbe {
    override suspend fun awaitReady(
        runtime: ManagedRuntime,
        timeoutMillis: Long,
    ): RuntimeEndpoint =
        withContext(Dispatchers.IO) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            val healthUrl =
                URL(
                    "http://127.0.0.1:${runtime.port}/api/plugins/st-launcher/health",
                )

            while (System.nanoTime() < deadline) {
                if (!runtime.isAlive) {
                    throw RuntimeExceptionWithCode(
                        code = "PROCESS_EXITED_DURING_START",
                        message = "Node.js exited before SillyTavern became ready",
                    )
                }

                var connection: HttpURLConnection? = null
                try {
                    connection = healthUrl.openConnection() as HttpURLConnection
                    connection.connectTimeout = HEALTH_REQUEST_TIMEOUT_MILLIS
                    connection.readTimeout = HEALTH_REQUEST_TIMEOUT_MILLIS
                    connection.setRequestProperty("X-ST-Launcher-Instance", runtime.instanceId)
                    connection.setRequestProperty("Connection", "close")
                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val body =
                            connection.inputStream
                                .bufferedReader()
                                .use(BufferedReader::readText)
                        val response = JSONObject(body)
                        if (response.optString("instanceId") == runtime.instanceId &&
                            response.optBoolean("ready")
                        ) {
                            return@withContext RuntimeEndpoint(runtime.port, runtime.instanceId)
                        }
                    }
                } catch (_: IOException) {
                    // Connection failures are expected while Node is binding
                    // and SillyTavern is initializing.
                } finally {
                    connection?.disconnect()
                }
                delay(HEALTH_RETRY_MILLIS)
            }

            throw RuntimeExceptionWithCode(
                code = "START_TIMEOUT",
                message = "SillyTavern did not become ready within ${timeoutMillis / 1_000} seconds",
            )
        }

    companion object {
        private const val HEALTH_REQUEST_TIMEOUT_MILLIS = 1_000
        private const val HEALTH_RETRY_MILLIS = 300L
    }
}
