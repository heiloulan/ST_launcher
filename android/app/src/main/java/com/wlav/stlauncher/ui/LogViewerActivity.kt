package com.wlav.stlauncher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wlav.stlauncher.R
import com.wlav.stlauncher.databinding.ActivityLogViewerBinding
import com.wlav.stlauncher.runtime.RuntimeForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Native viewer for the Node server log.
 *
 * This replaces the in-page overlay the SillyTavern extension used to build. Two
 * reasons: it is reachable while startup is still failing — precisely when the
 * WebView never comes up — and native text selection works without competing
 * with SillyTavern's global CSS and gesture handling.
 */
class LogViewerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLogViewerBinding

    private var binder: RuntimeForegroundService.LocalBinder? = null
    private var bound = false
    private var autoRefresh = true
    private var pinnedToBottom = true
    private var logText = ""
    private var unboundCycles = 0

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                service: IBinder?,
            ) {
                binder = service as? RuntimeForegroundService.LocalBinder
                lifecycleScope.launch { refresh() }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.logScroll.setOnScrollChangeListener { view, _, scrollY, _, _ ->
            val content = (view as ScrollView).getChildAt(0)
            val distanceFromBottom = (content?.height ?: 0) - view.height - scrollY
            pinnedToBottom = distanceFromBottom <= bottomThresholdPixels()
        }

        startRefreshLoop()
    }

    override fun onStart() {
        super.onStart()
        // BIND_AUTO_CREATE on purpose: the log directory is still readable after
        // the runtime has died, which is when these logs matter most.
        bound =
            bindService(
                Intent(this, RuntimeForegroundService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
    }

    override fun onStop() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        binder = null
        super.onStop()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.log_viewer, menu)
        menu.findItem(R.id.action_auto_refresh)?.isChecked = autoRefresh
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_copy -> {
                copyLogs()
                true
            }
            R.id.action_share -> {
                shareLogs()
                true
            }
            R.id.action_auto_refresh -> {
                autoRefresh = !item.isChecked
                item.isChecked = autoRefresh
                if (autoRefresh) lifecycleScope.launch { refresh() }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }

    private fun startRefreshLoop() {
        lifecycleScope.launch {
            // Tied to STARTED so the loop cannot spin while backgrounded, which
            // the extension's setInterval could.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    if (autoRefresh) refresh()
                    delay(REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    private suspend fun refresh() {
        val currentBinder = binder
        if (currentBinder == null) {
            // The connection normally lands within milliseconds of onStart, so
            // stay quiet for a couple of cycles rather than flashing an error.
            unboundCycles++
            if (unboundCycles <= UNBOUND_GRACE_CYCLES) return
        } else {
            unboundCycles = 0
        }

        val tail = currentBinder?.logTail(MAXIMUM_LINES, MAXIMUM_BYTES)
        // Replacing the text would drop an in-progress selection, so a refresh
        // yields to the user whenever they are actually selecting something.
        if (binding.logText.hasSelection()) return
        logText = tail.orEmpty()
        val display =
            when {
                tail == null -> getString(R.string.log_viewer_unavailable)
                tail.isBlank() -> getString(R.string.log_viewer_empty)
                else -> tail
            }
        if (binding.logText.text.toString() == display) return
        binding.logText.text = display
        if (pinnedToBottom) {
            binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun copyLogs() {
        if (logText.isBlank()) {
            toast(R.string.toast_logs_empty)
            return
        }
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(
                ClipData.newPlainText(getString(R.string.log_viewer_title), logText),
            )
        // Android 13 and above shows its own copy confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            toast(R.string.toast_logs_copied)
        }
    }

    private fun shareLogs() {
        if (logText.isBlank()) {
            toast(R.string.toast_logs_empty)
            return
        }
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_viewer_share_subject))
                // Intent extras travel over a Binder transaction with a hard
                // size ceiling, so only the tail is shared.
                putExtra(Intent.EXTRA_TEXT, logText.takeLast(SHARE_CHARACTER_LIMIT))
            }
        runCatching {
            startActivity(
                Intent.createChooser(intent, getString(R.string.log_viewer_share_chooser)),
            )
        }.onFailure { toast(R.string.log_viewer_share_unavailable) }
    }

    private fun bottomThresholdPixels(): Int = (BOTTOM_THRESHOLD_DP * resources.displayMetrics.density).toInt()

    private fun toast(
        @StringRes message: Int,
    ) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 2_000L
        private const val MAXIMUM_LINES = 1_000
        private const val MAXIMUM_BYTES = 512 * 1024
        private const val SHARE_CHARACTER_LIMIT = 128_000
        private const val BOTTOM_THRESHOLD_DP = 48f
        private const val UNBOUND_GRACE_CYCLES = 2

        fun intent(context: Context): Intent = Intent(context, LogViewerActivity::class.java)
    }
}
