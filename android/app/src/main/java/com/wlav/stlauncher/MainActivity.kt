package com.wlav.stlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wlav.stlauncher.databinding.ActivityMainBinding
import com.wlav.stlauncher.runtime.PreparationStage
import com.wlav.stlauncher.runtime.RuntimeEndpoint
import com.wlav.stlauncher.runtime.RuntimeForegroundService
import com.wlav.stlauncher.runtime.RuntimeState
import com.wlav.stlauncher.runtime.TransferState
import com.wlav.stlauncher.ui.BatteryGuidance
import com.wlav.stlauncher.ui.LogViewerActivity
import com.wlav.stlauncher.ui.RuntimeStatusPresenter
import com.wlav.stlauncher.ui.StatusPanelController
import com.wlav.stlauncher.web.AndroidDownloadCoordinator
import com.wlav.stlauncher.web.BlobDownloadStore
import com.wlav.stlauncher.web.DownloadCoordinator
import com.wlav.stlauncher.web.LauncherDownloadListener
import com.wlav.stlauncher.web.LauncherWebChromeClient
import com.wlav.stlauncher.web.LauncherWebViewClient
import com.wlav.stlauncher.web.PendingSave
import com.wlav.stlauncher.web.SecureWebBridge
import com.wlav.stlauncher.web.ThemeColors
import com.wlav.stlauncher.web.applyLauncherSecurityPolicy
import com.wlav.stlauncher.web.toBundle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The launcher shell: Activity lifecycle, the bound runtime service, the SAF
 * result launchers, and the wiring between them.
 *
 * Rendering lives in [StatusPanelController], navigation policy in
 * [LauncherWebViewClient], and host-side WebView UI in [LauncherWebChromeClient].
 * Result launchers must be registered on the Activity, so those stay here while
 * the handling of each result is delegated.
 */
