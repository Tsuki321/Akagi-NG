package org.akagi.mobile.protocol

import android.content.Context
import org.json.JSONObject
import java.util.Base64

/** Immutable result of one ordered browser capture message. Reset the engine before applying events. */
data class ProtocolUpdate(
    val events: List<String> = emptyList(),
    val status: String = "Waiting for Mahjong Soul",
    val sessionReset: Boolean = false,
    val synchronized: Boolean = false,
    val clearAdvice: Boolean = false,
    val seat: Int? = null,
    val playerCount: Int? = null,
    val generation: String? = null,
)

/**
 * One instance belongs to one game WebView. Call accept/reset on its single ordered worker.
 * The document-start hook supplies a global sequence per document, including control messages.
 * Trusted child documents have separate sequences; their socket identities include generation.
 */
class MahjongSoulProtocol(schemaJson: String) {
    constructor(context: Context) : this(context.assets.open("protocol/liqi.json").bufferedReader().use { it.readText() })

    private data class Document(var sequence: Long, val mainFrame: Boolean)
    private data class SocketKey(val generation: String, val connection: String)
    private data class Socket(val decoder: LiqiDecoder, val url: String)

    private val schema = LiqiSchema(schemaJson)
    private val documents = mutableMapOf<String, Document>()
    private val retiredDocuments = linkedSetOf<String>()
    private val sockets = mutableMapOf<SocketKey, Socket>()
    private var topGeneration: String? = null
    private var activeSocket: SocketKey? = null
    private var identity: GameIdentity? = null
    private var adapter: MjaiAdapter? = null
    private var pendingAccount: Long? = null
    private var pendingUuid: String? = null
    private var authenticated = false
    private var ready = false
    private var engineStarted = false
    private var history = mutableListOf<LiqiAction>()
    private var lastStep: Long? = null
    private var status = "Waiting for Mahjong Soul"
    private var resetThisUpdate = false
    private var clearThisUpdate = false

    fun reset() {
        documents.keys.forEach(::retire)
        documents.clear()
        sockets.clear()
        topGeneration = null
        clearGame()
        status = "Waiting for Mahjong Soul"
    }

    fun accept(rawCaptureJson: String): ProtocolUpdate {
        resetThisUpdate = false
        clearThisUpdate = false
        try {
            requireProtocol(rawCaptureJson.length <= MAX_CAPTURE_CHARS, "Capture message is too large")
            val capture = JSONObject(rawCaptureJson)
            val type = capture.getString("type")
            val generation = capture.getString("generation")
            requireProtocol(generation.isNotEmpty() && generation.length <= 256, "Invalid document generation")
            if (generation in retiredDocuments) return update()
            val mainFrame = capture.optBoolean("mainFrame", true)
            val sequence = capture.getLong("sequence")
            requireProtocol(sequence >= 1, "Invalid capture sequence")

            if (type == "capture_ready" && mainFrame && generation != topGeneration) {
                reset()
                topGeneration = generation
                resetEngine()
            }
            var document = documents[generation]
            if (document == null) {
                // A late frame from an unannounced top-level document cannot replace the active one.
                if (mainFrame && topGeneration != null && generation != topGeneration) return update()
                if (mainFrame && topGeneration == null) topGeneration = generation
                document = Document(0, mainFrame)
                documents[generation] = document
            }
            requireProtocol(document.mainFrame == mainFrame, "Document frame identity changed")
            if (sequence <= document.sequence) return update() // Duplicate or retired async delivery.
            val missing = sequence != document.sequence + 1
            document.sequence = sequence
            if (missing) invalidate("Capture messages were missed; waiting for a complete round replay")

            return when (type) {
                "capture_ready" -> update()
                "capture_error" -> {
                    invalidate("Browser capture interrupted; waiting for a complete round replay")
                    update()
                }
                "websocket_created" -> {
                    val key = socketKey(capture, generation)
                    requireProtocol(!sockets.containsKey(key), "Socket identity was reused")
                    sockets[key] = Socket(LiqiDecoder(schema), capture.getString("url"))
                    update()
                }
                "websocket_closed" -> {
                    val key = socketKey(capture, generation)
                    sockets.remove(key)
                    if (key == activeSocket) invalidate("Game disconnected; waiting for reconnection")
                    update()
                }
                "websocket" -> frame(capture, socketKey(capture, generation))
                else -> update()
            }
        } catch (_: Exception) {
            invalidate("Game data could not be verified; waiting for a complete round replay")
            return update()
        }
    }

