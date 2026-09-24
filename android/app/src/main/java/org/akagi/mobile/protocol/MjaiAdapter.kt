package org.akagi.mobile.protocol

import org.json.JSONArray
import org.json.JSONObject

internal data class GameIdentity(
    val account: Long,
    val uuid: String,
    val seat: Int,
    val playerCount: Int,
    val redFives: Boolean = true,
)

/** Stateful port of akagi_ng.bridge.majsoul.bridge, with validated tile ownership. */
internal class MjaiAdapter(val identity: GameIdentity) {
    private val hand = mutableListOf<String>()
    private var drawn: String? = null
    private var doras = emptyList<String>()
    private val pons = Array(4) { mutableMapOf<String, List<String>>() }
    var hasRound: Boolean = false
        private set

    fun startGame(): JSONObject = event("start_game", "id" to identity.seat, "is_3p" to (identity.playerCount == 3))

    fun acceptsOperation(action: LiqiAction): Boolean {
        val operation = action.data.optJSONObject("operation") ?: return false
        return operation.optInt("seat", -1) == identity.seat &&
            (operation.optJSONArray("operationList")?.length() ?: 0) > 0
    }

    fun apply(action: LiqiAction): List<JSONObject> {
        val data = action.data
        val result = mutableListOf<JSONObject>()
        if (action.name !in setOf("ActionMJStart", "ActionNewRound")) {
            requireProtocol(hasRound, "Game action arrived before a complete round")
        }
        when (action.name) {
            "ActionMJStart" -> Unit
            "ActionNewRound" -> result.addAll(newRound(data))
            "ActionDealTile" -> {
                val actor = actor(data)
                val tile = data.optString("tile").let { if (it.isEmpty()) "?" else tile(it) }
                if (actor == identity.seat) {
                    requireProtocol(tile != "?", "Local draw is hidden")
                    requireProtocol(drawn == null, "A local draw was not consumed")
                    drawn = tile
                }
                result += event("tsumo", "actor" to actor, "pai" to tile)
            }
            "ActionDiscardTile" -> {
                val actor = actor(data)
                val pai = tile(data.getString("tile"))
                val tsumogiri = data.getBoolean("moqie")
                val reach = data.optBoolean("isLiqi") || data.optBoolean("isWliqi")
                if (reach) result += event("reach", "actor" to actor)
                result += event("dahai", "actor" to actor, "pai" to pai, "tsumogiri" to tsumogiri)
                if (actor == identity.seat) {
                    if (tsumogiri) {
                        // The dealer's initial fourteen tiles have no distinct draw in Liqi.
                        // Follow the reference's sorted split; remove the observed tile exactly.
                        if (drawn == pai) drawn = null else {
                            remove(pai)
                            saveDraw()
                        }
                    } else {
                        if (hand.contains(pai)) remove(pai) else if (drawn == pai) drawn = null
                        else throw ProtocolException("Discarded tile is absent from the local hand")
                        saveDraw()
                    }
                }
                // This ordering deliberately matches the existing desktop MJAI bridge.
                if (reach) result += event("reach_accepted", "actor" to actor)
            }
            "ActionChiPengGang" -> result += openMeld(data)
            "ActionAnGangAddGang" -> result += closedOrAddedKan(data)
            "ActionBaBei" -> {
                requireProtocol(identity.playerCount == 3, "Kita outside a three-player game")
                val actor = actor(data)
                if (actor == identity.seat) {
                    if (hand.contains("N")) remove("N") else if (drawn == "N") drawn = null
                    else throw ProtocolException("Kita tile is absent from the local hand")
                    saveDraw()
                }
                result += event("nukidora", "actor" to actor, "pai" to "N")
            }
            "ActionHule", "ActionNoTile", "ActionLiuJu" -> {
                hasRound = false
                return listOf(event("end_kyoku"))
            }
            else -> throw ProtocolException("Unsupported game action ${action.name}")
        }
        val reportedDoras = data.strings("doras").map(::tile)
        if (reportedDoras.isNotEmpty()) {
            requireProtocol(reportedDoras.size >= doras.size && reportedDoras.take(doras.size) == doras,
                "Dora history changed without a new round")
            reportedDoras.drop(doras.size).forEach { result += event("dora", "dora_marker" to it) }
            doras = reportedDoras.toList()
        }
        return result
    }

    private fun newRound(data: JSONObject): List<JSONObject> {
        val tiles = data.strings("tiles").map(::tile).sortedBy(::tileOrder)
        requireProtocol(tiles.size == 13 || tiles.size == 14, "Incomplete starting hand")
        val dealer = data.getInt("ju")
        requireProtocol(dealer in 0 until identity.playerCount, "Invalid dealer seat")
        requireProtocol(tiles.size != 14 || dealer == identity.seat, "Fourteen tiles for a non-dealer")
        val winds = listOf("E", "S", "W", "N")
        val wind = data.getInt("chang")
        requireProtocol(wind in winds.indices, "Invalid round wind")
        var scores = data.ints("scores")
        requireProtocol(scores.size == identity.playerCount, "Score count does not match player count")
        if (identity.playerCount == 3) scores = scores + 0
        val initialDoras = data.strings("doras").ifEmpty {
            data.optString("dora").takeIf { it.isNotEmpty() }?.let(::listOf) ?: emptyList()
        }
        requireProtocol(initialDoras.isNotEmpty(), "Round has no dora indicator")
        doras = listOf(tile(initialDoras.first()))
        pons.forEach { it.clear() }
        hand.clear()
        hand.addAll(tiles.take(13))
        drawn = tiles.getOrNull(13)
        hasRound = true
        val hands = List(4) { seat -> if (seat == identity.seat) hand.toList() else List(13) { "?" } }
        val result = mutableListOf(event("start_kyoku",
            "bakaze" to winds[wind], "dora_marker" to doras.first(), "kyoku" to (dealer + 1),
            "honba" to data.getInt("ben"), "kyotaku" to data.getInt("liqibang"), "oya" to dealer,
            "scores" to scores, "tehais" to hands))
        drawn?.let { result += event("tsumo", "actor" to identity.seat, "pai" to it) }
        return result
    }

