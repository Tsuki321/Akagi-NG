package org.akagi.mobile.engine

data class EngineAction(
    val index: Int,
    val type: String,
    val tile: String?,
    val consumed: List<String>,
    val target: Int?,
    val score: Float,
    val eventJson: String,
) {
    fun displayLabel(): String = when (type) {
        "dahai" -> "Discard"
        "reach" -> "Riichi"
        "chi" -> "Chi"
        "pon" -> "Pon"
        "daiminkan" -> "Open kan"
        "ankan" -> "Closed kan"
        "kakan" -> "Added kan"
        "nukidora" -> "Kita"
        "hora" -> "Win"
        "ryukyoku" -> "Abortive draw"
        "none" -> "Pass"
        else -> type
    }

    fun displayDetail(): String = when {
        consumed.isNotEmpty() -> "Use ${consumed.joinToString(" ")}" 
        type == "reach" -> "Declare riichi, then discard the suggested tile."
        type == "none" -> "Continue without calling."
        else -> ""
    }
}

data class EngineAdvice(
    val recommended: EngineAction,
    val alternatives: List<EngineAction>,
    val shanten: Int,
    val furiten: Boolean,
    val latencyMs: Long,
    val playerCount: Int,
    val reachDiscard: EngineAction?,
    val legalMask: Long,
    val agariGuardApplied: Boolean = false,
)

data class ModelCheckResult(val ok: Boolean, val detail: String, val latencyMs: Long)
