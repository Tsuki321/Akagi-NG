package org.akagi.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import org.akagi.mobile.engine.EngineAdvice
import org.akagi.mobile.engine.MortalSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineBatchDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun smoke(players: Int): List<String> = context.assets.open("models/smoke_${players}p.jsonl")
        .bufferedReader().use { it.readLines().filter(String::isNotBlank) }

    private fun assertSameScores(expected: EngineAdvice, actual: EngineAdvice) {
        assertEquals(expected.legalMask, actual.legalMask)
        assertEquals(expected.recommended.index, actual.recommended.index)
        val scores = expected.alternatives.associate { it.index to it.score }
        assertEquals(scores.keys, actual.alternatives.map { it.index }.toSet())
        actual.alternatives.forEach { action ->
            assertEquals("Q score for action ${action.index}", scores.getValue(action.index), action.score, 0.000001f)
        }
    }

    @Test fun modelScoresUseTheDoraAnnouncedWithTheDraw() {
        for (players in listOf(4, 3)) {
            val events = smoke(players)
            val dora = """{"type":"dora","dora_marker":"4p","can_act":false}"""
            MortalSession(context).use { batch ->
                MortalSession(context).use { reference ->
                    MortalSession(context).use { beforeAnnouncement ->
                        val actual = batch.acceptBatch(events + dora)
                        events.take(2).forEach { reference.acceptMjai(it) }
                        reference.acceptMjai(dora)
                        val expected = reference.acceptMjai(events.last())
                        var old: EngineAdvice? = null
                        events.forEach { beforeAnnouncement.acceptMjai(it)?.let { result -> old = result } }
                        assertNotNull(actual)
                        assertNotNull(expected)
                        assertNotNull(old)
                        assertSameScores(expected!!, actual!!)
                        assertSameScores(actual, batch.recomputePending()!!)
                        val oldScores = old!!.alternatives.associate { it.index to it.score }
                        assertTrue("The new dora must affect real model scores", actual.alternatives.any {
                            abs(it.score - oldScores.getValue(it.index)) > 0.00001f
                        })
                        assertNull(batch.acceptBatch(listOf("""{"type":"dora","dora_marker":"1s"}""")))
                        assertNull(batch.recomputePending())
                    }
                }
            }
        }
    }

    @Test fun reachPaymentInTheBatchKeepsRonAvailableWithoutFuriten() {
        for (players in listOf(4, 3)) {
            val events = smoke(players).map(::JSONObject)
            val round = events[1]
            round.put("oya", 1)
            round.getJSONArray("tehais").put(0, org.json.JSONArray(listOf(
                "E", "E", "E", "1p", "2p", "3p", "4p", "5p", "6p", "7s", "8s", "9s", "C",
            )))
            MortalSession(context).use { session ->
                val advice = session.acceptBatch(listOf(
                    events[0].toString(), round.toString(),
                    """{"type":"tsumo","actor":1,"pai":"?","can_act":false}""",
                    """{"type":"reach","actor":1,"can_act":false}""",
                    """{"type":"dahai","actor":1,"pai":"C","tsumogiri":true,"can_act":true}""",
                    """{"type":"reach_accepted","actor":1,"can_act":false}""",
                ))
                assertNotNull(advice)
                assertFalse(advice!!.furiten)
                assertTrue(advice.alternatives.any { it.type == "hora" })
                assertSameScores(advice, session.recomputePending()!!)
            }
        }
    }

    @Test fun suppressedReplayDoesNotCreateAdviceAndBatchErrorsClearTheSession() {
        val events = smoke(4).map { JSONObject(it).put("can_act", false).toString() }
        MortalSession(context).use { session ->
            assertNull(session.acceptBatch(events + """{"type":"dora","dora_marker":"4p","can_act":true}"""))
            assertNull(session.recomputePending())
            var rejected = false
            try {
                session.acceptBatch(listOf("""{"type":"dahai","actor":0,"pai":"9p","tsumogiri":false}"""))
            } catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected)
            assertNull(session.recomputePending())
        }
    }
}
