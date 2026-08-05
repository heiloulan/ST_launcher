package com.wlav.stlauncher.ui

import com.wlav.stlauncher.R
import com.wlav.stlauncher.runtime.PreparationStage
import com.wlav.stlauncher.runtime.RuntimeEndpoint
import com.wlav.stlauncher.runtime.RuntimeFailure
import com.wlav.stlauncher.runtime.RuntimeState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The presenter returns `@StringRes` ints rather than resolved strings, so the
 * whole state mapping is assertable without Robolectric or a device.
 */
class RuntimeStatusPresenterTest {
    @Test
    fun `every preparation stage maps to a distinct label`() {
        val labels = PreparationStage.entries.map(RuntimeStatusPresenter::stageLabel)
        assertEquals(PreparationStage.entries.size, labels.distinct().size)
        assertTrue(labels.none { it == 0 })
    }

    @Test
    fun `preparing keeps the praying headline and reports determinate progress`() {
        val view =
            RuntimeStatusPresenter.present(
                RuntimeState.Preparing(PreparationStage.INSTALLING_RUNTIME, progress = 45),
            )

        assertEquals(R.string.status_praying, view.titleRes)
        assertEquals(UiText.of(R.string.stage_installing_runtime), view.stage)
        assertEquals(UiText.of(R.string.status_percent, 45), view.detail)
        assertEquals(45, view.progress)
        assertTrue(view.showProgress)
        assertFalse(view.showRetry)
        assertEquals(KaomojiMood.PRAYING, view.mood)
    }

    @Test
    fun `preparing without a percentage stays indeterminate`() {
        val view = RuntimeStatusPresenter.present(RuntimeState.Preparing(PreparationStage.RECOVERING))

        assertNull(view.progress)
        assertNull(view.detail)
        assertTrue(view.showProgress)
    }

    @Test
    fun `starting reports the attempt count`() {
        val view = RuntimeStatusPresenter.present(RuntimeState.Starting(attempt = 2, maximumAttempts = 3))

        assertEquals(R.string.status_praying, view.titleRes)
        assertEquals(UiText.of(R.string.stage_starting), view.stage)
        assertEquals(UiText.of(R.string.status_attempt, 2, 3), view.detail)
    }

    @Test
    fun `ready shows the page-load stage and the endpoint`() {
        val endpoint = RuntimeEndpoint(port = 8000, instanceId = "abc")
        val view = RuntimeStatusPresenter.present(RuntimeState.Ready(endpoint))

        assertEquals(UiText.of(R.string.stage_loading_page), view.stage)
        assertEquals(UiText.Raw("http://127.0.0.1:8000"), view.detail)
        assertEquals(KaomojiMood.HAPPY, view.mood)
        assertFalse(view.showRetry)
    }

    @Test
    fun `idle stops the progress bar and puts the figure to sleep`() {
        val view = RuntimeStatusPresenter.present(RuntimeState.Idle)

        assertEquals(R.string.status_idle, view.titleRes)
        assertFalse(view.showProgress)
        assertFalse(view.showRetry)
        assertEquals(KaomojiMood.SLEEPING, view.mood)
    }

    @Test
    fun `stopping keeps praying`() {
        val view = RuntimeStatusPresenter.present(RuntimeState.Stopping)

        assertEquals(R.string.status_praying, view.titleRes)
        assertEquals(UiText.of(R.string.stage_stopping), view.stage)
        assertEquals(KaomojiMood.PRAYING, view.mood)
    }

    @Test
    fun `every known failure code gets its own title`() {
        val codes =
            listOf(
                "CANDIDATE_RUNTIME_FAILED",
                "RUNTIME_ACTIVATION_FAILED",
                "PROCESS_EXITED_DURING_START",
                "START_TIMEOUT",
                "NODE_LAUNCH_FAILED",
                "UNSUPPORTED_ABI",
                "NODE_RUNTIME_MISSING",
                "RUNTIME_INCOMPLETE",
                "DATA_MIGRATION_FAILED",
                "RUNTIME_INSTALL_FAILED",
                "PROCESS_EXITED",
            )
        val titles = codes.map(RuntimeStatusPresenter::failureTitle)

        assertEquals(codes.size, titles.distinct().size)
        titles.forEach { assertNotEquals(R.string.failure_unknown, it) }
    }

