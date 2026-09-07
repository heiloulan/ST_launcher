package com.wlav.stlauncher.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleService
import com.wlav.stlauncher.MainActivity
import com.wlav.stlauncher.R
import com.wlav.stlauncher.ui.RuntimeStatusPresenter
import com.wlav.stlauncher.ui.resolve
import com.wlav.stlauncher.web.AndroidDownloadCoordinator
import com.wlav.stlauncher.web.PendingSave
import com.wlav.stlauncher.web.pendingSaveFromBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Owns the runtime and long data operations; Activities only submit requests. */
class RuntimeForegroundService : LifecycleService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Mutex()
    private lateinit var backend: AndroidRuntimeBackend
    private lateinit var supervisor: RuntimeSupervisor
    private lateinit var powerLease: PowerLease
    private lateinit var downloads: AndroidDownloadCoordinator
    private val preferences by lazy { getSharedPreferences("runtime_service", MODE_PRIVATE) }
    private val transfer = MutableStateFlow(TransferState())
    private var stateObserver: Job? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        val state: StateFlow<RuntimeState> get() = supervisor.state
        val transferState: StateFlow<TransferState> get() = transfer

        fun retry() {
            ContextCompat.startForegroundService(
                this@RuntimeForegroundService,
                Intent(this@RuntimeForegroundService, RuntimeForegroundService::class.java).setAction(ACTION_RETRY),
            )
        }

        fun acknowledgeTransfer(id: Long) {
            if (transfer.value.id == id && !transfer.value.busy) {
                transfer.value = TransferState()
                preferences.edit { remove("task_message") }
            }
        }

        suspend fun logTail(
            maximumLines: Int = 200,
            maximumBytes: Int = 256 * 1024,
        ): String = withContext(Dispatchers.IO) { backend.readLogTail(maximumLines, maximumBytes) }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        backend = AndroidRuntimeBackend(this, serviceScope)
        supervisor = DefaultRuntimeSupervisor(backend, serviceScope)
        powerLease = PowerLease(this, serviceScope)
        downloads = AndroidDownloadCoordinator(this)
        val interrupted = preferences.getBoolean("task_busy", false)
        val incompleteRemoved =
            !interrupted || preferences
                .getString("task_destination", null)
                ?.let { deleteIncomplete(it.toUri()) } != false
        val previousMessage =
            if (interrupted) {
                getString(R.string.transfer_interrupted) +
                    if (incompleteRemoved) "" else "\n${getString(R.string.transfer_incomplete_file)}"
            } else {
                preferences.getString("task_message", null)
            }
        if (previousMessage != null) {
            transfer.value =
                TransferState(
                    System.currentTimeMillis(),
                    message = previousMessage,
                    success = !interrupted && preferences.getBoolean("task_success", false),
                )
            preferences.edit {
                putBoolean("task_busy", false)
                putString("task_message", previousMessage)
                if (interrupted) {
                    putBoolean("task_success", false)
                    remove("task_destination")
                }
            }
        }
        backend.recordEvent("Service created; previous data operation interrupted=$interrupted")
        runCatching { backend.recordEnvironment() }
            .onFailure { backend.recordEvent("Environment diagnostics unavailable: ${it.message}") }
        stateObserver =
            serviceScope.launch {
                combine(supervisor.state, transfer) { state, task -> state to task }.collect { (state, task) ->
                    try {
                        if (task.busy || state is RuntimeState.Preparing || state is RuntimeState.Starting || state is RuntimeState.Ready) {
                            powerLease.acquire()
                        } else {
                            powerLease.release()
                        }
                    } catch (error: Exception) {
                        backend.recordEvent("Power lease failed: ${error.message}")
                    }
                    val text =
                        if (task.busy) {
                            task.message
                        } else {
                            RuntimeStatusPresenter.notification(state).resolve(this@RuntimeForegroundService)
                        }
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
                }
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_bootstrapping)))
        when (intent?.action) {
            ACTION_STOP -> {
                preferences.edit { putBoolean("runtime_wanted", false) }
                command {
                    supervisor.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_RETRY -> {
                preferences.edit { putBoolean("runtime_wanted", true) }
                command {
                    if (supervisor.state.value == RuntimeState.Idle) supervisor.start() else supervisor.retry()
                }
            }
            ACTION_SAVE -> {
                val request = intent.getBundleExtra(EXTRA_SAVE)?.let(::pendingSaveFromBundle)
                val destination = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
                if (request != null && destination != null) {
                    command { runTransfer(getString(R.string.transfer_saving), destination) { save(request, destination) } }
                }
            }
            ACTION_IMPORT -> {
                val source = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
                val origin = intent.getStringExtra(EXTRA_ORIGIN)
                if (source != null && origin != null) {
                    command {
                        runTransfer(getString(R.string.stage_verifying_backup)) {
                            check((supervisor.state.value as? RuntimeState.Ready)?.endpoint?.baseUrl == origin) {
                                getString(R.string.toast_server_not_ready)
                            }
                            val id = downloads.uploadImport(source, origin)
                            transfer.value = transfer.value.copy(message = getString(R.string.stage_applying_backup))
                            withContext(NonCancellable) { commitPreparedImport(id) }
                        }
                    }
                }
            }
            else -> {
                if (intent != null) preferences.edit { putBoolean("runtime_wanted", true) }
                if (preferences.getBoolean("runtime_wanted", false)) {
                    command { supervisor.start() }
                } else if (intent == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun command(action: suspend () -> Unit) {
        serviceScope.launch {
            commands.withLock {
                try {
                    action()
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (error: Exception) {
                    backend.recordEvent("Service operation failed: ${error.message}")
                }
            }
        }
    }

    private suspend fun runTransfer(
        message: String,
        destination: Uri? = null,
        action: suspend () -> String,
    ) {
        val id = System.currentTimeMillis()
        preferences.edit(commit = true) {
            putBoolean("task_busy", true)
            putString("task_destination", destination?.toString())
            remove("task_message")
        }
        transfer.value = TransferState(id, busy = true, message = message)
        backend.recordEvent("Data operation started: $message")
        try {
            finishTransfer(id, action(), true)
        } catch (cancelled: CancellationException) {
            destination?.let(::deleteIncomplete)
            finishTransfer(id, getString(R.string.transfer_interrupted), false)
            throw cancelled
        } catch (error: Exception) {
            val deleted = destination?.let(::deleteIncomplete) ?: true
            val detail =
                error.message.orEmpty() +
                    if (deleted) "" else "\n${getString(R.string.transfer_incomplete_file)}"
            finishTransfer(id, getString(R.string.toast_save_failed, detail), false)
        }
    }

    private fun finishTransfer(
        id: Long,
        message: String,
        success: Boolean,
    ) {
        preferences.edit(commit = true) {
            putBoolean("task_busy", false)
            remove("task_destination")
            putString("task_message", message)
            putBoolean("task_success", success)
        }
        transfer.value = TransferState(id, message = message, success = success)
        backend.recordEvent("Data operation finished: success=$success; $message")
    }

    private suspend fun save(
        request: PendingSave,
        destination: Uri,
    ): String {
        downloads.save(request, destination)
        try {
            downloads.cancel(request)
        } catch (error: Exception) {
            backend.recordEvent("Saved successfully; temporary download cleanup failed: ${error.message}")
        }
        return getString(R.string.toast_save_succeeded)
    }

    private suspend fun commitPreparedImport(id: String): String {
        var transaction: ImportTransactionManager.Transaction? = null
        try {
            supervisor.stop()
            transaction = backend.beginImportCommit(id)
            supervisor.start()
            val terminal =
                withTimeoutOrNull(75_000) {
                    supervisor.state.first { it is RuntimeState.Ready || it is RuntimeState.Failed }
                }
            if (terminal !is RuntimeState.Ready) {
                throw IOException(
                    (terminal as? RuntimeState.Failed)?.failure?.message ?: getString(R.string.import_health_check_failed),
                )
            }
            backend.commitImport(transaction)
            return getString(R.string.import_succeeded)
        } catch (error: Exception) {
            supervisor.stop()
            var restored = (error as? ImportTransactionManager.ActivationException)?.originalRestored ?: true
            transaction?.let {
                try {
                    backend.rollbackImport(it)
                } catch (recovery: Exception) {
                    restored = false
                    backend.recordEvent("Import rollback failed: ${recovery.message}")
                }
            }
            if (restored) supervisor.start()
            val messageId = if (restored) R.string.import_original_kept else R.string.import_recovery_pending
            throw IOException(getString(messageId, error.message.orEmpty()), error)
        }
    }

    private fun deleteIncomplete(uri: Uri): Boolean =
        try {
            DocumentsContract.deleteDocument(contentResolver, uri)
        } catch (_: Exception) {
            false
        }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        stateObserver?.cancel()
        powerLease.release()
        serviceScope.launch {
            commands.withLock { supervisor.stop() }
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notification_channel_description)
                setSound(null, null)
            },
        )
    }

    private fun buildNotification(text: String): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, RuntimeForegroundService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.action_stop_runtime), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.wlav.stlauncher.runtime.START"
        const val ACTION_STOP = "com.wlav.stlauncher.runtime.STOP"
        const val ACTION_RETRY = "com.wlav.stlauncher.runtime.RETRY"
        const val ACTION_SAVE = "com.wlav.stlauncher.runtime.SAVE"
        const val ACTION_IMPORT = "com.wlav.stlauncher.runtime.IMPORT"
        const val EXTRA_SAVE = "save"
        const val EXTRA_URI = "uri"
        const val EXTRA_ORIGIN = "origin"
        private const val CHANNEL_ID = "runtime_service_v3"
        private const val NOTIFICATION_ID = 1
    }
}

data class TransferState(
    val id: Long = 0,
    val busy: Boolean = false,
    val message: String = "",
    val success: Boolean? = null,
)