    private fun frame(capture: JSONObject, key: SocketKey): ProtocolUpdate {
        val socket = sockets[key]
        if (socket == null) {
            invalidate("Socket opening was missed; reload the game to restore capture")
            return update()
        }
        requireProtocol(capture.optString("url", socket.url) == socket.url, "Socket URL changed")
        val binary = when {
            capture.has("binary") -> capture.getBoolean("binary")
            capture.has("isBinary") -> capture.getBoolean("isBinary")
            else -> capture.optInt("opcode", 1) == 2
        }
        if (!binary) {
            if (key == activeSocket) invalidate("Unexpected game frame; waiting for a complete round replay")
            return update()
        }
        val direction = capture.getString("direction")
        requireProtocol(direction == "inbound" || direction == "outbound", "Invalid websocket direction")
        val message = try {
            socket.decoder.decode(Base64.getDecoder().decode(capture.getString("data")), direction == "outbound")
        } catch (_: Exception) {
            // Other page sockets may use an entirely different protocol. The authenticated game may not.
            if (key == activeSocket) invalidate("Game protocol changed; waiting for a complete round replay")
            return update()
        }
        if (message.method == ".lq.FastTest.authGame" && message.kind == 2) {
            val account = message.data.getLong("accountId")
            val uuid = message.data.getString("gameUuid")
            requireProtocol(account > 0 && uuid.isNotEmpty(), "Missing game account identity")
            val previous = identity
            if (previous == null || previous.account != account || previous.uuid != uuid) clearGame()
            activeSocket = key
            pendingAccount = account
            pendingUuid = uuid
            authenticated = false
            ready = false
            resetEngine()
            status = "Authenticating game"
            return update()
        }
        if (key != activeSocket) return update()
        return when (message.method) {
            ".lq.FastTest.authGame" -> if (message.kind == 3) authenticate(message.data) else update()
            ".lq.FastTest.syncGame", ".lq.FastTest.enterGame" -> when (message.kind) {
                2 -> {
                    ready = false
                    resetEngine()
                    status = "Restoring round history"
                    update()
                }
                3 -> restore(message.data, socket.decoder)
                else -> update()
            }
            ".lq.ActionPrototype" -> {
                requireProtocol(message.kind == 1 && authenticated, "Game action arrived before authentication")
                liveAction(LiqiAction(message.data.getString("name"), message.data.getJSONObject("data"),
                    message.data.getLong("step")))
            }
            ".lq.NotifyGameEndResult", ".lq.NotifyGameTerminate" -> endGame()
            ".lq.FastTest.inputOperation", ".lq.FastTest.inputChiPengGang" -> {
                if (message.kind == 2) clearThisUpdate = true
                if (message.kind == 3) checkSuccess(message.data)
                update()
            }
            else -> update()
        }
    }

