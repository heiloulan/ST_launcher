package com.wlav.stlauncher.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.wlav.stlauncher.MainActivity
import com.wlav.stlauncher.R
import com.wlav.stlauncher.ui.RuntimeStatusPresenter
import com.wlav.stlauncher.ui.resolve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class RuntimeForegroundService : LifecycleService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var backend: AndroidRuntimeBackend
    private lateinit var supervisor: RuntimeSupervisor
    private lateinit var powerLease: PowerLease
    private var stateObserver: Job? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        val state: StateFlow<RuntimeState>
            get() = supervisor.state

        fun retry() {
            serviceScope.launch { supervisor.retry() }
        }

        /**
         * Reads the tail of the server log. Suspending rather than blocking: the
         * call is same-process so there is no IPC cost, and the log viewer asks
         * for a window large enough that a main-thread read would be visible.
         */
        suspend fun logTail(
            maximumLines: Int = 200,
            maximumBytes: Int = 256 * 1024,
        ): String = withContext(Dispatchers.IO) { backend.readLogTail(maximumLines, maximumBytes) }

        fun commitImport(
            id: String,
            callback: (ImportCommitResult) -> Unit,
        ) {
            serviceScope.launch {
                val result = commitPreparedImport(id)
                withContext(Dispatchers.Main) { callback(result) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        backend = AndroidRuntimeBackend(this, serviceScope)
        supervisor = DefaultRuntimeSupervisor(backend, serviceScope)
        powerLease = PowerLease(this, serviceScope)
        stateObserver =
            serviceScope.launch {
                supervisor.state.collect { state ->
                    runCatching {
                        when (state) {
                            is RuntimeState.Preparing,
                            is RuntimeState.Starting,
                            is RuntimeState.Ready,
                            -> powerLease.acquire()
                            else -> powerLease.release()
                        }
                    }.onFailure { error ->
                        Log.w(TAG, "Unable to update the runtime power lease", error)
                    }
                    updateNotification(state)
                }
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notification_bootstrapping)),
        )
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP ->
                serviceScope.launch {
                    supervisor.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            ACTION_RETRY -> serviceScope.launch { supervisor.retry() }
            else -> serviceScope.launch { supervisor.start() }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        serviceScope.launch {
            supervisor.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stateObserver?.cancel()
        powerLease.release()
        serviceScope.launch {
            supervisor.stop()
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    private suspend fun commitPreparedImport(id: String): ImportCommitResult {
        var transaction: ImportTransactionManager.Transaction? = null
        return try {
            supervisor.stop()
            transaction = backend.beginImportCommit(id)
            supervisor.start()
            val terminalState =
                withTimeoutOrNull(75_000) {
                    supervisor.state.first {
                        it is RuntimeState.Ready || it is RuntimeState.Failed
                    }
                }
            if (terminalState is RuntimeState.Ready) {
                backend.commitImport(transaction)
                ImportCommitResult(true, getString(R.string.import_succeeded))
            } else {
                supervisor.stop()
                backend.rollbackImport(transaction)
                supervisor.start()
                val detail =
                    (terminalState as? RuntimeState.Failed)?.failure?.message
                        ?: getString(R.string.import_health_check_failed)
                ImportCommitResult(false, getString(R.string.import_rolled_back, detail))
            }
        } catch (error: Throwable) {
            runCatching { supervisor.stop() }
            transaction?.let { runCatching { backend.rollbackImport(it) } }
            runCatching { supervisor.start() }
            ImportCommitResult(
                success = false,
                message =
                    getString(
                        R.string.import_original_kept,
                        error.message ?: getString(R.string.import_failed_generic),
                    ),
            )
        }
    }

    private fun updateNotification(state: RuntimeState) {
        val text = RuntimeStatusPresenter.notification(state).resolve(this)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setSound(null, null)
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.wlav.stlauncher.runtime.START"
        const val ACTION_STOP = "com.wlav.stlauncher.runtime.STOP"
        const val ACTION_RETRY = "com.wlav.stlauncher.runtime.RETRY"
        private const val CHANNEL_ID = "runtime_service_v3"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "RuntimeService"
    }
}

data class ImportCommitResult(
    val success: Boolean,
    val message: String,
)
