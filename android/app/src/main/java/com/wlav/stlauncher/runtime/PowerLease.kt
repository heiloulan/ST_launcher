package com.wlav.stlauncher.runtime

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PowerLease(
    context: Context,
    scope: CoroutineScope,
) {
    private val wakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "STLauncher::Runtime")
            .apply { setReferenceCounted(false) }

    @Suppress("DEPRECATION")
    private val wifiLock =
        (
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        ).createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "STLauncher::RuntimeWifi",
        ).apply { setReferenceCounted(false) }

    private val controller =
        RenewingLeaseController(
            scope = scope,
            wakeLease =
                object : LeaseHandle {
                    override val isHeld: Boolean
                        get() = wakeLock.isHeld

                    @Suppress("WakelockTimeout")
                    override fun acquire() {
                        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
                    }

                    override fun release() {
                        wakeLock.release()
                    }
                },
            wifiLease =
                object : LeaseHandle {
                    override val isHeld: Boolean
                        get() = wifiLock.isHeld

                    override fun acquire() {
                        wifiLock.acquire()
                    }

                    override fun release() {
                        wifiLock.release()
                    }
                },
            renewAfterMillis = RENEW_AFTER_MILLIS,
        )

    fun acquire() = controller.acquire()

    fun release() = controller.release()

    companion object {
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60 * 1_000L
        private const val RENEW_AFTER_MILLIS = 9 * 60 * 1_000L
    }
}

internal interface LeaseHandle {
    val isHeld: Boolean

    fun acquire()

    fun release()
}

internal class RenewingLeaseController(
    private val scope: CoroutineScope,
    private val wakeLease: LeaseHandle,
    private val wifiLease: LeaseHandle,
    private val renewAfterMillis: Long,
) {
    private var renewalJob: Job? = null

    init {
        require(renewAfterMillis > 0)
    }

    @Synchronized
    fun acquire() {
        if (renewalJob?.isActive == true) return
        try {
            wakeLease.acquire()
            if (!wifiLease.isHeld) wifiLease.acquire()
            renewalJob =
                scope.launch {
                    var nextRenewal = renewAfterMillis
                    while (isActive) {
                        delay(nextRenewal)
                        synchronized(this@RenewingLeaseController) {
                            try {
                                wakeLease.acquire()
                                if (!wifiLease.isHeld) wifiLease.acquire()
                                nextRenewal = renewAfterMillis
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                releaseHandles()
                                nextRenewal = minOf(renewAfterMillis, 30_000)
                            }
                        }
                    }
                }
        } catch (error: Throwable) {
            release()
            throw error
        }
    }

    @Synchronized
    fun release() {
        renewalJob?.cancel()
        renewalJob = null
        releaseHandles()
    }

    private fun releaseHandles() {
        try {
            if (wakeLease.isHeld) wakeLease.release()
        } finally {
            if (wifiLease.isHeld) wifiLease.release()
        }
    }
}
