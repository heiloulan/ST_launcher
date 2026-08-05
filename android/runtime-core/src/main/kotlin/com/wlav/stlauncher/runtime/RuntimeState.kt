package com.wlav.stlauncher.runtime

sealed interface RuntimeState {
    data object Idle : RuntimeState

    data class Preparing(
        val stage: PreparationStage,
        val progress: Int? = null,
    ) : RuntimeState

    data class Starting(
        val attempt: Int,
        val maximumAttempts: Int,
    ) : RuntimeState

    data class Ready(
        val endpoint: RuntimeEndpoint,
    ) : RuntimeState

    data class Failed(
        val failure: RuntimeFailure,
    ) : RuntimeState

    data object Stopping : RuntimeState
}

enum class PreparationStage {
    RECOVERING,
    MIGRATING_DATA,
    INSTALLING_RUNTIME,
    PREPARING_CONFIGURATION,
}

data class RuntimeEndpoint(
    val port: Int,
    val instanceId: String,
) {
    init {
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        require(instanceId.isNotBlank()) { "Instance ID must not be blank" }
    }

    val baseUrl: String = "http://127.0.0.1:$port"
}

data class RuntimeFailure(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val detail: String? = null,
)

fun interface PreparationReporter {
    fun report(
        stage: PreparationStage,
        progress: Int?,
    )
}
