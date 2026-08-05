package com.wlav.stlauncher.runtime

interface RuntimeBackend {
    suspend fun prepare(reporter: PreparationReporter)

    suspend fun launch(attempt: Int): ManagedRuntime

    suspend fun awaitReady(
        runtime: ManagedRuntime,
        timeoutMillis: Long,
    ): RuntimeEndpoint
}

interface ManagedRuntime {
    val port: Int
    val instanceId: String
    val isAlive: Boolean

    suspend fun awaitExit(): Int

    /**
     * Implementations must be idempotent. They should request a graceful
     * shutdown first, then terminate descendants after [graceMillis].
     */
    suspend fun terminate(graceMillis: Long)
}

data class RetryPolicy(
    val restartDelaysMillis: List<Long> = listOf(1_000, 3_000, 10_000),
    val startupTimeoutMillis: Long = 60_000,
    val shutdownGraceMillis: Long = 10_000,
) {
    init {
        require(startupTimeoutMillis > 0)
        require(shutdownGraceMillis > 0)
        require(restartDelaysMillis.all { it >= 0 })
    }

    val maximumAttempts: Int = restartDelaysMillis.size + 1
}
