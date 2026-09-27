package org.akagi.mobile.engine

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable
import org.json.JSONArray
import org.json.JSONObject

/**
 * A complete local Mortal session. Call from one ordered worker queue, never the
 * main thread. Only the active model is retained. Lookahead clones native state
 * and shares the existing ONNX session, so it cannot change the real game.
 */
class MortalSession(context: Context, private val models: ModelRepository = ModelRepository(context)) : Closeable {
    private val assets = context.applicationContext.assets
    private var model: ModelRuntime? = null
    private var handle = 0L
    private var players = 4
    private var closed = false

    private fun backgroundOnly() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Mortal inference must run on a worker thread" }
        check(!closed) { "MortalSession is closed" }
    }

    private fun loadModel(playerCount: Int): ModelRuntime {
        val source = models.source(playerCount)
        model?.takeIf { it.source.identity == source.identity }?.let { return it }
        model?.close()
        model = null
        return ModelRuntime.open(models, source).also { model = it }
    }

    private fun infer(observation: FloatArray, bits: Long, playerCount: Int): FloatArray =
        loadModel(playerCount).infer(observation, bits)

    /** A change in the other mode must not invalidate this mode's live state or model. */
    @Synchronized
    fun modelChanged(playerCount: Int): Boolean {
        backgroundOnly()
        if (model?.source?.players == playerCount) {
            model?.close()
            model = null
        }
        return handle != 0L && players == playerCount
    }

    private fun inferState(nativeHandle: Long, snapshot: JSONObject): JSONObject {
        check(snapshot.getBoolean("can_act"))
        val normal = infer(NativeMortal.observation(nativeHandle, false), snapshot.getLong("mask_bits"), players)
        val kan = if (snapshot.getBoolean("kan_select")) {
            infer(NativeMortal.observation(nativeHandle, true), snapshot.getLong("kan_mask_bits"), players)
        } else FloatArray(0)
        return JSONObject(NativeMortal.resolve(nativeHandle, normal, kan))
    }

    /** Returns advice only at a decision; suppressed replay events just update rules. */
    @Synchronized
    fun acceptMjai(json: String): EngineAdvice? {
        backgroundOnly()
        val started = SystemClock.elapsedRealtime()
        try {
            val event = JSONObject(json)
            if (event.getString("type") == "start_game") startGame(event)
            check(handle != 0L) { "Waiting for a verified game start or reconnection replay" }
            val snapshot = JSONObject(NativeMortal.accept(handle, json))
            return evaluatePending(snapshot, started)
        } catch (error: Throwable) {
            resetState()
            throw error
        }
    }

    /** Apply one source action (or replay) completely before inferring once. */
    @Synchronized
    fun acceptBatch(events: List<String>): EngineAdvice? {
        backgroundOnly()
        if (events.isEmpty()) return null
        val started = SystemClock.elapsedRealtime()
        try {
            require(events.size <= 4096 && events.sumOf { it.length.toLong() } <= 8L * 1024 * 1024) {
                "The game event batch is too large"
            }
            val parsed = events.map(::JSONObject)
            require(parsed.drop(1).none { it.getString("type") == "start_game" }) {
                "A new game must begin at the start of its event batch"
            }
            if (parsed.first().getString("type") == "start_game") startGame(parsed.first())
            check(handle != 0L) { "Waiting for a verified game start or reconnection replay" }
            val snapshot = JSONObject(NativeMortal.acceptBatch(handle, JSONArray(parsed).toString()))
            return evaluatePending(snapshot, started)
        } catch (error: Throwable) {
            resetState()
            throw error
        }
    }

    private fun startGame(event: JSONObject) {
        resetState()
        players = event.optInt("players", if (event.optBoolean("is_3p", false)) 3 else 4)
        require(players == 3 || players == 4) { "Unsupported player count" }
        val seat = event.getInt("id")
        models.checkNativeCompatibility(players)
        handle = NativeMortal.create(seat, players)
    }

    /** Re-run inference only if the current verified state still has a decision. */
    @Synchronized
    fun recomputePending(): EngineAdvice? {
        backgroundOnly()
        if (handle == 0L) return null
        val started = SystemClock.elapsedRealtime()
        return try {
            evaluatePending(JSONObject(NativeMortal.snapshot(handle)), started)
        } catch (error: Throwable) {
            resetState()
            throw error
        }
    }

    private fun evaluatePending(snapshot: JSONObject, started: Long): EngineAdvice? {
        if (!snapshot.getBoolean("can_act")) return null
        val passIndex = if (players == 4) 45 else 43
        if (snapshot.getLong("mask_bits") == (1L shl passIndex)) return null
        val result = inferState(handle, snapshot)
        val best = parseAction(result.getJSONObject("recommended"))
        val alternatives = result.getJSONArray("alternatives").objects().map(::parseAction)
        // Desktop MortalBot looks ahead whenever riichi is in the top three.
        val reachDiscard = if (alternatives.take(3).any { it.type == "reach" }) {
            val fork = NativeMortal.forkReach(handle)
            try {
                val forkState = JSONObject(NativeMortal.snapshot(fork))
                parseAction(inferState(fork, forkState).getJSONObject("recommended"))
            } finally { NativeMortal.destroy(fork) }
        } else null
        return EngineAdvice(
            recommended = best,
            alternatives = alternatives,
            shanten = result.getInt("shanten"),
            furiten = result.getBoolean("at_furiten"),
            latencyMs = SystemClock.elapsedRealtime() - started,
            playerCount = players,
            reachDiscard = reachDiscard,
            legalMask = result.getLong("legal_mask"),
            agariGuardApplied = result.optBoolean("agari_guard_applied", false),
        )
    }

    /** Executes the real exported models on CI-generated desktop observations. */
    @Synchronized
    fun modelCheck(): ModelCheckResult {
        backgroundOnly()
        val started = SystemClock.elapsedRealtime()
        return try {
            var count = 0
            for (playerCount in listOf(4, 3)) {
                val runtime = loadModel(playerCount)
                count += models.validateReferences(runtime.source, runtime)
            }
            check(count >= 2) { "Missing real-model reference cases" }
            ModelCheckResult(true, "$count desktop reference observations passed on local CPU inference.", SystemClock.elapsedRealtime() - started)
        } catch (error: Exception) {
            ModelCheckResult(false, error.message ?: "Local model check failed", SystemClock.elapsedRealtime() - started)
        }
    }

    /** Isolated saved trace; never replaces or advances the active live session. */
    fun offlineReplay(playerCount: Int = 4): EngineAdvice {
        backgroundOnly()
        require(playerCount == 3 || playerCount == 4)
        return MortalSessionHolder.replay(assets.open("models/smoke_${playerCount}p.jsonl").bufferedReader().use { it.readLines() }, this)
    }

    // The context-free replay temporarily swaps only the native handle under the
    // session lock. Model resources remain shared; restoration happens on failure.
    private object MortalSessionHolder {
        fun replay(lines: List<String>, owner: MortalSession): EngineAdvice = synchronized(owner) {
            val savedHandle = owner.handle
            val savedPlayers = owner.players
            owner.handle = 0L
            try {
                var advice: EngineAdvice? = null
                for (line in lines.filter { it.isNotBlank() }) owner.acceptMjai(line)?.let { advice = it }
                checkNotNull(advice) { "The offline trace produced no decision" }
            } finally {
                owner.resetState()
                owner.handle = savedHandle
                owner.players = savedPlayers
            }
        }
    }

    private fun resetState() {
        if (handle != 0L) NativeMortal.destroy(handle)
        handle = 0L
    }

    @Synchronized
    fun reset() { resetState() }

    @Synchronized
    override fun close() {
        if (closed) return
        resetState()
        model?.close()
        model = null
        closed = true
        // OrtEnvironment is process-wide and may be used by another session.
    }

    private fun parseAction(value: JSONObject): EngineAction {
        val event = value.getJSONObject("event")
        val consumed = event.optJSONArray("consumed")?.let { array -> List(array.length()) { array.getString(it) } }.orEmpty()
        return EngineAction(
            index = value.getInt("index"), type = event.getString("type"),
            tile = if (event.has("pai")) event.getString("pai") else consumed.firstOrNull(),
            consumed = consumed, target = if (event.has("target")) event.getInt("target") else null,
            score = value.getDouble("score").toFloat(), eventJson = event.toString(),
        )
    }

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
}
