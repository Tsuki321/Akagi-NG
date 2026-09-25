package org.akagi.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.akagi.mobile.engine.EngineAdvice
import org.akagi.mobile.engine.MortalSession
import org.akagi.mobile.protocol.MahjongSoulProtocol
import org.akagi.mobile.ui.StatusTone
import org.akagi.mobile.ui.UiAdvice
import org.akagi.mobile.ui.UiAlternative
import org.akagi.mobile.ui.UiState
import org.akagi.mobile.ui.UiStatus
import org.json.JSONObject

/** One bounded worker owns both the protocol history and the native engine. */
class AnalysisViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()
    private val epoch = AtomicLong()
    private val visible = AtomicBoolean(true)
    private val disposed = AtomicBoolean(false)
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1024),
        { task -> Thread(task, "Akagi analysis").apply { isDaemon = true } },
    )
    private val protocol by lazy { MahjongSoulProtocol(application) }
    private val mortal by lazy { MortalSession(application) }

    fun capture(payload: String) {
        if (disposed.get()) return
        if (payload.length > 6 * 1024 * 1024) {
            resetSession("A game message was too large. Reload the game to resume advice.")
            return
        }
        val capturedEpoch = epoch.get()
        submit {
            if (capturedEpoch != epoch.get()) return@submit
            try {
                val update = protocol.accept(payload)
                if (update.sessionReset) mortal.reset()
                if (update.clearAdvice || update.sessionReset) mutable.update {
                    if (capturedEpoch != epoch.get()) it else it.copy(advice = null)
                }
                var newest: EngineAdvice? = null
                for (event in update.events) {
                    if (capturedEpoch != epoch.get()) return@submit
                    val input = if (visible.get()) event else JSONObject(event).put("can_act", false).toString()
                    mortal.acceptMjai(input)?.let { newest = it }
                }
                if (capturedEpoch != epoch.get()) return@submit
                val advice = newest?.takeIf { visible.get() }?.toUi()
                mutable.update { old ->
                    if (capturedEpoch != epoch.get()) return@update old
                    old.copy(
                        advice = if (!visible.get()) null else advice ?: old.advice,
                        status = UiStatus(
                            label = when {
                                advice != null -> "${newest!!.playerCount}-player Mortal"
                                update.synchronized -> "Following your game"
                                update.seat != null -> "Waiting for the next hand"
                                else -> "Waiting for game"
                            },
                            detail = update.status,
                            tone = if (update.synchronized) StatusTone.READY else StatusTone.CONNECTING,
                            captureActive = update.generation != null,
                            engineReady = old.status.engineReady || advice != null,
                        ),
                    )
                }
            } catch (failure: Throwable) {
                mortal.reset()
                protocol.invalidateEngine("Advice paused; waiting for a complete new hand or game reload")
                if (capturedEpoch == epoch.get()) {
                    mutable.update { if (capturedEpoch != epoch.get()) it else it.copy(advice = null, status = UiStatus(
                        label = "Advice paused",
                        detail = failure.message?.take(240) ?: "Waiting for a complete game state. Reload the game to retry.",
                        tone = StatusTone.ERROR,
                    )) }
                }
            }
        }
    }

    fun resetSession() = resetSession(null)

    private fun resetSession(reason: String?) {
        epoch.incrementAndGet()
        mutable.update { it.copy(advice = null, status = UiStatus(
            label = if (reason == null) "Waiting for game" else "Advice paused",
            detail = reason ?: "Sign in with your Yostar account to begin.",
            tone = if (reason == null) StatusTone.CONNECTING else StatusTone.ERROR,
        )) }
        submit { protocol.reset(); mortal.reset() }
    }

    fun setVisible(value: Boolean) {
        val wasVisible = visible.getAndSet(value)
        if (!value) mutable.update { it.copy(advice = null) }
        if (value && !wasVisible) {
            val capturedEpoch = epoch.get()
            submit {
                if (capturedEpoch != epoch.get() || !visible.get()) return@submit
                try {
                    val advice = mortal.recomputePending()?.toUi()
                    mutable.update { old ->
                        if (capturedEpoch != epoch.get() || !visible.get()) old else old.copy(advice = advice)
                    }
                } catch (_: Exception) {
                    mutable.update { old -> if (capturedEpoch != epoch.get()) old else old.copy(advice = null) }
                }
            }
        }
    }

    fun checkModels() {
        if (mutable.value.modelCheckRunning) return
        mutable.update { it.copy(modelCheckRunning = true, diagnostic = null) }
        submit {
            try {
                val result = mortal.modelCheck()
                mutable.update { it.copy(diagnostic = if (result.ok) "Local AI is ready. ${result.detail}" else "Local AI check failed: ${result.detail}") }
            } catch (failure: Throwable) {
                mutable.update { it.copy(diagnostic = "Local AI check failed: ${failure.message?.take(180)}") }
            } finally {
                mutable.update { it.copy(modelCheckRunning = false) }
            }
        }
    }

    /** The saved-hand result stays in settings, separate from live advice. */
    fun checkSavedHand() {
        if (mutable.value.localReplayRunning) return
        mutable.update { it.copy(localReplayRunning = true, diagnostic = null) }
        submit {
            try {
                val advice = mortal.offlineReplay()
                val action = advice.recommended
                mutable.update { it.copy(diagnostic = "Saved hand: ${action.displayLabel()} ${action.tile.orEmpty()} (${advice.latencyMs} ms on this device).") }
            } catch (failure: Throwable) {
                mutable.update { it.copy(diagnostic = "Saved-hand check failed: ${failure.message?.take(180)}") }
            } finally {
                mutable.update { it.copy(localReplayRunning = false) }
            }
        }
    }

    private fun submit(task: () -> Unit) {
        if (disposed.get()) return
        try {
            worker.execute(task)
        } catch (_: RejectedExecutionException) {
            if (disposed.get()) return
            epoch.incrementAndGet()
            worker.queue.clear()
            mutable.update { it.copy(advice = null, modelCheckRunning = false, localReplayRunning = false,
                status = UiStatus("Advice paused", "Game messages arrived too quickly. Reload the game to resynchronize.", StatusTone.ERROR)) }
            worker.execute { protocol.reset(); mortal.reset() }
        }
    }

    private fun EngineAdvice.toUi(): UiAdvice {
        val reach = reachDiscard?.tile?.let { "Then discard $it" }.orEmpty()
        val detail = listOf(reach, if (furiten) "Furiten" else "").filter(String::isNotEmpty).joinToString(" · ")
        return UiAdvice(
            action = recommended.displayLabel(), tile = recommended.tile, detail = detail,
            alternatives = alternatives.filter { it.index != recommended.index }.take(2).map {
                UiAlternative(it.displayLabel(), it.tile, it.score)
            }, latencyMs = latencyMs,
        )
    }

    override fun onCleared() {
        disposed.set(true)
        epoch.incrementAndGet()
        worker.queue.clear()
        worker.execute { mortal.close() }
        worker.shutdown()
        super.onCleared()
    }
}
