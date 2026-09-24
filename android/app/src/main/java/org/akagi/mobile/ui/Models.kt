package org.akagi.mobile.ui

/** Only live, validated advice belongs here. A null advice clears every previous action. */
data class UiState(
    val status: UiStatus = UiStatus(),
    val advice: UiAdvice? = null,
    val modelCheckRunning: Boolean = false,
    val localReplayRunning: Boolean = false,
    val diagnostic: String? = null,
)

data class UiStatus(
    val label: String = "Waiting for game",
    val detail: String = "Sign in with your Yostar account to begin.",
    val tone: StatusTone = StatusTone.CONNECTING,
    val captureActive: Boolean = false,
    val engineReady: Boolean = false,
)

enum class StatusTone { CONNECTING, READY, BUSY, ERROR }

data class UiAdvice(
    val action: String,
    val tile: String? = null,
    val detail: String = "",
    val alternatives: List<UiAlternative> = emptyList(),
    val latencyMs: Long? = null,
)

data class UiAlternative(
    val action: String,
    val tile: String? = null,
    val score: Float? = null,
)
