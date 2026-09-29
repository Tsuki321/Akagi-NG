package org.akagi.mobile

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.akagi.mobile.browser.DEBUG_FIXTURE_EXTRA
import org.akagi.mobile.browser.DEBUG_PORT_EXTRA
import org.akagi.mobile.protocol.AssistancePlan
import org.akagi.mobile.ui.StatusTone
import org.akagi.mobile.ui.UiAdvice
import org.akagi.mobile.ui.UiState
import org.akagi.mobile.ui.UiStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistanceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun manualHighlightsFollowTheCanvasAndLetTouchesReachTheGame() = withTable { activity ->
        advise(activity)
        settings()
        compose.onNodeWithTag("highlight_moves").performScrollTo().performClick()
        returnToGame()
        awaitBrowser(activity, "document.querySelector('[data-tile-index=\"4\"]')")
        assertEquals(0, inputs(activity).size)
        assertEquals("Discard", evaluate(activity, "document.querySelector('[data-action]').dataset.action"))
        captureScreenshot(activity, "10_manual_table_highlight")
        val marker = JSONObject(evaluate(activity, "JSON.stringify(document.querySelector('[data-tile-index=\"4\"]').getBoundingClientRect().toJSON())") as String)
        assertTrue(marker.getDouble("height") > marker.getDouble("width"))
        val point = JSONArray(evaluate(activity, "JSON.stringify((()=>{const r=document.querySelector('[data-tile-index=\"4\"]').getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2];})())") as String)
        val location = IntArray(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { requireNotNull(findWebView(activity.window.decorView)).getLocationOnScreen(location) }
        val ratio = (evaluate(activity, "devicePixelRatio") as Number).toDouble()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).click(
            location[0] + (point.getDouble(0) * ratio).toInt(), location[1] + (point.getDouble(1) * ratio).toInt())
        awaitBrowser(activity, "window.fixtureClicks === 1")
        assertEquals(0, inputs(activity).size)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        awaitBrowser(activity, "innerHeight > innerWidth")
        awaitBrowser(activity, "document.querySelector('[data-tile-index=\"4\"]')?.getBoundingClientRect().width > document.querySelector('[data-tile-index=\"4\"]')?.getBoundingClientRect().height")
        captureScreenshot(activity, "11_portrait_table_highlight")
        InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.fixtureState = UiState() }
        awaitBrowser(activity, "!document.getElementById('akagi-table-guidance')")
    }

    @Test fun autoplaySendsOneMoveAndConsumesOnlyItsOwnReply() = withTable { activity ->
        advise(activity)
        settings()
        compose.onNodeWithTag("autoplay").performScrollTo().performClick()
        assertEquals(0, inputs(activity).size)
        compose.onNodeWithTag("close_settings").performScrollTo().performClick()
        SystemClock.sleep(3_250)
        assertEquals("Expanded advice keeps autoplay paused", 0, inputs(activity).size)
        compose.onNodeWithTag("collapse_advice").performClick()
        compose.waitUntil(8_000) { inputs(activity).size == 1 }
        val sent = inputs(activity).single()
        assertEquals(2, android.util.Base64.decode(sent.getString("data"), android.util.Base64.DEFAULT)[0].toInt())
        awaitBrowser(activity, "fixtureReceived.length === 1")
        compose.waitUntil(5_000) {
            activity.captures.map(::JSONObject).any { it.optString("direction") == "inbound" &&
                it.optBoolean("binary") && android.util.Base64.decode(it.getString("data"), android.util.Base64.DEFAULT).firstOrNull() == 3.toByte() }
        }
        assertEquals(1, (evaluate(activity, "fixtureReceived.length") as Number).toInt())
        captureScreenshot(activity, "12_autoplay_active")
        settings()
        compose.onNodeWithTag("autoplay").performScrollTo().performClick()
        returnToGame()
        evaluate(activity, "fixtureNextTurn(2); true")
        awaitBrowser(activity, "fixtureReceived.length === 2")
        advise(activity)
        SystemClock.sleep(3_250)
        assertEquals(1, inputs(activity).size)
    }

    @Test fun touchingTheGameDisablesAutoplayForSubsequentTurns() = withTable { activity ->
        advise(activity)
        settings()
        compose.onNodeWithTag("autoplay").performScrollTo().performClick()
        returnToGame()
        compose.waitUntil(8_000) { inputs(activity).size == 1 }
        tapBrowserElement(activity, "unity-canvas")
        awaitBrowser(activity, "window.fixtureClicks === 1")
        settings()
        compose.onNodeWithTag("autoplay").performScrollTo().assertIsOff()
        returnToGame()
        evaluate(activity, "fixtureNextTurn(2); true")
        awaitBrowser(activity, "fixtureReceived.length === 2")
        advise(activity)
        SystemClock.sleep(3_250)
        assertEquals("Manual touch keeps the next turn manual", 1, inputs(activity).size)
    }

    private fun settings() {
        compose.onNodeWithTag("advice_chip").performClick()
        compose.onNodeWithTag("open_settings").performClick()
    }

    private fun returnToGame() {
        compose.onNodeWithTag("close_settings").performScrollTo().performClick()
        compose.onNodeWithTag("collapse_advice").performClick()
    }

    private fun advise(activity: BrowserFixtureActivity) {
        val frame = activity.captures.map(::JSONObject).last { it.optString("direction") == "inbound" && it.optLong("gameRevision") > 0 }
        val generation = frame.getString("generation")
        val connection = frame.getString("connectionId")
        val revision = frame.getLong("gameRevision")
        val hand = JSONArray(evaluate(activity, "JSON.stringify(fixtureHand)") as String).let { array -> List(array.length()) { array.getString(it) } }
        val plan = AssistancePlan("$generation/$connection/$revision", generation, connection, revision, hand, listOf(4),
            "Discard", "5pr", """{"method":"inputOperation","type":1,"tile":"0p","moqie":false}""", 120000)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity.fixtureState = UiState(status = UiStatus("Local AI ready", tone = StatusTone.READY, captureActive = true, engineReady = true),
                advice = UiAdvice("Discard", "5pr", assistance = plan))
        }
        compose.waitForIdle()
    }

    private fun inputs(activity: BrowserFixtureActivity) = activity.captures.map(::JSONObject).filter {
        it.optString("direction") == "outbound" && it.optBoolean("binary") &&
            android.util.Base64.decode(it.getString("data"), android.util.Base64.DEFAULT).firstOrNull() == 2.toByte()
    }

    private fun withTable(block: (BrowserFixtureActivity) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LoopbackSocketFixture(context, "assistance").use { fixture ->
            val intent = Intent(context, BrowserFixtureActivity::class.java)
                .putExtra(DEBUG_FIXTURE_EXTRA, "capture").putExtra(DEBUG_PORT_EXTRA, fixture.port)
            ActivityScenario.launch<BrowserFixtureActivity>(intent).use { scenario ->
                lateinit var activity: BrowserFixtureActivity
                scenario.onActivity { activity = it }
                dismissFullscreenEducation(activity)
                awaitBrowser(activity, "window.fixtureReady === true")
                compose.waitUntil(10_000) { activity.captures.map(::JSONObject).any { it.optString("direction") == "inbound" } }
                block(activity)
            }
        }
    }
}
