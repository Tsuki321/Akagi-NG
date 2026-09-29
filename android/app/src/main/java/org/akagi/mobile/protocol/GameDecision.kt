package org.akagi.mobile.protocol

import org.akagi.mobile.engine.EngineAction
import org.json.JSONArray
import org.json.JSONObject

/** A move belongs to the socket and game revision that offered these operations. */
data class GameDecision(
    val generation: String,
    val connectionId: String,
    val revision: Long,
    val seat: Int,
    val hand: List<String>,
    val drawn: String?,
    val operations: List<GameOperation>,
    val timeLimitMs: Long,
) {
    val id: String get() = "$generation/$connectionId/$revision"

    fun plan(action: EngineAction, reachDiscard: EngineAction? = null): AssistancePlan? = runCatching {
        val event = JSONObject(action.eventJson)
        require(event.optInt("actor", seat) == seat)
        require(hand.isNotEmpty() && hand.size <= 14)
        require(timeLimitMs > 0)
        val reaction = operations.any { it.type in setOf(2, 3, 5, 9) }
        val request = JSONObject().put("method", if (reaction) "inputChiPengGang" else "inputOperation")
        val selected = mutableListOf<Int>()
        fun operation(type: Int) = requireNotNull(operations.singleOrNull { it.type == type })
        fun select(tiles: List<String>): List<Int> {
            val used = mutableSetOf<Int>()
            return tiles.map { tile ->
                val index = hand.indices.firstOrNull { it !in used && hand[it] == tile }
                requireNotNull(index).also { used += it }
            }
        }
        fun discard(move: EngineAction, riichi: Boolean) {
            require(!reaction && move.type == "dahai")
            val tile = requireNotNull(move.tile)
            val drawnDiscard = JSONObject(move.eventJson).optBoolean("tsumogiri", false)
            val index = if (drawnDiscard) {
                require(drawn == tile && hand.last() == tile)
                hand.lastIndex
            } else {
                requireNotNull(hand.indices.firstOrNull { hand[it] == tile && (drawn == null || it < hand.lastIndex) })
            }
            val op = operation(if (riichi) 7 else 1)
            val listed = op.combinations.flatMap { it.split('|') }.map(MjaiAdapter::tile)
            // Discard combinations prohibit kuikae; riichi combinations list allowed discards.
            if (riichi) require(listed.any { it.removeSuffix("r") == tile.removeSuffix("r") })
            else require(listed.none { it.removeSuffix("r") == tile.removeSuffix("r") })
            request.put("type", op.type).put("tile", soulTile(tile)).put("moqie", drawnDiscard)
            selected += index
        }
        fun combination(type: Int, tiles: List<String>, singleTile: String? = null) {
            val op = operation(type)
            val matches = op.combinations.mapIndexedNotNull { index, value ->
                val candidate = value.split('|').map(MjaiAdapter::tile)
                val matches = candidate.sorted() == tiles.sorted() ||
                    (singleTile != null && candidate.size == 1 &&
                        candidate.single().removeSuffix("r") == singleTile.removeSuffix("r"))
                index.takeIf { matches }
            }
            require(matches.size == 1)
            request.put("type", type).put("index", matches.single())
        }
        var tile = action.tile
        val label = when (action.type) {
            "dahai" -> { discard(action, false); "Discard" }
            "reach" -> {
                val move = requireNotNull(reachDiscard)
                discard(move, true)
                tile = move.tile
                "Riichi · discard"
            }
            "chi", "pon", "daiminkan" -> {
                require(reaction)
                require(action.consumed.size == if (action.type == "daiminkan") 3 else 2)
                combination(when (action.type) { "chi" -> 2; "pon" -> 3; else -> 5 }, action.consumed)
                selected += select(action.consumed)
                action.displayLabel()
            }
            "ankan" -> {
                require(!reaction && action.consumed.size == 4)
                combination(4, action.consumed, action.consumed.first())
                selected += select(action.consumed)
                "Closed kan"
            }
            "kakan" -> {
                require(!reaction && action.consumed.size == 3)
                val added = requireNotNull(action.tile)
                combination(6, action.consumed + added, added)
                selected += select(listOf(added))
                "Added kan"
            }
            "nukidora" -> {
                require(!reaction)
                operation(11)
                val drawnNorth = drawn == "N"
                selected += if (drawnNorth) hand.lastIndex else select(listOf("N")).single()
                request.put("type", 11).put("moqie", drawnNorth)
                tile = "N"
                "Kita"
            }
            "hora" -> {
                val selfDraw = action.target == seat
                require(reaction != selfDraw)
                operation(if (selfDraw) 8 else 9)
                request.put("type", if (selfDraw) 8 else 9).put("index", 0)
                if (selfDraw && drawn != null) selected += hand.lastIndex
                if (selfDraw) "Tsumo" else "Ron"
            }
            "ryukyoku" -> {
                require(!reaction)
                operation(10)
                request.put("type", 10).put("index", 0)
                "Abortive draw"
            }
            "none" -> {
                // Cancelling optional self-turn actions does not discard a tile.
                require(reaction || operations.none { it.type == 1 || it.type == 7 })
                request.put("cancel_operation", true)
                "Pass"
            }
            else -> error("Unsupported operation")
        }
        AssistancePlan(id, generation, connectionId, revision, hand, selected, label, tile, request.toString(), timeLimitMs)
    }.getOrNull()

    companion object {
        fun soulTile(tile: String): String = when (tile) {
            "E" -> "1z"; "S" -> "2z"; "W" -> "3z"; "N" -> "4z"
            "P" -> "5z"; "F" -> "6z"; "C" -> "7z"
            else -> if (tile.endsWith("r")) "0${tile[1]}" else tile
        }
    }
}

data class GameOperation(val type: Int, val combinations: List<String>)

data class AssistancePlan(
    val id: String,
    val generation: String,
    val connectionId: String,
    val revision: Long,
    val hand: List<String>,
    val tileIndices: List<Int>,
    val label: String,
    val tile: String?,
    val requestJson: String,
    val timeLimitMs: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("generation", generation).put("connectionId", connectionId)
        .put("revision", revision).put("hand", JSONArray(hand)).put("tileIndices", JSONArray(tileIndices))
        .put("label", label).put("tile", tile ?: JSONObject.NULL)
        .put("request", JSONObject(requestJson)).put("timeLimitMs", timeLimitMs)
}
