package org.akagi.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.akagi.mobile.engine.MortalSession
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineDeviceTest {
    @Test fun actualModelsMatchDesktopAndSavedGameRunsEntirelyOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MortalSession(context).use { session ->
            val check = session.modelCheck()
            assertTrue(check.detail, check.ok)
            val times = mutableListOf<Long>()
            repeat(30) { iteration ->
                val players = if (iteration % 2 == 0) 4 else 3
                val advice = session.offlineReplay(players)
                assertTrue(advice.playerCount == players)
                assertTrue(advice.legalMask and (1L shl advice.recommended.index) != 0L)
                assertTrue(advice.recommended.score.isFinite())
                assertTrue(advice.recommended.type in setOf("dahai", "reach", "hora", "ankan", "kakan", "nukidora", "none"))
                times += advice.latencyMs
            }
            val sorted = times.sorted()
            val report = JSONObject()
                .put("modelParity", check.detail)
                .put("parityMilliseconds", check.latencyMs)
                .put("sampleCount", sorted.size)
                .put("p50Milliseconds", sorted[sorted.size / 2])
                .put("p95Milliseconds", sorted[(sorted.size * .95).toInt().coerceAtMost(sorted.lastIndex)])
                .put("worstMilliseconds", sorted.last())
                .put("samplesMilliseconds", JSONArray(times))
                .put("environment", "Android emulator; not an Exynos device benchmark")
            val directory = File(context.getExternalFilesDir(null), "measurements").apply { mkdirs() }
            File(directory, "local-mortal.json").writeText(report.toString(2))
        }
    }

    @Test fun foregroundRecomputeUsesLiveStateAndResetClearsTheDecision() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (players in listOf(4, 3)) {
            MortalSession(context).use { session ->
                var original: org.akagi.mobile.engine.EngineAdvice? = null
                context.assets.open("models/smoke_${players}p.jsonl").bufferedReader().useLines { lines ->
                    lines.filter(String::isNotBlank).forEach { event ->
                        session.acceptMjai(event)?.let { original = it }
                    }
                }
                val result = session.recomputePending()
                assertTrue(original != null && result != null)
                assertTrue(original!!.recommended.eventJson == result!!.recommended.eventJson)
                session.reset()
                assertTrue(session.recomputePending() == null)
            }
        }
    }
}
