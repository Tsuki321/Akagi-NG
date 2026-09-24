package org.akagi.mobile

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.akagi.mobile.ui.UiAdvice
import org.akagi.mobile.ui.UiAlternative
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameShellTest {
    @get:Rule val compose = createAndroidComposeRule<BrowserFixtureActivity>()

    @Before fun waitForGame() {
        compose.waitUntil(15_000) { compose.activity.captures.any { JSONObject(it).optString("type") == "capture_ready" } }
        awaitBrowser(compose.activity, "window.fixtureReady")
    }

    @Test fun compactControlsLeaveTheGameFullscreenAndTouchable() {
        val chip = compose.onNodeWithTag("advice_chip")
        chip.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        val root = compose.onNodeWithTag("game_root").fetchSemanticsNode().boundsInRoot
        val game = compose.onNodeWithTag("game_webview").fetchSemanticsNode().boundsInRoot
        val chipBounds = chip.fetchSemanticsNode().boundsInRoot
        assertEquals(root.width, game.width, 1f)
        assertEquals(root.height, game.height, 1f)
        assertTrue("Collapsed control takes less than 3% of the game", chipBounds.width * chipBounds.height < root.width * root.height * .03f)
        compose.onNodeWithTag("settings_sheet").assertDoesNotExist()
        compose.onNodeWithTag("advice_strip").assertDoesNotExist()
        captureScreenshot(compose.activity, "01_fixture_compact_landscape")
        val action = evaluate(compose.activity, "JSON.stringify((() => { const r=document.getElementById('fixture_action').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2};})())") as String
        val center = JSONObject(action)
        val density = compose.activity.resources.displayMetrics.density
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).click((center.getDouble("x") * density).toInt(), (center.getDouble("y") * density).toInt())
        awaitBrowser(compose.activity, "window.fixtureClicks === 1")
    }

    @Test fun expandSettingsDismissAndBackReturnToCompactGame() {
        compose.onNodeWithTag("advice_chip").performClick()
        compose.onNodeWithTag("advice_strip").assertIsDisplayed()
        compose.onNodeWithTag("open_settings").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("settings_sheet").assertIsDisplayed()
        captureScreenshot(compose.activity, "03_fixture_settings_landscape")
        compose.onNodeWithTag("close_settings").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("settings_sheet").assertDoesNotExist()
        compose.onNodeWithTag("advice_strip").assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("advice_chip").assertIsDisplayed()
        compose.onNodeWithTag("advice_strip").assertDoesNotExist()
        compose.onNodeWithTag("advice_chip").performClick()
        compose.onNodeWithTag("open_settings").performClick()
        pressBack()
        compose.onNodeWithTag("settings_sheet").assertDoesNotExist()
        compose.onNodeWithTag("collapse_advice").performClick()
        compose.onNodeWithTag("advice_chip").assertIsDisplayed()
    }

    @Test fun settingsDispatchRealCallbacksAndExplainExistingGoogleAccounts() {
        compose.onNodeWithTag("advice_chip").performClick()
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("load_replay").performScrollTo().performClick()
        assertEquals(1, compose.activity.replayRequests.get())
        compose.onNodeWithTag("model_check").performScrollTo().performClick()
        assertEquals(1, compose.activity.modelCheckRequests.get())
        compose.onNodeWithTag("diagnostic_report").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("account_help").performScrollTo().performClick()
        compose.onNodeWithText("Sign in with Yostar").assertIsDisplayed()
        compose.onNodeWithText("Got it").performClick()
        compose.onNodeWithText("Sign in with Yostar").assertDoesNotExist()
    }

    @Test fun longPressMovesTheChipButKeepsItAboveTheHand() {
        val before = compose.onNodeWithTag("advice_chip").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("advice_chip").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(190f, 70f))
            advanceEventTime(100)
            up()
        }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("advice_chip").fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("game_root").fetchSemanticsNode().boundsInRoot
        assertTrue("Chip moved with a held drag", after.left > before.left + 30f)
        assertTrue("Chip stays clear of the player's hand", after.bottom <= root.height * .56f)
        compose.onNodeWithTag("advice_strip").assertDoesNotExist()
    }

    @Test fun rotationAndBackgroundPreserveTheActualWebViewDocument() {
        val document = evaluate(compose.activity, "window.fixtureDocumentToken")
        val session = evaluate(compose.activity, "window.fixtureSession")
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        compose.onNodeWithTag("advice_chip").assertIsDisplayed()
        assertEquals(document, evaluate(compose.activity, "window.fixtureDocumentToken"))
        captureScreenshot(compose.activity, "04_fixture_compact_portrait")
        compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(document, evaluate(compose.activity, "window.fixtureDocumentToken"))
        assertEquals(session, evaluate(compose.activity, "window.fixtureSession"))
        assertEquals(1, compose.activity.captures.count { JSONObject(it).optString("type") == "capture_ready" })
    }

    @Test fun clearingAdviceImmediatelyRemovesTheOldTileAndAction() {
        compose.runOnUiThread { compose.activity.fixtureState = compose.activity.fixtureState.copy(advice = UiAdvice("Discard", "5mr", "Validated test action")) }
        compose.onNodeWithText("Discard").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.fixtureState = compose.activity.fixtureState.copy(advice = null) }
        compose.onNodeWithText("Discard").assertDoesNotExist()
        compose.onNodeWithText("Akagi").assertIsDisplayed()
    }

    @Test fun reloadStartsANewCaptureGenerationAndKeepsSessionStorage() {
        val session = evaluate(compose.activity, "window.fixtureSession")
        val document = evaluate(compose.activity, "window.fixtureDocumentToken")
        val generation = JSONObject(compose.activity.captures.first()).getString("generation")
        compose.onNodeWithTag("advice_chip").performClick()
        compose.onNodeWithTag("open_settings").performClick()
        compose.onNodeWithTag("reload_game").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.activity.captures.count { JSONObject(it).optString("type") == "capture_ready" } >= 2 }
        awaitBrowser(compose.activity, "window.fixtureReady")
        assertEquals(session, evaluate(compose.activity, "window.fixtureSession"))
        assertTrue(document != evaluate(compose.activity, "window.fixtureDocumentToken"))
        assertTrue(generation != JSONObject(compose.activity.captures.last()).getString("generation"))
        assertTrue(compose.activity.resets.get() >= 2)
    }

    @Test fun activityRecreationRetainsCookiesAndTheGameAccountStorage() {
        val session = evaluate(compose.activity, "window.fixtureSession")
        evaluate(compose.activity, "document.cookie='akagi_fixture_cookie=retained; Max-Age=3600; SameSite=Lax; Path=/'; true")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15_000) { compose.activity.captures.any { JSONObject(it).optString("type") == "capture_ready" } }
        awaitBrowser(compose.activity, "window.fixtureReady")
        assertEquals(session, evaluate(compose.activity, "window.fixtureSession"))
        assertTrue((evaluate(compose.activity, "document.cookie") as String).contains("akagi_fixture_cookie=retained"))
    }

    @Test fun googleLoginNavigationExplainsBindingWithoutLosingTheGame() {
        val document = evaluate(compose.activity, "window.fixtureDocumentToken")
        evaluate(compose.activity, "setTimeout(() => { location.href='https://accounts.google.com/o/oauth2/v2/auth'; }, 0); true")
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("Sign in with Yostar").fetchSemanticsNode() }.isSuccess
        }
        captureScreenshot(compose.activity, "06_fixture_yostar_account_help")
        compose.onNodeWithText("Got it").performClick()
        assertEquals(document, evaluate(compose.activity, "window.fixtureDocumentToken"))
    }

    @Test fun expandedAdviceRemainsReadableWithoutCoveringTheHand() {
        compose.runOnUiThread {
            compose.activity.fixtureState = compose.activity.fixtureState.copy(advice = UiAdvice(
                action = "Discard", tile = "5mr", detail = "UI fixture · tile rendering",
                alternatives = listOf(UiAlternative("Discard", "9p"), UiAlternative("Discard", "1s")),
            ))
        }
        compose.onNodeWithTag("advice_chip").performClick()
        compose.onNodeWithText("Discard", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("collapse_advice").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        val overlay = compose.onNodeWithTag("advice_overlay").fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("game_root").fetchSemanticsNode().boundsInRoot
        assertTrue(overlay.bottom < root.height * .56f)
        captureScreenshot(compose.activity, "02_fixture_expanded_advice")
    }

    @Test fun largerSystemTextKeepsSettingsAndCompactControlsUsable() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val previousScale = device.executeShellCommand("settings get system font_scale").trim().toFloatOrNull() ?: 1f
        try {
            device.executeShellCommand("settings put system font_scale 1.5")
            compose.waitUntil(10_000) { compose.activity.resources.configuration.fontScale >= 1.49f }
            compose.onNodeWithTag("advice_chip").assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithTag("open_settings").performClick()
            compose.onNodeWithTag("close_settings").assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("load_replay").performScrollTo().assertIsDisplayed()
            captureScreenshot(compose.activity, "05_fixture_settings_large_font")
            compose.onNodeWithTag("model_check").performScrollTo().performClick()
            assertEquals(1, compose.activity.modelCheckRequests.get())
        } finally {
            device.executeShellCommand("settings put system font_scale $previousScale")
        }
    }

    @Test fun rendererLossRecreatesTheBrowserAndInstallsCaptureAgain() {
        val previousGeneration = JSONObject(compose.activity.captures.first()).getString("generation")
        compose.runOnUiThread {
            checkNotNull(findWebView(compose.activity.window.decorView)).loadUrl("chrome://crash")
        }
        compose.waitUntil(20_000) { compose.activity.captures.count { JSONObject(it).optString("type") == "capture_ready" } >= 2 }
        awaitBrowser(compose.activity, "window.fixtureReady")
        val generation = JSONObject(compose.activity.captures.last()).getString("generation")
        assertTrue(previousGeneration != generation)
        assertTrue(compose.activity.resets.get() >= 2)
        compose.onNodeWithTag("advice_chip").assertIsDisplayed()
        captureScreenshot(compose.activity, "07_fixture_renderer_recovered")
    }
}