    @Test
    fun `an unrecognised code falls back to the generic title`() {
        assertEquals(R.string.failure_unknown, RuntimeStatusPresenter.failureTitle("WHAT_IS_THIS"))
        assertEquals(R.string.failure_unknown, RuntimeStatusPresenter.failureTitle(""))
    }

    @Test
    fun `a failure demotes the raw runtime message to the detail line`() {
        val view =
            RuntimeStatusPresenter.present(
                RuntimeState.Failed(
                    RuntimeFailure(
                        code = "PROCESS_EXITED",
                        message = "Node.js exited unexpectedly with code 1",
                        retryable = true,
                    ),
                ),
            )

        assertEquals(R.string.failure_process_exited, view.titleRes)
        assertEquals(UiText.Raw("Node.js exited unexpectedly with code 1"), view.detail)
        assertTrue(view.showRetry)
        assertFalse(view.showProgress)
        assertEquals(KaomojiMood.SAD, view.mood)
    }

    @Test
    fun `message and detail are combined without repeating themselves`() {
        val combined =
            RuntimeStatusPresenter.present(
                RuntimeFailure("START_TIMEOUT", "timed out", retryable = true, detail = "after 60s"),
            )
        assertEquals(UiText.Raw("timed out\nafter 60s"), combined.detail)

        val duplicated =
            RuntimeStatusPresenter.present(
                RuntimeFailure("START_TIMEOUT", "timed out", retryable = true, detail = "timed out"),
            )
        assertEquals(UiText.Raw("timed out"), duplicated.detail)
    }

    @Test
    fun `a failure with nothing to say has no detail line`() {
        val view =
            RuntimeStatusPresenter.present(
                RuntimeFailure("UNSUPPORTED_ABI", message = "   ", retryable = false, detail = null),
            )
        assertNull(view.detail)
    }

    @Test
    fun `local failures reuse the failure presentation`() {
        val view = RuntimeStatusPresenter.localFailure(R.string.error_page_load_failed, "net::ERR")

        assertEquals(R.string.error_page_load_failed, view.titleRes)
        assertEquals(UiText.Raw("net::ERR"), view.detail)
        assertTrue(view.showRetry)
        assertEquals(KaomojiMood.SAD, view.mood)
    }

    @Test
    fun `local failures drop a blank detail`() {
        assertNull(RuntimeStatusPresenter.localFailure(R.string.error_secure_channel, "  ").detail)
        assertNull(RuntimeStatusPresenter.localFailure(R.string.error_secure_channel, null).detail)
    }

    @Test
    fun `the notification shares stage wording with the panel`() {
        val state = RuntimeState.Preparing(PreparationStage.MIGRATING_DATA, progress = 10)
        val panelStage = RuntimeStatusPresenter.present(state).stage
        val notification = RuntimeStatusPresenter.notification(state)

        assertEquals(
            UiText.of(R.string.notification_preparing, UiText.of(R.string.stage_migrating_data)),
            notification,
        )
        assertEquals(UiText.of(R.string.stage_migrating_data), panelStage)
    }

    @Test
    fun `the notification covers every state`() {
        val states =
            listOf(
                RuntimeState.Idle,
                RuntimeState.Stopping,
                RuntimeState.Preparing(PreparationStage.RECOVERING),
                RuntimeState.Starting(1, 3),
                RuntimeState.Ready(RuntimeEndpoint(8000, "id")),
                RuntimeState.Failed(RuntimeFailure("PROCESS_EXITED", "boom", retryable = true)),
            )
        val texts = states.map(RuntimeStatusPresenter::notification)

        assertEquals(states.size, texts.distinct().size)
    }

    @Test
    fun `the busy panel is indeterminate and offers no retry`() {
        val view =
            RuntimeStatusPresenter.busy(
                stageRes = R.string.stage_verifying_backup,
                detailRes = R.string.detail_verifying_backup,
            )

        assertEquals(R.string.status_praying, view.titleRes)
        assertNull(view.progress)
        assertTrue(view.showProgress)
        assertFalse(view.showRetry)
    }
}
