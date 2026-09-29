package org.akagi.mobile.protocol

import org.akagi.mobile.engine.EngineAction
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GameDecisionTest {
    private fun move(type: String, tile: String? = null, consumed: List<String> = emptyList(),
        target: Int? = null, tsumogiri: Boolean = false, actor: Int = 0): EngineAction {
        val event = JSONObject().put("type", type).put("actor", actor).put("tsumogiri", tsumogiri)
        if (tile != null) event.put("pai", tile)
        return EngineAction(0, type, tile, consumed, target, 1f, event.toString())
    }

    private fun decision(hand: List<String>, vararg operations: GameOperation, drawn: String? = null) =
        GameDecision("document", "socket", 12, 0, hand, drawn, operations.toList(), 15000)

    @Test fun discardKeepsRedFiveAndDrawnTileIdentity() {
        val state = decision(listOf("5pr", "5p", "E", "5p"), GameOperation(1, emptyList()), drawn = "5p")
        val red = requireNotNull(state.plan(move("dahai", "5pr")))
        assertEquals(listOf(0), red.tileIndices)
        assertEquals("0p", JSONObject(red.requestJson).getString("tile"))
        val ordinary = requireNotNull(state.plan(move("dahai", "5p")))
        assertEquals(listOf(1), ordinary.tileIndices)
        val drawn = requireNotNull(state.plan(move("dahai", "5p", tsumogiri = true)))
        assertEquals(listOf(3), drawn.tileIndices)
        assertTrue(JSONObject(drawn.requestJson).getBoolean("moqie"))
        assertNull(state.plan(move("dahai", "5pr", tsumogiri = true)))
        assertNull(state.plan(move("dahai", "9s")))
        assertNull(state.plan(move("dahai", "5p", actor = 2)))
    }

    @Test fun riichiUsesTheLookaheadDiscardAndTheServerAllowedTiles() {
        val state = decision(listOf("1m", "5mr", "E"), GameOperation(1, emptyList()), GameOperation(7, listOf("5m")), drawn = "E")
        val result = requireNotNull(state.plan(move("reach"), move("dahai", "5mr")))
        val request = JSONObject(result.requestJson)
        assertEquals(7, request.getInt("type"))
        assertEquals("0m", request.getString("tile"))
        assertEquals(listOf(1), result.tileIndices)
        assertEquals("5mr", result.tile)
        assertNull(state.plan(move("reach")))
        assertNull(state.plan(move("reach"), move("dahai", "1m")))
    }

    @Test fun kuikaeRestrictionAlsoRejectsTheRedVariant() {
        val state = decision(listOf("5mr", "5m", "E"), GameOperation(1, listOf("5m")))
        assertNull(state.plan(move("dahai", "5mr")))
        assertNull(state.plan(move("dahai", "5m")))
        assertNotNull(state.plan(move("dahai", "E")))
    }

    @Test fun chiUsesTheExactOfferedCombinationAndHighlightsBothConsumedTiles() {
        val state = decision(listOf("3m", "4m", "5mr", "5m", "6m"),
            GameOperation(2, listOf("3m|4m", "4m|0m", "4m|5m", "0m|6m")))
        val plan = requireNotNull(state.plan(move("chi", "3m", listOf("4m", "5mr"), target = 3)))
        assertEquals("inputChiPengGang", JSONObject(plan.requestJson).getString("method"))
        assertEquals(1, JSONObject(plan.requestJson).getInt("index"))
        assertEquals(listOf(1, 2), plan.tileIndices)
        assertNull(state.plan(move("chi", "3m", listOf("5m", "6m"), target = 3)))
    }

    @Test fun ponDoesNotSubstituteTwoCopiesOfTheSameTileForARedFive() {
        val state = decision(listOf("5pr", "5p", "5p"), GameOperation(3, listOf("5p|5p", "0p|5p")))
        val plan = requireNotNull(state.plan(move("pon", "5p", listOf("5pr", "5p"), target = 2)))
        assertEquals(1, JSONObject(plan.requestJson).getInt("index"))
        assertEquals(listOf(0, 1), plan.tileIndices)
        assertNull(state.plan(move("pon", "5p", listOf("5pr", "5pr"), target = 2)))
    }

    @Test fun allKanTypesUseTheOfferedIndex() {
        val open = decision(listOf("E", "E", "E", "1m"), GameOperation(5, listOf("1z|1z|1z")))
        val openPlan = requireNotNull(open.plan(move("daiminkan", "E", listOf("E", "E", "E"), target = 1)))
        assertEquals(5, JSONObject(openPlan.requestJson).getInt("type"))
        assertEquals(listOf(0, 1, 2), openPlan.tileIndices)
        val closed = decision(listOf("5sr", "5s", "5s", "5s", "1m"),
            GameOperation(4, listOf("0s|5s|5s|5s")), GameOperation(1, emptyList()), drawn = "1m")
        val closedPlan = requireNotNull(closed.plan(move("ankan", consumed = listOf("5sr", "5s", "5s", "5s"))))
        assertEquals(listOf(0, 1, 2, 3), closedPlan.tileIndices)
        val added = decision(listOf("1m", "5pr"), GameOperation(6, listOf("0p|5p|5p|5p")), drawn = "5pr")
        val addedPlan = requireNotNull(added.plan(move("kakan", "5pr", listOf("5p", "5p", "5p"))))
        assertEquals(6, JSONObject(addedPlan.requestJson).getInt("type"))
        assertEquals(listOf(1), addedPlan.tileIndices)
    }

    @Test fun kitaPrefersTheDrawnNorthAndRequiresAnOfferedOperation() {
        val state = decision(listOf("N", "1m", "N"), GameOperation(11, emptyList()), drawn = "N")
        val plan = requireNotNull(state.plan(move("nukidora")))
        assertEquals(listOf(2), plan.tileIndices)
        assertTrue(JSONObject(plan.requestJson).getBoolean("moqie"))
        assertEquals("N", plan.tile)
        assertNull(state.copy(operations = listOf(GameOperation(1, emptyList()))).plan(move("nukidora")))
    }

    @Test fun ronTsumoPassAndAbortiveDrawUseTheCorrectInputMethod() {
        val reaction = decision(listOf("E"), GameOperation(9, emptyList()))
        val ron = requireNotNull(reaction.plan(move("hora", "1m", target = 2)))
        assertEquals(9, JSONObject(ron.requestJson).getInt("type"))
        assertEquals("inputChiPengGang", JSONObject(ron.requestJson).getString("method"))
        val pass = requireNotNull(reaction.plan(move("none")))
        assertTrue(JSONObject(pass.requestJson).getBoolean("cancel_operation"))
        val self = decision(listOf("E", "1m"), GameOperation(8, emptyList()), drawn = "1m")
        val tsumo = requireNotNull(self.plan(move("hora", "1m", target = 0)))
        assertEquals("inputOperation", JSONObject(tsumo.requestJson).getString("method"))
        assertEquals(8, JSONObject(tsumo.requestJson).getInt("type"))
        assertEquals(listOf(1), tsumo.tileIndices)
        assertNull(self.plan(move("hora", "1m", target = 1)))
        assertNull(self.copy(operations = listOf(GameOperation(1, emptyList()))).plan(move("none")))
        val abort = requireNotNull(self.copy(operations = listOf(GameOperation(10, emptyList()))).plan(move("ryukyoku")))
        assertEquals(10, JSONObject(abort.requestJson).getInt("type"))
    }

    @Test fun serializedPlanRetainsItsSocketRevisionAndPhysicalTilePositions() {
        val state = decision(listOf("E", "5mr"), GameOperation(1, emptyList()), drawn = "5mr")
        val plan = requireNotNull(state.plan(move("dahai", "5mr", tsumogiri = true)))
        val json = plan.toJson()
        assertEquals("document/socket/12", json.getString("id"))
        assertEquals(12L, json.getLong("revision"))
        assertEquals("socket", json.getString("connectionId"))
        assertEquals(JSONArray(listOf(1)).toString(), json.getJSONArray("tileIndices").toString())
    }
}