    private fun authenticate(data: JSONObject): ProtocolUpdate {
        checkSuccess(data)
        val account = pendingAccount ?: throw ProtocolException("Authentication response has no account")
        val uuid = pendingUuid ?: throw ProtocolException("Authentication response has no game")
        val seats = data.getJSONArray("seatList")
        requireProtocol(seats.length() == 3 || seats.length() == 4, "Unsupported player count")
        val matches = (0 until seats.length()).filter { seats.getLong(it) == account }
        requireProtocol(matches.size == 1, "Authenticated account has no unique player seat")
        val rules = data.optJSONObject("gameConfig")?.optJSONObject("mode")?.optJSONObject("detailRule")
        if (rules != null) {
            requireProtocol(VARIANT_RULES.none { rules.optInt(it, 0) != 0 }, "This game uses unsupported variant rules")
        }
        val next = GameIdentity(account, uuid, matches.single(), seats.length(),
            redFives = rules == null || rules.optInt("doraCount", 3) != 0)
        if (identity != null && identity != next) {
            history.clear()
            lastStep = null
        }
        identity = next
        adapter = MjaiAdapter(next)
        authenticated = true
        ready = false
        engineStarted = true
        status = "Waiting for the round"
        return update(render(listOf(requireNotNull(adapter).startGame()), sync = false, allowAction = false))
    }

    private fun liveAction(action: LiqiAction): ProtocolUpdate {
        val bridge = adapter ?: throw ProtocolException("Game identity is not known")
        if (action.name == "ActionMJStart") {
            clearThisUpdate = true
            lastStep = action.step
            return update()
        }
        if (action.name == "ActionNewRound") {
            if (history.firstOrNull()?.sameAs(action) == true) return update()
            clearThisUpdate = true
            // A new round is a complete, observed state boundary and can recover from a gap.
            val replacement = MjaiAdapter(bridge.identity)
            val events = replacement.apply(action)
            val prefix = if (!engineStarted) listOf(replacement.startGame()) else emptyList()
            adapter = replacement
            history = mutableListOf(action)
            lastStep = action.step
            ready = true
            engineStarted = true
            status = watchingStatus()
            return update(render(prefix, sync = false, allowAction = false) +
                render(events, sync = false, allowAction = replacement.acceptsOperation(action)))
        }
        val previous = history.findLast { it.step == action.step }
        if (previous != null && previous.sameAs(action)) return update()
        if (!ready) return update()
        clearThisUpdate = true
        requireProtocol(lastStep == null || action.step == requireNotNull(lastStep) + 1, "Game action sequence has a gap")
        val events = bridge.apply(action)
        history += action
        requireProtocol(history.size <= MAX_ROUND_ACTIONS, "Round history is too large")
        lastStep = action.step
        ready = bridge.hasRound
        status = if (ready) watchingStatus() else "Round ended"
        return update(render(events, sync = false, allowAction = bridge.acceptsOperation(action)))
    }

    private fun restore(data: JSONObject, decoder: LiqiDecoder): ProtocolUpdate {
        checkSuccess(data)
        requireProtocol(authenticated, "Round replay arrived before authentication")
        if (data.optBoolean("isEnd")) return endGame()
        val identity = identity ?: throw ProtocolException("Round replay has no player identity")
        val restore = data.optJSONObject("gameRestore") ?: run {
            status = "Waiting for the round"
            return update()
        }
        val players = restore.optJSONObject("snapshot")?.optJSONArray("players")
        if (players != null && players.length() > 0) {
            requireProtocol(players.length() == identity.playerCount, "Snapshot player count changed")
        }
        val raw = restore.optJSONArray("actions") ?: throw ProtocolException("Replay has no chronological actions")
        requireProtocol(raw.length() in 1..MAX_ROUND_ACTIONS, "Replay has no complete action history")
        val incoming = List(raw.length()) { decoder.action(raw.getJSONObject(it), useXor = false) }
        val newRound = incoming.indexOfLast { it.name == "ActionNewRound" }
        val replay = if (newRound >= 0) {
            incoming.drop(newRound)
        } else {
            requireProtocol(history.firstOrNull()?.name == "ActionNewRound", "Snapshot needs an earlier round history")
            val merged = history.toMutableList()
            for (action in incoming) {
                if (action.name == "ActionMJStart") continue
                val old = merged.findLast { it.step == action.step }
                if (old != null) requireProtocol(old.sameAs(action), "Replay contradicts an observed action")
                else {
                    requireProtocol(action.step == merged.last().step + 1, "Partial replay has a missing action")
                    merged += action
                }
            }
            merged
        }
        requireProtocol(replay.size <= MAX_ROUND_ACTIONS, "Replay is too large")
        for (index in 1 until replay.size) {
            requireProtocol(replay[index].step == replay[index - 1].step + 1, "Replay action sequence has a gap")
        }
        val replacement = MjaiAdapter(identity)
        val output = mutableListOf<String>()
        output += render(listOf(replacement.startGame()), sync = true, allowAction = false)
        for ((index, action) in replay.withIndex()) {
            val events = replacement.apply(action)
            val last = index == replay.lastIndex
            output += render(events, sync = !last, allowAction = last && replacement.acceptsOperation(action))
        }
        // Commit only after every action decoded, passed continuity checks, and replayed successfully.
        resetEngine()
        adapter = replacement
        history = replay.toMutableList()
        lastStep = replay.last().step
        ready = replacement.hasRound
        engineStarted = true
        status = if (ready) watchingStatus() else "Round ended"
        return update(output)
    }

