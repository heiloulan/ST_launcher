package com.wlav.stlauncher.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeSupervisorTest {
    @Test
    fun `repeated start launches only one runtime`() =
        runTest {
            val backend = FakeBackend()
            val supervisor = DefaultRuntimeSupervisor(backend, this)

            supervisor.start()
            supervisor.start()
            advanceUntilIdle()

            assertEquals(1, backend.launchCount)
            assertInstanceOf(RuntimeState.Ready::class.java, supervisor.state.value)
            supervisor.stop()
        }

    @Test
    fun `unexpected exit is retried with the configured policy`() =
        runTest {
            val backend = FakeBackend(exitCodes = ArrayDeque(listOf(9, 7)))
            val supervisor =
                DefaultRuntimeSupervisor(
                    backend = backend,
                    scope = this,
                    retryPolicy =
                        RetryPolicy(
                            restartDelaysMillis = listOf(1, 3, 10),
                            startupTimeoutMillis = 1_000,
                            shutdownGraceMillis = 100,
                        ),
                )

            supervisor.start()
            advanceUntilIdle()

            assertEquals(3, backend.launchCount)
            assertInstanceOf(RuntimeState.Ready::class.java, supervisor.state.value)
            supervisor.stop()
        }

    @Test
    fun `exhausted retries produce a typed failure`() =
        runTest {
            val backend = FakeBackend(failLaunch = true)
            val supervisor =
                DefaultRuntimeSupervisor(
                    backend = backend,
                    scope = this,
                    retryPolicy =
                        RetryPolicy(
                            restartDelaysMillis = listOf(1, 3),
                            startupTimeoutMillis = 1_000,
                            shutdownGraceMillis = 100,
                        ),
                )

            supervisor.start()
            advanceUntilIdle()

            assertEquals(3, backend.launchCount)
            val failed = assertInstanceOf(RuntimeState.Failed::class.java, supervisor.state.value)
            assertEquals("RUNTIME_START_FAILED", failed.failure.code)
        }

    @Test
    fun `idle stop and premature retry are no-ops`() =
        runTest {
            val backend = FakeBackend()
            val supervisor = DefaultRuntimeSupervisor(backend, this)

            supervisor.stop()
            supervisor.retry()
            advanceUntilIdle()

            assertEquals(RuntimeState.Idle, supervisor.state.value)
            assertEquals(0, backend.launchCount)
        }

    @Test
    fun `non retryable launch failure stops immediately`() =
        runTest {
            val backend =
                FakeBackend(
                    launchErrors =
                        ArrayDeque(
                            listOf(
                                NonRetryableRuntimeException(
                                    "UNSUPPORTED",
                                    "unsupported runtime",
                                    IOException("root cause"),
                                ),
                            ),
                        ),
                )
            val supervisor = DefaultRuntimeSupervisor(backend, this, fastPolicy())

            supervisor.start()
            advanceUntilIdle()

            assertEquals(1, backend.launchCount)
            val failed = assertInstanceOf(RuntimeState.Failed::class.java, supervisor.state.value)
            assertEquals("UNSUPPORTED", failed.failure.code)
            assertEquals("root cause", failed.failure.detail)
            assertFalse(failed.failure.retryable)
        }

    @Test
    fun `typed readiness failures are retried and preserved`() =
        runTest {
            val backend =
                FakeBackend(
                    readyErrors =
                        ArrayDeque(
                            List(3) {
                                RuntimeExceptionWithCode("PORT_CONFLICT", "port unavailable")
                            },
                        ),
                )
            val supervisor = DefaultRuntimeSupervisor(backend, this, fastPolicy())

            supervisor.start()
            advanceUntilIdle()

            assertEquals(3, backend.launchCount)
            val failed = assertInstanceOf(RuntimeState.Failed::class.java, supervisor.state.value)
            assertEquals("PORT_CONFLICT", failed.failure.code)
            assertTrue(failed.failure.retryable)
        }

    @Test
    fun `repeated process exits exhaust retry policy`() =
        runTest {
            val backend = FakeBackend(exitCodes = ArrayDeque(listOf(1, 2, 3)))
            val supervisor = DefaultRuntimeSupervisor(backend, this, fastPolicy())

            supervisor.start()
            advanceUntilIdle()

            val failed = assertInstanceOf(RuntimeState.Failed::class.java, supervisor.state.value)
            assertEquals("PROCESS_EXITED", failed.failure.code)
            assertTrue(failed.failure.message.contains("3"))
        }

    @Test
    fun `prepare failures become terminal failures without launching`() =
        runTest {
            val backend = FakeBackend(prepareError = IOException())
            val supervisor = DefaultRuntimeSupervisor(backend, this, fastPolicy())

            supervisor.start()
            advanceUntilIdle()

            val failed = assertInstanceOf(RuntimeState.Failed::class.java, supervisor.state.value)
            assertEquals("RUNTIME_START_FAILED", failed.failure.code)
            assertEquals("SillyTavern runtime failed", failed.failure.message)
            assertEquals(0, backend.launchCount)
        }

    @Test
    fun `stop cancels preparation and leaves the supervisor idle`() =
        runTest {
            val prepareGate = CompletableDeferred<Unit>()
            val backend = FakeBackend(prepareGate = prepareGate)
            val supervisor = DefaultRuntimeSupervisor(backend, this, fastPolicy())

            supervisor.start()
            runCurrent()
            assertInstanceOf(RuntimeState.Preparing::class.java, supervisor.state.value)

            supervisor.stop()
            advanceUntilIdle()

            assertEquals(RuntimeState.Idle, supervisor.state.value)
            assertEquals(0, backend.launchCount)
        }

    @Test
    fun `stop during restart backoff prevents another launch`() =
        runTest {
            val backend =
                FakeBackend(
                    launchErrors = ArrayDeque(listOf(IOException("first launch failed"))),
                )
            val supervisor =
                DefaultRuntimeSupervisor(
                    backend,
                    this,
                    RetryPolicy(
                        restartDelaysMillis = listOf(10_000),
                        startupTimeoutMillis = 1_000,
                        shutdownGraceMillis = 100,
                    ),
                )

            supervisor.start()
            runCurrent()
            assertEquals(1, backend.launchCount)
            supervisor.stop()
            advanceUntilIdle()

            assertEquals(RuntimeState.Idle, supervisor.state.value)
            assertEquals(1, backend.launchCount)
        }

    @Test
    fun `start waits for an in-flight stop to release the runtime`() =
        runTest {
            val terminateStarted = CompletableDeferred<Unit>()
            val allowTerminate = CompletableDeferred<Unit>()
            var launchCount = 0
            val backend =
                object : RuntimeBackend {
                    override suspend fun prepare(reporter: PreparationReporter) = Unit

                    override suspend fun launch(attempt: Int): ManagedRuntime {
                        launchCount++
                        val blockTermination = launchCount == 1
                        return object : ManagedRuntime {
                            private val exit = CompletableDeferred<Int>()
                            override val port = 8_000
                            override val instanceId = "instance-$launchCount"
                            override val isAlive: Boolean
                                get() = !exit.isCompleted

                            override suspend fun awaitExit(): Int = exit.await()

                            override suspend fun terminate(graceMillis: Long) {
                                if (blockTermination) {
                                    terminateStarted.complete(Unit)
                                    allowTerminate.await()
                                }
                                exit.complete(0)
                            }
                        }
                    }

                    override suspend fun awaitReady(
                        runtime: ManagedRuntime,
                        timeoutMillis: Long,
                    ) = RuntimeEndpoint(runtime.port, runtime.instanceId)
                }
            val supervisor = DefaultRuntimeSupervisor(backend, this, fastPolicy())
            supervisor.start()
            runCurrent()
            assertInstanceOf(RuntimeState.Ready::class.java, supervisor.state.value)

            val stopping = async { supervisor.stop() }
            terminateStarted.await()
            val restarting = async { supervisor.start() }
            runCurrent()

            assertEquals(1, launchCount)
            assertInstanceOf(RuntimeState.Stopping::class.java, supervisor.state.value)

            allowTerminate.complete(Unit)
            stopping.await()
            restarting.await()
            runCurrent()

            assertEquals(2, launchCount)
            assertInstanceOf(RuntimeState.Ready::class.java, supervisor.state.value)
            supervisor.stop()
        }

    @Test
    fun `failed runtime can be explicitly retried`() =
        runTest {
            val backend =
                FakeBackend(
                    launchErrors =
                        ArrayDeque(
                            listOf(
                                IOException("one"),
                                IOException("two"),
                                IOException("three"),
                            ),
                        ),
                )
            val supervisor = DefaultRuntimeSupervisor(backend, this, fastPolicy())
            supervisor.start()
            advanceUntilIdle()
            assertInstanceOf(RuntimeState.Failed::class.java, supervisor.state.value)

            supervisor.retry()
            advanceUntilIdle()

            assertEquals(4, backend.launchCount)
            assertInstanceOf(RuntimeState.Ready::class.java, supervisor.state.value)
            supervisor.stop()
        }

    @Test
    fun `runtime value objects reject invalid policies and endpoints`() {
        assertThrows(IllegalArgumentException::class.java) {
            RetryPolicy(startupTimeoutMillis = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RetryPolicy(shutdownGraceMillis = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RetryPolicy(restartDelaysMillis = listOf(-1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeEndpoint(0, "instance")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeEndpoint(8_000, " ")
        }
        assertEquals("http://127.0.0.1:8000", RuntimeEndpoint(8_000, "instance").baseUrl)
    }

    private class FakeBackend(
        private val exitCodes: ArrayDeque<Int> = ArrayDeque(),
        private val failLaunch: Boolean = false,
        private val prepareError: Throwable? = null,
        private val prepareGate: CompletableDeferred<Unit>? = null,
        private val launchErrors: ArrayDeque<Throwable> = ArrayDeque(),
        private val readyErrors: ArrayDeque<Throwable> = ArrayDeque(),
    ) : RuntimeBackend {
        var launchCount = 0

        override suspend fun prepare(reporter: PreparationReporter) {
            prepareError?.let { throw it }
            reporter.report(PreparationStage.INSTALLING_RUNTIME, 100)
            prepareGate?.await()
        }

        override suspend fun launch(attempt: Int): ManagedRuntime {
            launchCount++
            if (failLaunch) throw IOException("launch failed")
            launchErrors.removeFirstOrNull()?.let { throw it }
            return FakeRuntime(exitCodes.removeFirstOrNull())
        }

        override suspend fun awaitReady(
            runtime: ManagedRuntime,
            timeoutMillis: Long,
        ): RuntimeEndpoint {
            readyErrors.removeFirstOrNull()?.let { throw it }
            return RuntimeEndpoint(runtime.port, runtime.instanceId)
        }
    }

    private class FakeRuntime(
        exitCode: Int?,
    ) : ManagedRuntime {
        private val exit = CompletableDeferred<Int>()

        init {
            if (exitCode != null) {
                exit.complete(exitCode)
            }
        }

        override val port: Int = 8_000
        override val instanceId: String = "test-instance"
        override val isAlive: Boolean
            get() = !exit.isCompleted

        override suspend fun awaitExit(): Int = exit.await()

        override suspend fun terminate(graceMillis: Long) {
            exit.complete(0)
        }
    }

    private fun fastPolicy() =
        RetryPolicy(
            restartDelaysMillis = listOf(1, 2),
            startupTimeoutMillis = 100,
            shutdownGraceMillis = 10,
        )
}
