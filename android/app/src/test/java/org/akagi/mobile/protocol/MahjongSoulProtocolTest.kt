package org.akagi.mobile.protocol

import com.google.protobuf.ByteString
import com.google.protobuf.DynamicMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/** Real serialized Liqi -> desktop Python events, with transport fault/recovery checks. */
class MahjongSoulProtocolTest {
    @Test
    fun serializedTracesMatchTheDesktopReference() {
        val hash = MessageDigest.getInstance("SHA-256").digest(schemaJson.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(fixtures.getString("schemaSha256"), hash)
        for (case in fixtureCases()) {
            val protocol = MahjongSoulProtocol(schemaJson)
            val actual = captures(case).flatMap { protocol.accept(it.toString()).events }.map(::JSONObject)
                // A replay recreates the native session before restoring the current round.
                // That lifecycle reset has no counterpart in the desktop bridge event list.
                .filterNot { it.getString("type") == "start_game" && it.optBoolean("sync") }
                .map(::withoutInferenceFlags)
            val expected = case.getJSONArray("expected").objects().map(::withoutInferenceFlags)
            assertEquals("${case.getString("name")}: event count", expected.size, actual.size)
            for (index in expected.indices) {
                assertTrue("${case.getString("name")}, event $index\nExpected ${expected[index]}\nActual ${actual[index]}",
                    jsonEquivalent(expected[index], actual[index]))
            }
        }
    }

    @Test
    fun simultaneousSocketsHaveIndependentRequestIdentifiers() {
        val protocol = MahjongSoulProtocol(schemaJson)
        val updates = captures(case("four_player_red_reach")).map { protocol.accept(it.toString()) }
        val start = updates.flatMap { it.events }.map(::JSONObject).single { it.getString("type") == "start_game" }
        assertEquals(2, start.getInt("id"))
        assertFalse(start.getBoolean("is_3p"))
        assertEquals(2, updates.last().seat)
        assertEquals(4, updates.last().playerCount)
        assertTrue(updates.last().synchronized)
    }

    @Test
    fun inferenceRunsOnTheDecisionEventAndNotOnFollowingDoraAnnouncement() {
        val protocol = MahjongSoulProtocol(schemaJson)
        var decision: ProtocolUpdate? = null
        for (capture in captures(case("pon_red_kakan"))) {
            val update = protocol.accept(capture.toString())
            if (update.events.any { JSONObject(it).getString("type") == "dora" }) decision = update
        }
        val events = requireNotNull(decision).events.map(::JSONObject)
        assertEquals(listOf("tsumo", "dora"), events.map { it.getString("type") })
        assertTrue(events[0].getBoolean("can_act"))
        assertFalse(events[1].getBoolean("can_act"))
    }

    @Test
    fun reconnectReplaysTheCompleteRoundButInfersOnlyOnTheCurrentDecision() {
        val protocol = MahjongSoulProtocol(schemaJson)
        val update = captures(case("reconnect_history")).map { protocol.accept(it.toString()) }.last()
        val events = update.events.map(::JSONObject)
        assertTrue(update.sessionReset)
        assertTrue(update.synchronized)
        assertEquals(1, events.count { it.getBoolean("can_act") })
        assertEquals("tsumo", events.single { it.getBoolean("can_act") }.getString("type"))
        assertEquals("3s", events.single { it.getBoolean("can_act") }.getString("pai"))
        assertTrue(events.first().getBoolean("sync"))
        assertEquals("start_game", events.first().getString("type"))
        assertEquals(1, events.first().getInt("id"))
    }

    @Test
    fun capturedThreePlayerRoundUsesUnencryptedReplayPayloadAndPreservesFourteenTiles() {
        val protocol = MahjongSoulProtocol(schemaJson)
        val update = captures(case("captured_three_player_sync")).map { protocol.accept(it.toString()) }.last()
        assertTrue(update.synchronized)
        assertEquals(3, update.playerCount)
        val events = update.events.map(::JSONObject)
        val round = events.single { it.getString("type") == "start_kyoku" }
        val draw = events.single { it.getString("type") == "tsumo" }
        val hand = round.getJSONArray("tehais").getJSONArray(0)
        assertEquals(13, hand.length())
        assertEquals("C", draw.getString("pai"))
        assertEquals(4, round.getJSONArray("scores").length())
        assertEquals(0, round.getJSONArray("scores").getInt(3))
    }

    @Test
    fun aLostCaptureSuppressesAdviceUntilAnObservedNewRound() {
        val protocol = MahjongSoulProtocol(schemaJson)
        val original = captures(case("four_player_red_reach"))
        val dropped = original.first { it.optString("fixtureAction") == "ActionDiscardTile" }
        var last = ProtocolUpdate()
        var resetAfterLoss = false
        var afterLoss = false
        for (capture in original) {
            if (capture === dropped) {
                afterLoss = true
                continue
            }
            last = protocol.accept(capture.toString())
            if (afterLoss) {
                resetAfterLoss = resetAfterLoss || last.sessionReset
                assertFalse(last.synchronized)
                assertTrue(last.events.isEmpty())
            }
        }
        assertTrue(resetAfterLoss)
        val round = original.first { it.optString("fixtureAction") == "ActionNewRound" }
        val recovered = copy(round).put("sequence", original.last().getLong("sequence") + 1)
        // A genuinely new complete round, not a duplicated packet from the lost round.
        val changed = transformAction(recovered) { action ->
            val inner = xorPayload(action, "ActionNewRound")
            val next = inner.toBuilder().setField(inner.descriptorForType.findFieldByName("ben"), 1).build()
            action.toBuilder().setField(action.descriptorForType.findFieldByName("data"),
                ByteString.copyFrom(LiqiDecoder.xor(next.toByteArray()))).build()
        }
        last = protocol.accept(changed.toString())
        assertTrue(last.synchronized)
        assertEquals(listOf("start_game", "start_kyoku"), last.events.map { JSONObject(it).getString("type") })
    }

    @Test
    fun duplicateCaptureDoesNotAdvanceStateOrClearCurrentAdvice() {
        val protocol = MahjongSoulProtocol(schemaJson)
        val frames = captures(case("four_player_red_reach"))
        frames.forEach { protocol.accept(it.toString()) }
        val duplicate = protocol.accept(frames.last().toString())
        assertTrue(duplicate.synchronized)
        assertTrue(duplicate.events.isEmpty())
        assertFalse(duplicate.sessionReset)
        assertFalse(duplicate.clearAdvice)
    }

    @Test
    fun gameStepGapIsDetectedEvenWhenCaptureSequenceIsContinuous() {
        val protocol = MahjongSoulProtocol(schemaJson)
        val frames = captures(case("four_player_red_reach"))
        frames.dropLast(1).forEach { protocol.accept(it.toString()) }
        val gap = transformAction(frames.last()) { action ->
            action.toBuilder().setField(action.descriptorForType.findFieldByName("step"), 100).build()
        }
        val result = protocol.accept(gap.toString())
        assertFalse(result.synchronized)
        assertTrue(result.sessionReset)
        assertTrue(result.clearAdvice)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun lateFramesFromThePreviousDocumentCannotRestoreOldAdvice() {
        val protocol = MahjongSoulProtocol(schemaJson)
        val frames = captures(case("four_player_red_reach"))
        frames.forEach { protocol.accept(it.toString()) }
        val nextDocument = JSONObject().put("type", "capture_ready").put("generation", "replacement-document")
            .put("sequence", 1).put("mainFrame", true)
        val replaced = protocol.accept(nextDocument.toString())
        assertTrue(replaced.sessionReset)
        assertFalse(replaced.synchronized)
        val stale = copy(frames.last()).put("sequence", frames.last().getLong("sequence") + 1)
        val ignored = protocol.accept(stale.toString())
        assertEquals("replacement-document", ignored.generation)
        assertFalse(ignored.synchronized)
        assertTrue(ignored.events.isEmpty())
        assertEquals(null, ignored.seat)
    }

    @Test
    fun trustedChildFrameSocketsKeepSeparateSequencesWithoutResettingTheTopDocument() {
        val protocol = MahjongSoulProtocol(schemaJson)
        protocol.accept(JSONObject().put("type", "capture_ready").put("generation", "top")
            .put("mainFrame", true).put("sequence", 1).toString())
        var result = ProtocolUpdate()
        for (capture in captures(case("four_player_red_reach"))) {
            result = protocol.accept(copy(capture).put("mainFrame", false).toString())
        }
        assertTrue(result.synchronized)
        assertEquals("top", result.generation)
        assertEquals(2, result.seat)
        val unrelatedChild = JSONObject().put("type", "capture_ready").put("generation", "another-child")
            .put("mainFrame", false).put("sequence", 1)
        val unchanged = protocol.accept(unrelatedChild.toString())
        assertTrue(unchanged.synchronized)
        assertFalse(unchanged.sessionReset)
    }

    @Test
    fun snapshotWithoutRoundChronologyCannotFabricateAnObservation() {
        val protocol = MahjongSoulProtocol(schemaJson)
        val frames = captures(case("captured_three_player_sync"))
        frames.dropLast(1).forEach { protocol.accept(it.toString()) }
        val snapshotOnly = transformResponse(frames.last(), "ResSyncGame") { response ->
            val restoreField = response.descriptorForType.findFieldByName("game_restore")
            val restore = response.getField(restoreField) as DynamicMessage
            response.toBuilder().setField(restoreField,
                restore.toBuilder().clearField(restore.descriptorForType.findFieldByName("actions")).build()).build()
        }
        val result = protocol.accept(snapshotOnly.toString())
        assertFalse(result.synchronized)
        assertTrue(result.sessionReset)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun partialReplayCanReuseVerifiedHistoryWithoutApplyingADrawTwice() {
        val protocol = MahjongSoulProtocol(schemaJson)
        val frames = captures(case("reconnect_history"))
        frames.forEach { protocol.accept(it.toString()) }
        val sequence = frames.last().getLong("sequence")
        val request = frames.last { it.optString("fixtureMethod") == ".lq.FastTest.syncGame" &&
            it.optString("direction") == "outbound" }
        protocol.accept(copy(request).put("sequence", sequence + 1).toString())
        val response = transformResponse(copy(frames.last()).put("sequence", sequence + 2), "ResSyncGame") { value ->
            val restoreField = value.descriptorForType.findFieldByName("game_restore")
            val restore = value.getField(restoreField) as DynamicMessage
            val actionsField = restore.descriptorForType.findFieldByName("actions")
            val last = restore.getRepeatedField(actionsField, restore.getRepeatedFieldCount(actionsField) - 1)
            val delta = restore.toBuilder().clearField(actionsField).addRepeatedField(actionsField, last).build()
            value.toBuilder().setField(restoreField, delta).build()
        }
        val result = protocol.accept(response.toString())
        assertTrue(result.synchronized)
        assertTrue(result.sessionReset)
        assertEquals(1, result.events.count { JSONObject(it).getString("type") == "tsumo" })
        assertEquals(1, result.events.count { JSONObject(it).optBoolean("can_act") })
    }

    @Test
    fun malformedGameFrameImmediatelyClearsAdvice() {
        val protocol = MahjongSoulProtocol(schemaJson)
        val frames = captures(case("four_player_red_reach"))
        frames.forEach { protocol.accept(it.toString()) }
        val damaged = copy(frames.last()).put("sequence", frames.last().getLong("sequence") + 1).put("data", "AA==")
        val result = protocol.accept(damaged.toString())
        assertFalse(result.synchronized)
        assertTrue(result.sessionReset)
        assertTrue(result.clearAdvice)
        assertTrue(result.events.isEmpty())
    }

    private fun transformAction(capture: JSONObject, transform: (DynamicMessage) -> DynamicMessage): JSONObject =
        transformWire(capture, 1) { envelope ->
            val dataField = envelope.descriptorForType.findFieldByName("data")
            val bytes = (envelope.getField(dataField) as ByteString).toByteArray()
            val action = transform(schema.decode("ActionPrototype", bytes))
            envelope.toBuilder().setField(dataField, action.toByteString()).build()
        }

    private fun transformResponse(capture: JSONObject, name: String,
                                  transform: (DynamicMessage) -> DynamicMessage): JSONObject =
        transformWire(capture, 3) { envelope ->
            val dataField = envelope.descriptorForType.findFieldByName("data")
            val bytes = (envelope.getField(dataField) as ByteString).toByteArray()
            envelope.toBuilder().setField(dataField, transform(schema.decode(name, bytes)).toByteString()).build()
        }

    private fun transformWire(capture: JSONObject, headerLength: Int,
                              transform: (DynamicMessage) -> DynamicMessage): JSONObject {
        val bytes = Base64.getDecoder().decode(capture.getString("data"))
        val envelope = schema.decode("Wrapper", bytes.copyOfRange(headerLength, bytes.size))
        val changed = bytes.copyOfRange(0, headerLength) + transform(envelope).toByteArray()
        return copy(capture).put("data", Base64.getEncoder().encodeToString(changed))
    }

    private fun xorPayload(action: DynamicMessage, name: String): DynamicMessage {
        val bytes = (action.getField(action.descriptorForType.findFieldByName("data")) as ByteString).toByteArray()
        return schema.decode(name, LiqiDecoder.xor(bytes))
    }

    companion object {
        private fun resource(name: String): String {
            val stream = MahjongSoulProtocolTest::class.java.classLoader?.getResourceAsStream(name)
            assertNotNull("Generate fixtures in CI first: python scripts/android_protocol_fixtures.py ($name)", stream)
            return requireNotNull(stream).bufferedReader().use { it.readText() }
        }
        private val schemaJson by lazy { resource("protocol/liqi.json") }
        private val schema by lazy { LiqiSchema(schemaJson) }
        private val fixtures by lazy { JSONObject(resource("protocol/fixtures.json")) }
        private fun fixtureCases(): List<JSONObject> = fixtures.getJSONArray("cases").objects()
        private fun case(name: String): JSONObject = fixtureCases().single { it.getString("name") == name }
        private fun captures(case: JSONObject): List<JSONObject> = case.getJSONArray("captures").objects()
        private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
        private fun copy(value: JSONObject) = JSONObject(value.toString())
        private fun withoutInferenceFlags(value: JSONObject): JSONObject = copy(value).apply {
            remove("sync")
            remove("can_act")
        }
    }
}
