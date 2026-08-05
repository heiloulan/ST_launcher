package com.wlav.stlauncher.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Serializes runtime lifecycle commands and owns the retry policy.
 *
 * Android-specific process, power and filesystem behavior stays behind
 * [RuntimeBackend], which makes all state transitions deterministic in JVM
 * tests.
 */
interface RuntimeSupervisor {
    val state: StateFlow<RuntimeState>

    suspend fun start()

    suspend fun retry()

    suspend fun stop()
}

class DefaultRuntimeSupervisor(
    private val backend: RuntimeBackend,
    private val scope: CoroutineScope,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) : RuntimeSupervisor {
    private val commandMutex = Mutex()
    private val _state = MutableStateFlow<RuntimeState>(RuntimeState.Idle)

    @Volatile
    private var lifecycleJob: Job? = null

    @Volatile
    private var activeRuntime: ManagedRuntime? = null

    @Volatile
    private var stopRequested = false

    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    override suspend fun start() = commandMutex.withLock { startLocked() }

    override suspend fun retry() {
        commandMutex.withLock {
            if (_state.value !is RuntimeState.Failed) {
                return
            }
            stopLocked()
            startLocked()
        }
    }

    override suspend fun stop() = commandMutex.withLock { stopLocked() }

    private fun startLocked() {
        if (lifecycleJob?.isActive == true) {
            return
        }

        stopRequested = false
        lifecycleJob =
            scope.launch {
                supervise()
            }
    }

    private suspend fun stopLocked() {
        val job: Job?
        val runtime: ManagedRuntime?
        if (_state.value == RuntimeState.Idle && lifecycleJob?.isActive != true) {
            return
        }
        stopRequested = true
        _state.value = RuntimeState.Stopping
        job = lifecycleJob
        runtime = activeRuntime
        lifecycleJob = null
        activeRuntime = null
        job?.cancel()

        withContext(NonCancellable) {
            runtime?.terminate(retryPolicy.shutdownGraceMillis)
            job?.cancelAndJoin()
        }
        _state.value = RuntimeState.Idle
    }

    private suspend fun supervise() {
        try {
            _state.value = RuntimeState.Preparing(PreparationStage.RECOVERING)
            backend.prepare { stage, progress ->
                _state.value = RuntimeState.Preparing(stage, progress)
            }

            var lastFailure: Throwable? = null
            for (attempt in 1..retryPolicy.maximumAttempts) {
                if (!scope.isActive || stopRequested) {
                    return
                }

                _state.value = RuntimeState.Starting(attempt, retryPolicy.maximumAttempts)
                val runtime =
                    try {
                        backend.launch(attempt)
                    } catch (error: Throwable) {
                        if (error is NonRetryableRuntimeException) throw error
                        lastFailure = error
                        if (!waitBeforeRetry(attempt)) {
                            break
                        }
                        continue
                    }

                activeRuntime = runtime
                try {
                    val endpoint = backend.awaitReady(runtime, retryPolicy.startupTimeoutMillis)
                    _state.value = RuntimeState.Ready(endpoint)
                    val exitCode = runtime.awaitExit()
                    if (stopRequested) {
                        return
                    }
                    throw ProcessExitedException(exitCode)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (error is NonRetryableRuntimeException) throw error
                    lastFailure = error
                } finally {
                    withContext(NonCancellable) {
                        runtime.terminate(retryPolicy.shutdownGraceMillis)
                    }
                    if (activeRuntime === runtime) {
                        activeRuntime = null
                    }
                }

                if (!waitBeforeRetry(attempt)) {
                    break
                }
            }

            if (!stopRequested) {
                _state.value = RuntimeState.Failed(lastFailure.toRuntimeFailure())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!stopRequested) {
                _state.value = RuntimeState.Failed(error.toRuntimeFailure())
            }
        }
    }

    private suspend fun waitBeforeRetry(attempt: Int): Boolean {
        val delayMillis =
            retryPolicy.restartDelaysMillis.getOrNull(attempt - 1)
                ?: return false
        if (stopRequested) {
            return false
        }
        delay(delayMillis)
        return !stopRequested
    }

    private fun Throwable?.toRuntimeFailure(): RuntimeFailure {
        val error = this ?: IOException("Runtime exited without an error")
        return RuntimeFailure(
            code =
                when (error) {
                    is RuntimeExceptionWithCode -> error.code
                    is ProcessExitedException -> "PROCESS_EXITED"
                    else -> "RUNTIME_START_FAILED"
                },
            message = error.message ?: "SillyTavern runtime failed",
            retryable = error !is NonRetryableRuntimeException,
            detail = error.cause?.message,
        )
    }
}

open class RuntimeExceptionWithCode(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class NonRetryableRuntimeException(
    code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeExceptionWithCode(code, message, cause)

class ProcessExitedException(
    val exitCode: Int,
) : RuntimeExceptionWithCode(
        code = "PROCESS_EXITED",
        message = "Node.js exited unexpectedly with code $exitCode",
    )