class MainActivity :
    AppCompatActivity(),
    SecureWebBridge.Callbacks,
    LauncherWebViewClient.Callbacks,
    LauncherWebChromeClient.Callbacks {
    private lateinit var binding: ActivityMainBinding
    private lateinit var statusPanel: StatusPanelController
    private val viewModel: LauncherViewModel by viewModels()
    private lateinit var downloadCoordinator: DownloadCoordinator
    private lateinit var blobStore: BlobDownloadStore
    private lateinit var webBridge: SecureWebBridge

    private var serviceBinder: RuntimeForegroundService.LocalBinder? = null
    private var serviceStateJob: Job? = null
    private var serviceBound = false
    private var loadedEndpoint: RuntimeEndpoint? = null
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var fileChooserAllowsMultiple = false
    private var retryAction: () -> Unit = { serviceBinder?.retry() }
    private var importPickerOpen = false
    private var displayedTransferId = 0L
    private var webViewDestroyed = false

    private val fileChooserLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val uris =
                if (result.resultCode == RESULT_OK) {
                    result.data?.let { intent ->
                        when {
                            fileChooserAllowsMultiple && intent.clipData != null ->
                                Array(intent.clipData!!.itemCount) { index ->
                                    intent.clipData!!.getItemAt(index).uri
                                }
                            intent.data != null -> arrayOf(intent.data!!)
                            else -> null
                        }
                    }
                } else {
                    null
                }
            fileUploadCallback?.onReceiveValue(uris)
            fileUploadCallback = null
        }

    private val saveFileLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            val pending = viewModel.consumePendingSave()
            val destination = result.data?.data
            if (result.resultCode != RESULT_OK || destination == null || pending == null) {
                pending?.let(::discardPendingSave)
                return@registerForActivityResult
            }

            retainDocumentPermission(result.data, destination)
            ContextCompat.startForegroundService(
                this,
                Intent(this, RuntimeForegroundService::class.java)
                    .setAction(RuntimeForegroundService.ACTION_SAVE)
                    .putExtra(RuntimeForegroundService.EXTRA_SAVE, pending.toBundle())
                    .putExtra(RuntimeForegroundService.EXTRA_URI, destination.toString()),
            )
        }

    private val importFileLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            importPickerOpen = false
            val source = result.data?.data
            if (result.resultCode != RESULT_OK || source == null) {
                return@registerForActivityResult
            }
            retainDocumentPermission(result.data, source)
            uploadAndCommitImport(source)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) {
            // A denied permission does not block foreground use. Android still
            // exposes the foreground service in the task manager.
        }

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                serviceBinder = service as? RuntimeForegroundService.LocalBinder
                serviceBound = serviceBinder != null
                collectServiceState()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceBound = false
                serviceBinder = null
                serviceStateJob?.cancel()
                showLocalFailure(R.string.error_service_disconnected)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            insets
        }

        statusPanel =
            StatusPanelController(binding).apply {
                onRetry = { retryAction() }
                onViewLogs = ::openLogViewer
            }

        downloadCoordinator = AndroidDownloadCoordinator(this)
        blobStore = BlobDownloadStore(cacheDir, retainedPath = (viewModel.pendingSave as? PendingSave.TemporaryFile)?.path)
        val bridgeScript =
            assets
                .open("web/launcher-bridge.js")
                .bufferedReader()
                .use { it.readText() }
        webBridge =
            SecureWebBridge(
                webView = binding.webView,
                scope = lifecycleScope,
                blobStore = blobStore,
                scriptSource = bridgeScript,
                callbacks = this,
            )

        binding.launcherMenuButton.setOnClickListener { openLauncherMenu() }
        configureWebView()
        configureBackNavigation()
        renderState(RuntimeState.Preparing(PreparationStage.RECOVERING))
        requestNotificationPermission()
        startAndBindRuntime()
    }

    override fun onStart() {
        super.onStart()
        statusPanel.onStart()
        if (!webViewDestroyed) binding.webView.resumeTimers()
        if (!serviceBound) bindRuntime()
    }

    override fun onStop() {
        statusPanel.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        serviceStateJob?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        webBridge.remove()
        lifecycleScope.launch { blobStore.cancelAll() }
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = null
        if (!webViewDestroyed) {
            binding.webView.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
            webViewDestroyed = true
        }
        super.onDestroy()
    }

    private fun startAndBindRuntime() {
        val intent =
            Intent(this, RuntimeForegroundService::class.java)
                .setAction(RuntimeForegroundService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        bindRuntime()
    }

    private fun bindRuntime() {
        if (serviceBound) return
        serviceBound =
            bindService(
                Intent(this, RuntimeForegroundService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE,
            )
    }

    private fun collectServiceState() {
        val binder = serviceBinder ?: return
        serviceStateJob?.cancel()
        serviceStateJob =
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch { binder.state.collect { if (!binder.transferState.value.busy) renderState(it) } }
                    launch { binder.transferState.collect(::renderTransfer) }
                }
            }
    }

    private fun renderState(state: RuntimeState) {
        // Re-emitting Ready for an endpoint already on screen must not pull the
        // loading panel back over a live page.
        if (state is RuntimeState.Ready &&
            loadedEndpoint == state.endpoint &&
            binding.webView.isVisible
        ) {
            return
        }
        statusPanel.render(state)
        if (state is RuntimeState.Ready) {
            loadEndpoint(state.endpoint)
            BatteryGuidance.showOnce(this)
        }
    }

    private fun loadEndpoint(endpoint: RuntimeEndpoint) {
        loadedEndpoint = endpoint
        retryAction = { serviceBinder?.retry() }
        statusPanel.setRetryLabel(R.string.action_retry)
        try {
            webBridge.install(endpoint.baseUrl)
            binding.webView.clearHistory()
            binding.webView.loadUrl(endpoint.baseUrl)
        } catch (error: Throwable) {
            showLocalFailure(R.string.error_secure_channel, error.message)
        }
    }

    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().setAcceptCookie(true)
        binding.webView.settings.applyLauncherSecurityPolicy(BuildConfig.VERSION_NAME)
        // SillyTavern scales its text and layout together through the theme.
        // System text zoom enlarges glyphs without resizing its CSS toolbars.
        binding.webView.settings.textZoom = 100
        binding.webView.webViewClient = LauncherWebViewClient(this)
        binding.webView.webChromeClient = LauncherWebChromeClient(BuildConfig.DEBUG, this)
        binding.webView.setDownloadListener(
            LauncherDownloadListener(
                defaultMimeType = DEFAULT_MIME,
                onInPageTransfer = webBridge::requestInPageTransfer,
                onSaveRequested = ::onSaveRequested,
            ),
        )
    }

    private fun configureBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.webView.canGoBack()) {
                        binding.webView.goBack()
                    } else {
                        moveTaskToBack(true)
                    }
                }
            },
        )
    }

    // ---- LauncherWebViewClient.Callbacks -----------------------------------

    override fun activeOrigin(): String? = loadedEndpoint?.baseUrl

    override fun onLocalPageFinished() {
        webBridge.injectFallbackIfNeeded()
    }

    override fun onLocalPageVisible() {
        statusPanel.hidePanelForWebContent()
    }

    override fun onExternalNavigation(url: Uri) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
            .onFailure { showToast(getString(R.string.toast_external_link_failed)) }
    }

    override fun onBlockedNavigation() {
        showToast(getString(R.string.toast_link_blocked))
    }

    override fun onLoadFailure(
        @StringRes titleRes: Int,
        detail: String?,
    ) {
        showLocalFailure(titleRes, detail)
    }

    override fun onRendererGone(view: WebView) {
        webBridge.remove()
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        webViewDestroyed = true
        showLocalFailure(R.string.error_render_process_gone)
        // The WebView is gone for good; only a fresh Activity can recover.
        retryAction = { recreate() }
        statusPanel.setRetryLabel(R.string.action_reload)
    }

    // ---- LauncherWebChromeClient.Callbacks ---------------------------------

    override fun onFileChooserRequested(
        intent: Intent,
        callback: ValueCallback<Array<Uri>>,
        allowMultiple: Boolean,
    ): Boolean {
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = callback
        fileChooserAllowsMultiple = allowMultiple
        return runCatching {
            fileChooserLauncher.launch(intent)
            true
        }.getOrElse {
            fileUploadCallback = null
            false
        }
    }

    // ---- SecureWebBridge.Callbacks -----------------------------------------

    override fun onThemeColor(value: String) {
        val color = ThemeColors.parse(value) ?: return
        binding.root.setBackgroundColor(color)
        val light = ColorUtils.calculateLuminance(color) > 0.5
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    override fun onToast(message: String) {
        showToast(message.take(300))
    }

    override fun onSaveRequested(save: PendingSave) {
        runOnUiThread {
            if (viewModel.pendingSave != null) {
                showToast(getString(R.string.toast_save_dialog_busy))
                discardPendingSave(save)
                return@runOnUiThread
            }
            viewModel.pendingSave = save
            val intent =
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = save.mimeType
                    putExtra(Intent.EXTRA_TITLE, save.fileName)
                }
            runCatching { saveFileLauncher.launch(intent) }
                .onFailure {
                    viewModel.consumePendingSave()
                    discardPendingSave(save)
                    showToast(
                        getString(R.string.toast_save_dialog_failed, it.message.orEmpty()),
                    )
                }
        }
    }

    override fun onImportRequested() {
        runOnUiThread {
            if (importPickerOpen) return@runOnUiThread
            importPickerOpen = true
            val intent =
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        arrayOf("application/gzip", "application/x-gzip", DEFAULT_MIME),
                    )
                }
            runCatching { importFileLauncher.launch(intent) }
                .onFailure {
                    importPickerOpen = false
                    showToast(getString(R.string.toast_import_picker_failed))
                }
        }
    }

    override fun onLogsRequested() {
        runOnUiThread(::openLogViewer)
    }

    override fun onLauncherMenuRequested() {
        runOnUiThread(::openLauncherMenu)
    }

    // ------------------------------------------------------------------------

    private fun openLogViewer() {
        runCatching { startActivity(LogViewerActivity.intent(this)) }
            .onFailure { showToast(getString(R.string.toast_logs_unavailable)) }
    }

    private fun discardPendingSave(save: PendingSave) {
        lifecycleScope.launch {
            runCatching { downloadCoordinator.cancel(save) }
        }
    }

    private fun uploadAndCommitImport(source: Uri) {
        val endpoint = loadedEndpoint
        val binder = serviceBinder
        if (endpoint == null || binder == null) {
            showToast(getString(R.string.toast_server_not_ready))
            return
        }

        ContextCompat.startForegroundService(
            this,
            Intent(this, RuntimeForegroundService::class.java)
                .setAction(RuntimeForegroundService.ACTION_IMPORT)
                .putExtra(RuntimeForegroundService.EXTRA_URI, source.toString())
                .putExtra(RuntimeForegroundService.EXTRA_ORIGIN, endpoint.baseUrl),
        )
    }

    private fun renderTransfer(task: TransferState) {
        if (task.busy) {
            statusPanel.render(RuntimeStatusPresenter.busy(R.string.transfer_title, R.string.transfer_wait))
            binding.statusStage.text = task.message
            return
        }
        if (task.id == 0L) return
        serviceBinder?.state?.value?.let(::renderState)
        if (displayedTransferId == task.id) return
        displayedTransferId = task.id
        MaterialAlertDialogBuilder(this)
            .setTitle(if (task.success == true) R.string.transfer_finished else R.string.transfer_failed)
            .setMessage(task.message)
            .setPositiveButton(android.R.string.ok) { _, _ -> serviceBinder?.acknowledgeTransfer(task.id) }
            .setOnCancelListener { serviceBinder?.acknowledgeTransfer(task.id) }
            .show()
    }

    @SuppressLint("WrongConstant") // Result flags are restricted to the two grants below.
    private fun retainDocumentPermission(
        intent: Intent?,
        uri: Uri,
    ) {
        val flags = (intent?.flags ?: 0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (flags == 0) return
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
    }

    private fun openLauncherMenu() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.launcher_menu)
            .setItems(
                arrayOf(
                    getString(R.string.action_import_backup),
                    getString(R.string.battery_dialog_title),
                    getString(R.string.action_view_logs),
                ),
            ) { _, which ->
                when (which) {
                    0 ->
                        MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.action_import_backup)
                            .setMessage(R.string.import_confirmation)
                            .setPositiveButton(R.string.action_continue) { _, _ -> onImportRequested() }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    1 -> BatteryGuidance.showSettings(this)
                    2 -> openLogViewer()
                }
            }.show()
    }

    private fun showLocalFailure(
        @StringRes titleRes: Int,
        detail: String? = null,
    ) {
        retryAction = {
            val ready = serviceBinder?.state?.value as? RuntimeState.Ready
            if (ready != null && !webViewDestroyed) loadEndpoint(ready.endpoint) else serviceBinder?.retry()
        }
        statusPanel.setRetryLabel(R.string.action_retry)
        statusPanel.render(RuntimeStatusPresenter.localFailure(titleRes, detail))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showToast(
        message: String,
        long: Boolean = false,
    ) {
        runOnUiThread {
            Toast
                .makeText(
                    this,
                    message,
                    if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                ).show()
        }
    }

    companion object {
        private const val DEFAULT_MIME = "application/octet-stream"
    }
}
