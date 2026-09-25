package org.akagi.mobile.engine

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * A complete local Mortal session. Call from one ordered worker queue, never the
 * main thread. Only the active model is retained. Lookahead clones native state
 * and shares the existing ONNX session, so it cannot change the real game.
 */
class MortalSession(context: Context) : Closeable {
    private val assets = context.applicationContext.assets
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    private var model: OrtSession? = null
    private var modelPlayers = 0
    private var handle = 0L
    private var players = 4
    private var closed = false

    private fun backgroundOnly() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Mortal inference must run on a worker thread" }
        check(!closed) { "MortalSession is closed" }
    }

    private fun manifest(playerCount: Int): JSONObject = assets.open("models/mortal${playerCount}p.json").bufferedReader().use {
        JSONObject(it.readText())
    }

    private fun loadModel(playerCount: Int): OrtSession {
        model?.takeIf { modelPlayers == playerCount }?.let { return it }
        val description = manifest(playerCount)
        check(description.getInt("version") == 4 && description.getString("score_semantics") == "legal_masked_dueling_q") {
            "Unsupported model format"
        }
        val expectedChannels = if (playerCount == 4) 1012L else 775L
        val expectedActions = if (playerCount == 4) 46L else 44L
        val bytes = assets.open("models/" + description.getString("file")).use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(digest == description.getString("sha256")) { "The bundled model checksum does not match" }
        model?.close()
        model = null
        modelPlayers = 0
        val loaded = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // No optional accelerator: CPU is the validated baseline.
            environment.createSession(bytes, options)
        }
        try {
            val obs = loaded.inputInfo.getValue("obs").info as TensorInfo
            val mask = loaded.inputInfo.getValue("mask").info as TensorInfo
            val output = loaded.outputInfo.getValue("q_values").info as TensorInfo
            check(obs.type == OnnxJavaType.FLOAT && obs.shape.contentEquals(longArrayOf(1, expectedChannels, 34))) { "Observation dimensions do not match the encoder" }
            check(mask.type == OnnxJavaType.BOOL && mask.shape.contentEquals(longArrayOf(1, expectedActions))) { "Legal-mask dimensions do not match the encoder" }
            check(output.type == OnnxJavaType.FLOAT && output.shape.contentEquals(longArrayOf(1, expectedActions))) { "Unexpected score output" }
            model = loaded
            modelPlayers = playerCount
            return loaded
        } catch (error: Throwable) {
            loaded.close()
            throw error
        }
    }

    private fun infer(observation: FloatArray, bits: Long, playerCount: Int): FloatArray {
        val channels = if (playerCount == 4) 1012 else 775
        val actions = if (playerCount == 4) 46 else 44
        require(observation.size == channels * 34 && observation.all { it.isFinite() }) { "Invalid native observation" }
        require(bits != 0L && bits ushr actions == 0L) { "Invalid native action mask" }
        val mask = BooleanArray(actions) { bits and (1L shl it) != 0L }
        val activeModel = loadModel(playerCount)
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(observation), longArrayOf(1, channels.toLong(), 34)).use { obsTensor ->
            OnnxTensor.createTensor(environment, arrayOf(mask)).use { maskTensor ->
                activeModel.run(mapOf("obs" to obsTensor, "mask" to maskTensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val batch = result.get("q_values").orElseThrow().value as Array<FloatArray>
                    val scores = batch.single()
                    check(scores.size == actions)
                    check(scores.indices.all { i -> if (mask[i]) scores[i].isFinite() else scores[i] == Float.NEGATIVE_INFINITY }) {
                        "Local model returned invalid legal scores"
                    }
                    return scores
                }
            }
        }
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
            if (event.getString("type") == "start_game") {
                resetState()
                players = event.optInt("players", if (event.optBoolean("is_3p", false)) 3 else 4)
                require(players == 3 || players == 4) { "Unsupported player count" }
                val seat = event.getInt("id")
                check(manifest(players).getBoolean("native_compatible")) {
                    "The ${players}-player rules have not passed compatibility validation in this build"
                }
                handle = NativeMortal.create(seat, players)
            }
            check(handle != 0L) { "Waiting for a verified game start or reconnection replay" }
            val snapshot = JSONObject(NativeMortal.accept(handle, json))
            return evaluatePending(snapshot, started)
        } catch (error: Throwable) {
            resetState()
            throw error
        }
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
            val reference = assets.open("models/reference.json").bufferedReader().use { JSONObject(it.readText()) }
            var count = 0
            for (case in reference.getJSONArray("cases").objects()) {
                val playerCount = case.getInt("players")
                val raw = assets.open("models/" + case.getString("observation")).use { it.readBytes() }
                require(raw.size % 4 == 0)
                val floats = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val observation = FloatArray(floats.remaining()).also { floats.get(it) }
                val bits = case.getLong("mask_bits")
                val scores = infer(observation, bits, playerCount)
                val expected = case.getJSONArray("q_values")
                var best = -1
                for (i in scores.indices) {
                    if (bits and (1L shl i) == 0L) continue
                    val value = expected.getDouble(i).toFloat()
                    check(abs(scores[i] - value) <= 0.0003f + 0.0002f * abs(value)) { "Model parity failed for ${case.getString("name")}, action $i" }
                    if (best < 0 || scores[i] > scores[best]) best = i
                }
                check(best == case.getInt("argmax")) { "Model decision differs from desktop reference" }
                count += 1
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
        modelPlayers = 0
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
