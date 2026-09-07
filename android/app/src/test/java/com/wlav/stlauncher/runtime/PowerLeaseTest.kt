package com.wlav.stlauncher.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PowerLeaseTest {
    @Test
    fun `lease acquisition renewal and release are idempotent`() =
        runTest {
            val wake = FakeLease()
            val wifi = FakeLease()
            val controller = RenewingLeaseController(this, wake, wifi, renewAfterMillis = 100)

            controller.acquire()
            controller.acquire()
            assertEquals(1, wake.acquireCount)
            assertEquals(1, wifi.acquireCount)

            advanceTimeBy(100)
            runCurrent()
            assertEquals(2, wake.acquireCount)
            assertEquals(1, wifi.acquireCount)

            controller.release()
            controller.release()
            assertEquals(1, wake.releaseCount)
            assertEquals(1, wifi.releaseCount)
            assertFalse(wake.isHeld)
            assertFalse(wifi.isHeld)
        }

    @Test
    fun `partial acquisition failure releases every held lease`() {
        val wake = FakeLease()
        val wifi = FakeLease(failAcquire = true)
        val controller =
            RenewingLeaseController(
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job()),
                wakeLease = wake,
                wifiLease = wifi,
                renewAfterMillis = 100,
            )

        assertThrows(IllegalStateException::class.java) { controller.acquire() }

        assertFalse(wake.isHeld)
        assertEquals(1, wake.releaseCount)
    }

    @Test
    fun `renewal failure releases leases and retries while still requested`() =
        runTest {
            val wake = FakeLease(failOnAcquire = 2)
            val wifi = FakeLease()
            val controller = RenewingLeaseController(this, wake, wifi, renewAfterMillis = 100)

            controller.acquire()
            advanceTimeBy(100)
            runCurrent()

            assertFalse(wake.isHeld)
            assertFalse(wifi.isHeld)
            assertEquals(1, wake.releaseCount)
            assertEquals(1, wifi.releaseCount)
            advanceTimeBy(100)
            runCurrent()
            assertEquals(3, wake.acquireCount)
            assertEquals(2, wifi.acquireCount)
            controller.release()
        }

    private class FakeLease(
        private val failAcquire: Boolean = false,
        private val failOnAcquire: Int? = null,
    ) : LeaseHandle {
        override var isHeld = false
        var acquireCount = 0
        var releaseCount = 0

        override fun acquire() {
            acquireCount++
            if (failAcquire || acquireCount == failOnAcquire) {
                throw IllegalStateException("lease unavailable")
            }
            isHeld = true
        }

        override fun release() {
            releaseCount++
            isHeld = false
        }
    }
}
