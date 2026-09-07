package com.wlav.stlauncher.ui

import androidx.annotation.StringRes
import com.wlav.stlauncher.R
import com.wlav.stlauncher.runtime.PreparationStage
import com.wlav.stlauncher.runtime.RuntimeFailure
import com.wlav.stlauncher.runtime.RuntimeState

/** Everything the status panel needs in order to render one [RuntimeState]. */
data class StatusView(
    @StringRes val titleRes: Int,
    val stage: UiText?,
    val detail: UiText?,
    /** Determinate percentage, or `null` for an indeterminate bar. */
    val progress: Int?,
    val showProgress: Boolean,
    val showRetry: Boolean,
    val mood: KaomojiMood,
)

/**
 * The single mapping from [RuntimeState] to user-facing copy.
 *
 * Both the status panel and the foreground-service notification read from here,
 * which is what keeps the two from drifting apart — they previously carried
 * separate `PreparationStage.displayName()` implementations with different
 * wording for the same stage.
 */
object RuntimeStatusPresenter {
    fun present(state: RuntimeState): StatusView =
        when (state) {
            RuntimeState.Idle ->
                StatusView(
                    titleRes = R.string.status_idle,
                    stage = null,
                    detail = null,
                    progress = null,
                    showProgress = false,
                    showRetry = true,
                    mood = KaomojiMood.SLEEPING,
                )

            RuntimeState.Stopping ->
                StatusView(
                    titleRes = R.string.status_praying,
                    stage = UiText.of(R.string.stage_stopping),
                    detail = null,
                    progress = null,
                    showProgress = true,
                    showRetry = false,
                    mood = KaomojiMood.PRAYING,
                )

            is RuntimeState.Preparing ->
                StatusView(
                    titleRes = R.string.status_praying,
                    stage = UiText.of(stageLabel(state.stage)),
                    detail = state.progress?.let { UiText.of(R.string.status_percent, it) },
                    progress = state.progress,
                    showProgress = true,
                    showRetry = false,
                    mood = KaomojiMood.PRAYING,
                )

            is RuntimeState.Starting ->
                StatusView(
                    titleRes = R.string.status_praying,
                    stage = UiText.of(R.string.stage_starting),
                    detail =
                        UiText.of(
                            R.string.status_attempt,
                            state.attempt,
                            state.maximumAttempts,
                        ),
                    progress = null,
                    showProgress = true,
                    showRetry = false,
                    mood = KaomojiMood.PRAYING,
                )

            // The endpoint answered; what is left is the page load itself.
            is RuntimeState.Ready ->
                StatusView(
                    titleRes = R.string.status_praying,
                    stage = UiText.of(R.string.stage_loading_page),
                    detail = UiText.Raw(state.endpoint.baseUrl),
                    progress = null,
                    showProgress = true,
                    showRetry = false,
                    mood = KaomojiMood.HAPPY,
                )

            is RuntimeState.Failed -> present(state.failure)
        }

    fun present(failure: RuntimeFailure): StatusView =
        StatusView(
            titleRes = failureTitle(failure.code),
            stage = null,
            // The raw message is usually English and comes from deep in the
            // runtime, so it is demoted from the headline to the detail line.
            detail = failureDetail(failure)?.let { UiText.Raw(it) },
            progress = null,
            showProgress = false,
            showRetry = true,
            mood = KaomojiMood.SAD,
        )

    /** A locally raised problem that never reached the runtime state machine. */
    fun localFailure(
        @StringRes titleRes: Int,
        detail: String? = null,
    ): StatusView =
        StatusView(
            titleRes = titleRes,
            stage = null,
            detail = detail?.takeIf(String::isNotBlank)?.let { UiText.Raw(it) },
            progress = null,
            showProgress = false,
            showRetry = true,
            mood = KaomojiMood.SAD,
        )

    /** A transient panel shown while the Activity drives a long operation itself. */
    fun busy(
        @StringRes stageRes: Int,
        @StringRes detailRes: Int,
    ): StatusView =
        StatusView(
            titleRes = R.string.status_praying,
            stage = UiText.of(stageRes),
            detail = UiText.of(detailRes),
            progress = null,
            showProgress = true,
            showRetry = false,
            mood = KaomojiMood.PRAYING,
        )

    /** Notification body text. Shares the stage wording with the status panel. */
    fun notification(state: RuntimeState): UiText =
        when (state) {
            RuntimeState.Idle -> UiText.of(R.string.notification_idle)
            RuntimeState.Stopping -> UiText.of(R.string.notification_stopping)
            is RuntimeState.Preparing ->
                UiText.of(
                    R.string.notification_preparing,
                    UiText.of(stageLabel(state.stage)),
                )
            is RuntimeState.Starting ->
                UiText.of(
                    R.string.notification_starting,
                    state.attempt,
                    state.maximumAttempts,
                )
            is RuntimeState.Ready -> UiText.of(R.string.notification_ready)
            is RuntimeState.Failed -> UiText.of(R.string.notification_failed)
        }

    @StringRes
    fun stageLabel(stage: PreparationStage): Int =
        when (stage) {
            PreparationStage.RECOVERING -> R.string.stage_recovering
            PreparationStage.MIGRATING_DATA -> R.string.stage_migrating_data
            PreparationStage.INSTALLING_RUNTIME -> R.string.stage_installing_runtime
            PreparationStage.PREPARING_CONFIGURATION -> R.string.stage_preparing_configuration
        }

    /**
     * Maps a [RuntimeFailure.code] to a readable Chinese headline. These codes
     * were previously computed and then discarded, leaving users to read the
     * raw English message.
     */
    @StringRes
    fun failureTitle(code: String): Int =
        when (code) {
            "CANDIDATE_RUNTIME_FAILED" -> R.string.failure_candidate_runtime_failed
            "RUNTIME_ACTIVATION_FAILED" -> R.string.failure_runtime_activation_failed
            "PROCESS_EXITED_DURING_START" -> R.string.failure_process_exited_during_start
            "START_TIMEOUT" -> R.string.failure_start_timeout
            "NODE_LAUNCH_FAILED" -> R.string.failure_node_launch_failed
            "UNSUPPORTED_ABI" -> R.string.failure_unsupported_abi
            "NODE_RUNTIME_MISSING" -> R.string.failure_node_runtime_missing
            "RUNTIME_INCOMPLETE" -> R.string.failure_runtime_incomplete
            "DATA_MIGRATION_FAILED" -> R.string.failure_data_migration_failed
            "RUNTIME_INSTALL_FAILED" -> R.string.failure_runtime_install_failed
            "PROCESS_EXITED" -> R.string.failure_process_exited
            else -> R.string.failure_unknown
        }

    private fun failureDetail(failure: RuntimeFailure): String? =
        listOfNotNull(
            failure.message.takeIf(String::isNotBlank),
            failure.detail?.takeIf(String::isNotBlank),
        ).distinct()
            .joinToString("\n")
            .takeIf(String::isNotBlank)
}