    private fun openMeld(data: JSONObject): JSONObject {
        val actor = actor(data)
        val tiles = data.strings("tiles").map(::tile)
        val froms = data.ints("froms")
        val type = when (data.getInt("type")) {
            0 -> "chi"
            1 -> "pon"
            2 -> "daiminkan"
            else -> throw ProtocolException("Unknown open meld type")
        }
        requireProtocol(type != "chi" || identity.playerCount == 4, "Chi in a three-player game")
        requireProtocol(tiles.size == froms.size && tiles.size == (if (type == "daiminkan") 4 else 3),
            "Malformed open meld")
        val foreign = froms.indices.filter { froms[it] != actor }
        requireProtocol(foreign.size == 1, "Meld has no unique called tile")
        val called = foreign.single()
        val target = froms[called]
        requireProtocol(target in 0 until identity.playerCount, "Invalid meld target")
        val consumed = tiles.filterIndexed { index, _ -> index != called }
        if (actor == identity.seat) consumed.forEach(::remove)
        if (type == "pon") pons[actor][base(tiles[called])] = tiles.toList()
        return event(type, "actor" to actor, "target" to target, "pai" to tiles[called], "consumed" to consumed)
    }

    private fun closedOrAddedKan(data: JSONObject): JSONObject {
        val actor = actor(data)
        val pai = tile(data.getString("tiles"))
        val normalized = base(pai)
        return when (data.getInt("type")) {
            3 -> {
                val consumed = if (actor == identity.seat) {
                    saveDraw()
                    val exact = hand.filter { base(it) == normalized }.sortedBy(::tileOrder)
                    requireProtocol(exact.size == 4, "Concealed kan tiles are absent from the local hand")
                    exact.forEach(::remove)
                    exact
                } else {
                    MutableList(4) { normalized }.also {
                        if (identity.redFives && normalized.startsWith("5") && normalized.length == 2) it[0] = "${normalized}r"
                    }
                }
                event("ankan", "actor" to actor, "consumed" to consumed)
            }
            2 -> {
                val consumed = pons[actor].remove(normalized)
                    ?: throw ProtocolException("Added kan has no preceding pon")
                if (actor == identity.seat) {
                    saveDraw()
                    remove(pai)
                }
                // Sort red first just as the reference constructs its three consumed tiles.
                event("kakan", "actor" to actor, "pai" to pai, "consumed" to consumed.sortedBy(::tileOrder))
            }
            else -> throw ProtocolException("Unknown kan type")
        }
    }

    private fun actor(data: JSONObject): Int = data.getInt("seat").also {
        requireProtocol(it in 0 until identity.playerCount, "Invalid actor seat")
    }

    private fun remove(tile: String) {
        requireProtocol(hand.remove(tile), "Consumed tile is absent from the local hand")
    }

    private fun saveDraw() {
        drawn?.let { hand += it }
        drawn = null
        hand.sortBy(::tileOrder)
    }

    companion object {
        private val HONORS = listOf("E", "S", "W", "N", "P", "F", "C")

        fun tile(tile: String): String {
            requireProtocol(tile.length == 2, "Invalid Mahjong Soul tile")
            val rank = tile[0].digitToIntOrNull() ?: throw ProtocolException("Invalid tile rank")
            return when (tile[1]) {
                'm', 'p', 's' -> {
                    requireProtocol(rank in 0..9, "Invalid numbered tile")
                    if (rank == 0) "5${tile[1]}r" else tile
                }
                'z' -> HONORS.getOrNull(rank - 1) ?: throw ProtocolException("Invalid honor tile")
                else -> throw ProtocolException("Invalid tile suit")
            }
        }

        private fun base(tile: String): String = tile.removeSuffix("r")

        private fun tileOrder(tile: String): Int {
            val honor = HONORS.indexOf(tile)
            if (honor >= 0) return 60 + honor
            val suit = "mps".indexOf(tile[1])
            return suit * 20 + tile[0].digitToInt() * 2 - if (tile.endsWith("r")) 1 else 0
        }

        fun event(type: String, vararg fields: Pair<String, Any>): JSONObject = JSONObject().put("type", type).apply {
            fields.forEach { (key, value) -> put(key, jsonValue(value)) }
        }

        private fun jsonValue(value: Any): Any = when (value) {
            is List<*> -> JSONArray().also { array -> value.forEach { array.put(jsonValue(requireNotNull(it))) } }
            else -> value
        }
    }
}
