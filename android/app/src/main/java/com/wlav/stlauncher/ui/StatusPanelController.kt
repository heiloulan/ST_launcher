package com.wlav.stlauncher.ui

import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.wlav.stlauncher.R
import com.wlav.stlauncher.databinding.ActivityMainBinding
import com.wlav.stlauncher.runtime.RuntimeState

/**
 * Owns the loading screen: the animation rig, the copy, the progress bar and the
 * two buttons.
 *
 * The panel and the WebView are two halves of one visual state — exactly one of
 * them is on screen at a time — so this controller manages both rather than
 * leaving the pairing for callers to remember.
 */
class StatusPanelController(
    private val binding: ActivityMainBinding,
) {
    private val context = binding.root.context
    private val animation =
        PrayerAnimation(
            halo = binding.haloView,
            particleLayer = binding.particleLayer,
            kaomoji = binding.kaomojiView,
            title = binding.statusTitle,
        )

    private var started = false
    private var panelVisible = true

    @StringRes
    private var retryLabelRes = R.string.action_retry

    var onRetry: () -> Unit = {}
    var onViewLogs: () -> Unit = {}

    init {
        binding.retryButton.setOnClickListener { onRetry() }
        binding.logsButton.setOnClickListener { onViewLogs() }
    }

    fun render(state: RuntimeState) = render(RuntimeStatusPresenter.present(state))

    fun render(view: StatusView) {
        showPanel()
        animation.setMood(view.mood)
        animation.bindTitle(
            text = context.getString(view.titleRes),
            // Only the fixed 「少女祈祷中……」 headline gets the shimmer; a failure
            // title should read as a statement, not as something in progress.
            shimmer = view.titleRes == R.string.status_praying,
        )
        binding.statusStage.setOptionalText(view.stage?.resolve(context))
        binding.statusDetail.setOptionalText(view.detail?.resolve(context))
        applyProgress(view)
        binding.retryButton.setText(retryLabelRes)
        binding.retryButton.isVisible = view.showRetry
        syncAnimation()
    }

    /**
     * Chooses the retry button's wording. A renderer crash needs a full reload
     * rather than a runtime retry, and the label has to say so.
     */
    fun setRetryLabel(
        @StringRes labelRes: Int,
    ) {
        retryLabelRes = labelRes
        binding.retryButton.setText(labelRes)
    }

    fun showPanel() {
        binding.webView.visibility = View.INVISIBLE
        if (panelVisible) return
        panelVisible = true
        binding.statusPanel.visibility = View.VISIBLE
        syncAnimation()
    }

    /** Hands the screen over to the WebView and shuts the rig down. */
    fun hidePanelForWebContent() {
        binding.webView.visibility = View.VISIBLE
        if (!panelVisible) return
        panelVisible = false
        binding.statusPanel.visibility = View.GONE
        syncAnimation()
    }

    fun onStart() {
        started = true
        syncAnimation()
    }

    fun onStop() {
        started = false
        syncAnimation()
    }

    private fun syncAnimation() {
        if (started && panelVisible) animation.start() else animation.stop()
    }

    private fun applyProgress(view: StatusView) {
        val bar = binding.statusProgress
        if (!view.showProgress) {
            bar.isVisible = false
            return
        }
        val wantIndeterminate = view.progress == null
        if (bar.isIndeterminate != wantIndeterminate) {
            // Material refuses to enter indeterminate mode while the indicator
            // is visible, so the switch has to happen off screen.
            bar.visibility = View.GONE
            bar.isIndeterminate = wantIndeterminate
        }
        bar.isVisible = true
        view.progress?.let { bar.setProgressCompat(it, true) }
    }

    private fun TextView.setOptionalText(value: String?) {
        text = value.orEmpty()
        isVisible = !value.isNullOrBlank()
    }
}
