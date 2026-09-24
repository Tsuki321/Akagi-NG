package org.akagi.mobile

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.akagi.mobile.browser.DEBUG_FIXTURE_EXTRA
import org.akagi.mobile.browser.DEBUG_PORT_EXTRA
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BrowserCaptureTest {
    @Test fun documentStartHookCapturesRealSocketsAndPreservesEveryPayloadInOrder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LoopbackSocketFixture(context).use { fixture ->
            val intent = Intent(context, BrowserFixtureActivity::class.java)
                .putExtra(DEBUG_FIXTURE_EXTRA, "capture").putExtra(DEBUG_PORT_EXTRA, fixture.port)
            ActivityScenario.launch<BrowserFixtureActivity>(intent).use { scenario ->
                lateinit var activity: BrowserFixtureActivity
                scenario.onActivity { activity = it }
                awaitFrames(activity) { frames -> frames.any { it.optString("type") == "websocket_closed" } }
                val frames = activity.captures.map(::JSONObject)
                assertEquals("capture_ready", frames.first().getString("type"))
                assertEquals((1L..frames.size.toLong()).toList(), frames.map { it.getLong("sequence") })
                assertEquals(1, frames.map { it.getString("generation") }.toSet().size)
                assertTrue(frames.all { it.getBoolean("mainFrame") })
                assertTrue(frames.all { it.getString("sourceOrigin") == "http://127.0.0.1:${fixture.port}" })
                assertFalse(frames.any { it.optString("type") == "capture_error" })

                val sent = frames.filter { it.optString("type") == "websocket" && it.optString("direction") == "outbound" }
                val received = frames.filter { it.optString("type") == "websocket" && it.optString("direction") == "inbound" }
                val expected = listOf("fixture:text:1", "AQID", "BAUG", "BwgJ")
                assertEquals(expected, sent.map { it.getString("data") })
                assertEquals(expected, received.map { it.getString("data") })
                assertEquals(listOf(false, true, true, true), sent.map { it.getBoolean("binary") })
                assertEquals("blob", received[1].getString("payloadType"))
                assertEquals(1, frames.filter { it.optString("type").startsWith("websocket") }.map { it.getString("connectionId") }.toSet().size)

                val page = JSONObject(evaluate(activity, "JSON.stringify(window.fixtureResults)") as String)
                assertTrue(page.getBoolean("bootstrapBeforePageScript"))
                assertTrue(page.getBoolean("nativeInstance"))
                assertTrue(page.getBoolean("nativeConstants"))
                assertTrue(page.getBoolean("nativeConstructor"))
                assertTrue(page.getBoolean("nativeSendSignature"))
                assertTrue(page.getBoolean("sendReturnedUndefined"))
                assertTrue(page.getBoolean("closed"))
                assertEquals(4, page.getInt("received"))
            }
        }
    }

    @Test fun trustedFramesHaveSeparateGenerationsAndOtherOriginsNeverReceiveTheBridge() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LoopbackSocketFixture(context).use { fixture ->
            val intent = Intent(context, BrowserFixtureActivity::class.java)
                .putExtra(DEBUG_FIXTURE_EXTRA, "capture").putExtra(DEBUG_PORT_EXTRA, fixture.port)
            ActivityScenario.launch<BrowserFixtureActivity>(intent).use { scenario ->
                lateinit var activity: BrowserFixtureActivity
                scenario.onActivity { activity = it }
                awaitFrames(activity) { frames -> frames.any { it.optString("type") == "websocket_closed" } }
                evaluate(activity, "(() => { const child=document.createElement('iframe');child.src='/child.html';document.body.appendChild(child);return true;})()")
                awaitFrames(activity) { frames -> frames.any { !it.optBoolean("mainFrame", true) && it.optString("type") == "websocket_closed" } }
                val childFrames = activity.captures.map(::JSONObject).filter { !it.getBoolean("mainFrame") }
                assertEquals((1L..childFrames.size.toLong()).toList(), childFrames.map { it.getLong("sequence") })
                assertEquals("trusted child", childFrames.first { it.optString("type") == "websocket" }.getString("data"))
                assertEquals(2, activity.captures.map { JSONObject(it).getString("generation") }.toSet().size)

                evaluate(activity, "(() => { const child=document.createElement('iframe');child.src='http://localhost:${fixture.port}/untrusted.html';document.body.appendChild(child);return true;})()")
                awaitBrowser(activity, "window.fixtureUntrustedResult === 'no-bridge'")
                assertFalse(activity.captures.any { JSONObject(it).getString("sourceOrigin").contains("localhost") })
            }
        }
    }

    private fun awaitFrames(activity: BrowserFixtureActivity, predicate: (List<JSONObject>) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            if (predicate(activity.captures.map(::JSONObject))) return
            Thread.sleep(50)
        }
        error("Capture did not complete. Received: ${activity.captures}")
    }
}
