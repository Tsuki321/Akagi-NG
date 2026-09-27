package org.akagi.mobile

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.akagi.mobile.engine.EngineAdvice
import org.akagi.mobile.engine.MortalSession
import org.akagi.mobile.engine.ModelChoice
import org.akagi.mobile.engine.ModelRepository
import org.akagi.mobile.protocol.MahjongSoulProtocol
import org.akagi.mobile.ui.StatusTone
import org.akagi.mobile.ui.UiAdvice
import org.akagi.mobile.ui.UiAlternative
import org.akagi.mobile.ui.UiModel
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
    private val models = ModelRepository(application)
    private val mortal by lazy { MortalSession(application, models) }
    // Model copies and candidate inference must not stall the live protocol queue.
    private val imports = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Akagi model import").apply { isDaemon = true }
    }
    private val preparedModels = ConcurrentHashMap.newKeySet<ModelRepository.PreparedModel>()
    // A sent operation consumes the UI decision before its server echo arrives.
    // Native state alone cannot establish that this decision is still pending.
    private var decisionPending = false

    init {
        submit {
            for (players in listOf(4, 3)) {
                runCatching { models.choice(players) }.onSuccess { choice -> updateModel(players) { choice.toUi() } }
                runCatching { models.prune(players) }
            }
        }
    }

    fun importModel(players: Int, uri: Uri) {
        if (disposed.get() || players !in listOf(4, 3) || !beginModelChange(players, "Checking model…")) return
        imports.execute {
            try {
                val prepared = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    models.prepareImport(players, input)
                } ?: error("The selected file could not be opened")
                preparedModels.add(prepared)
                if (disposed.get()) {
                    prepared.close()
                    preparedModels.remove(prepared)
                    return@execute
                }
                // Bypass the capture overflow handler so a rejected import always frees its files.
                try {
                    worker.execute {
                        try {
                            prepared.use {
                                if (disposed.get()) return@use
                                val choice = try { models.apply(prepared) } catch (failure: Throwable) {
                                    modelChangeFailed(players, failure)
                                    return@use
                                }
                                finishModelChange(choice, "$players-player model updated.")
                            }
                        } finally { preparedModels.remove(prepared) }
                    }
                } catch (failure: RejectedExecutionException) {
                    prepared.close()
                    preparedModels.remove(prepared)
                    modelChangeFailed(players, failure)
                }
            } catch (failure: Throwable) {
                modelChangeFailed(players, failure)
            }
        }
    }

    fun useBundledModel(players: Int) {
        if (disposed.get() || players !in listOf(4, 3) || !beginModelChange(players, "Restoring bundled model…")) return
        submit {
            val choice = try { models.useBundled(players) } catch (failure: Throwable) {
                modelChangeFailed(players, failure)
                return@submit
            }
            finishModelChange(choice, "Bundled $players-player model restored.")
        }
    }

    private fun finishModelChange(choice: ModelChoice, message: String) {
        updateModel(choice.players) { choice.toUi(message) }
        runCatching { refreshChangedModel(choice.players) }.onFailure { failure ->
            mutable.update { it.copy(advice = null) }
            updateModel(choice.players) { it.copy(message = "Model saved. ${failure.message?.take(180) ?: "Reload the game to resume advice."}") }
        }
        runCatching { models.prune(choice.players) }
    }

    /** Keep the verified game state; the next inference loads only this mode's selection. */
    private fun refreshChangedModel(players: Int) {
        if (!mortal.modelChanged(players)) return
        mutable.update { it.copy(advice = null) }
        val capturedEpoch = epoch.get()
        // Queue behind capture messages already waiting so no old decision is republished.
        submit {
            if (capturedEpoch != epoch.get() || !visible.get() || !decisionPending) return@submit
            try {
                val advice = mortal.recomputePending()?.toUi()
                mutable.update { old ->
                    if (capturedEpoch != epoch.get() || !visible.get()) old else old.copy(advice = advice)
                }
            } catch (failure: Throwable) {
                decisionPending = false
                mortal.reset()
                protocol.invalidateEngine("Advice paused; waiting for a complete new hand or game reload")
                mutable.update { old ->
                    if (capturedEpoch != epoch.get()) old else old.copy(advice = null, status = UiStatus(
                        "Advice paused", failure.message?.take(180) ?: "The selected model could not run.", StatusTone.ERROR,
                    ))
                }
            }
        }
    }

    private fun beginModelChange(players: Int, message: String): Boolean {
        val current = if (players == 4) mutable.value.fourPlayerModel else mutable.value.threePlayerModel
        if (current.busy) return false
        updateModel(players) { it.copy(busy = true, message = message) }
        return true
    }

    private fun modelChangeFailed(players: Int, failure: Throwable) {
        updateModel(players) { it.copy(busy = false, message = "Model was not changed. ${failure.message?.take(200) ?: "The file could not be loaded."}") }
    }

    private fun ModelChoice.toUi(message: String? = notice) = UiModel(players, name, custom, message = message)

    private fun updateModel(players: Int, update: (UiModel) -> UiModel) {
        mutable.update { state ->
            if (players == 4) state.copy(fourPlayerModel = update(state.fourPlayerModel))
            else state.copy(threePlayerModel = update(state.threePlayerModel))
        }
    }

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
                if (update.clearAdvice || update.sessionReset) {
                    decisionPending = false
                    mutable.update { if (capturedEpoch != epoch.get()) it else it.copy(advice = null) }
                }
                val events = if (visible.get()) update.events else update.events.map { JSONObject(it).put("can_act", false).toString() }
                val newest = mortal.acceptBatch(events)
                if (newest != null) decisionPending = true
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
                decisionPending = false
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
        submit { decisionPending = false; protocol.reset(); mortal.reset() }
    }

    fun setVisible(value: Boolean) {
        val wasVisible = visible.getAndSet(value)
        if (!value) mutable.update { it.copy(advice = null) }
        if (value && !wasVisible) {
            val capturedEpoch = epoch.get()
            submit {
                if (capturedEpoch != epoch.get() || !visible.get() || !decisionPending) return@submit
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
            preparedModels.forEach { it.close() }
            preparedModels.clear()
            mutable.update { it.copy(advice = null, modelCheckRunning = false, localReplayRunning = false,
                fourPlayerModel = it.fourPlayerModel.copy(busy = false), threePlayerModel = it.threePlayerModel.copy(busy = false),
                status = UiStatus("Advice paused", "Game messages arrived too quickly. Reload the game to resynchronize.", StatusTone.ERROR)) }
            worker.execute { decisionPending = false; protocol.reset(); mortal.reset() }
        }
    }

    private fun EngineAdvice.toUi(): UiAdvice {
        val reach = if (recommended.type == "reach") reachDiscard?.tile?.let { "Then discard $it" }.orEmpty() else ""
        val consumed = if (recommended.consumed.isNotEmpty()) recommended.displayDetail() else ""
        val detail = listOf(reach, consumed, if (furiten) "Furiten" else "").filter(String::isNotEmpty).joinToString(" · ")
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
        imports.shutdownNow()
        worker.queue.clear()
        preparedModels.forEach { it.close() }
        preparedModels.clear()
        worker.execute { mortal.close() }
        worker.shutdown()
        super.onCleared()
    }
}