    private fun endGame(): ProtocolUpdate {
        ready = false
        clearThisUpdate = true
        history.clear()
        lastStep = null
        status = "Game ended"
        return update(render(listOf(MjaiAdapter.event("end_game")), sync = false, allowAction = false))
    }

    private fun render(events: List<JSONObject>, sync: Boolean, allowAction: Boolean): List<String> {
        val decisionIndex = if (allowAction) events.indexOfLast { it.getString("type") in ACTIONABLE } else -1
        return events.mapIndexed { index, event ->
            event.put("sync", sync).put("can_act", !sync && index == decisionIndex).toString()
        }
    }

    private fun socketKey(capture: JSONObject, generation: String): SocketKey {
        val connection = capture.getString("connectionId")
        requireProtocol(connection.isNotEmpty() && connection.length <= 256, "Invalid socket identifier")
        return SocketKey(generation, connection)
    }

    private fun checkSuccess(data: JSONObject) {
        requireProtocol((data.optJSONObject("error")?.optInt("code") ?: 0) == 0, "Game request was rejected")
    }

    private fun watchingStatus() = "Watching ${requireNotNull(identity).playerCount}-player game"

    private fun clearGame() {
        activeSocket = null
        identity = null
        adapter = null
        pendingAccount = null
        pendingUuid = null
        authenticated = false
        ready = false
        engineStarted = false
        history.clear()
        lastStep = null
    }

    private fun resetEngine() {
        engineStarted = false
        resetThisUpdate = true
        clearThisUpdate = true
    }

    private fun invalidate(reason: String) {
        ready = false
        resetEngine()
        status = reason
    }

    private fun retire(generation: String) {
        retiredDocuments += generation
        while (retiredDocuments.size > 128) retiredDocuments.remove(retiredDocuments.first())
    }

    private fun update(events: List<String> = emptyList()) = ProtocolUpdate(events.toList(), status,
        resetThisUpdate, ready, clearThisUpdate, identity?.seat, identity?.playerCount, topGeneration)

    companion object {
        private const val MAX_CAPTURE_CHARS = 16 * 1024 * 1024
        private const val MAX_ROUND_ACTIONS = 4096
        private val ACTIONABLE = setOf("tsumo", "dahai", "chi", "pon", "daiminkan", "ankan", "kakan", "nukidora")
        private val VARIANT_RULES = setOf("guyiMode", "beginOpenMode", "jiuchaoMode", "muyuMode", "openHand",
            "xuezhandaodi", "huansanzhang", "chuanma", "revealDiscard", "fieldSpellMode", "zhanxing",
            "tianmingMode", "yongchangMode", "hunzhiyijiMode", "wanxiangxiuluoMode", "beishuizhizhanMode")
    }
}
