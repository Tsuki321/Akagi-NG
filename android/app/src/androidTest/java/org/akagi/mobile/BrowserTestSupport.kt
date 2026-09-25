package org.akagi.mobile

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

internal fun findWebView(view: View): WebView? {
    if (view is WebView) return view
    if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
    return null
}

internal fun evaluate(activity: Activity, script: String): Any? {
    val latch = CountDownLatch(1)
    val result = AtomicReference<String>()
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        checkNotNull(findWebView(activity.window.decorView)).evaluateJavascript(script) {
            result.set(it)
            latch.countDown()
        }
    }
    check(latch.await(10, TimeUnit.SECONDS)) { "WebView JavaScript did not return" }
    return JSONTokener(result.get()).nextValue()
}

internal fun awaitBrowser(activity: Activity, condition: String, timeoutMs: Long = 15_000) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (System.nanoTime() < deadline) {
        if (evaluate(activity, "Boolean($condition)") == true) return
        Thread.sleep(50)
    }
    error("Browser condition timed out: $condition")
}

/** Real Android first-use education; never change or suppress it in the app. */
internal fun dismissFullscreenEducation(activity: Activity, requirePrompt: Boolean = false): Boolean {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val selectors = listOf(By.res("android", "ok"), By.res("com.android.systemui", "ok"))
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(if (requirePrompt) 12 else 2)
    while (System.nanoTime() < deadline) {
        for (selector in selectors) {
            val confirmation = device.findObject(selector) ?: continue
            if (!confirmation.text.equals("Got it", ignoreCase = true)) continue
            if (requirePrompt) captureScreenshot(activity, "00_system_first_launch_education")
            confirmation.click()
            check(device.wait(Until.gone(selector), 5_000)) { "System fullscreen education did not dismiss" }
            awaitWindowFocus(activity)
            return true
        }
        Thread.sleep(100)
    }
    check(!requirePrompt) { "Expected Android's first-launch fullscreen education on the fresh emulator" }
    awaitWindowFocus(activity)
    return false
}

internal fun awaitWindowFocus(activity: Activity, timeoutMs: Long = 10_000) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    val focused = AtomicBoolean()
    while (System.nanoTime() < deadline) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { focused.set(activity.window.decorView.hasWindowFocus()) }
        if (focused.get()) return
        Thread.sleep(50)
    }
    error("The game window did not regain input focus")
}

/** Use the measured browser viewport, which may differ from display density. */
internal fun tapBrowserElement(activity: Activity, elementId: String) {
    val element = JSONObject.quote(elementId)
    val target = JSONObject(evaluate(activity, "JSON.stringify((() => { const e=document.getElementById($element); const r=e.getBoundingClientRect(); return {x:r.x+r.width/2,y:r.y+r.height/2,width:innerWidth,height:innerHeight,visible:r.left>=0&&r.top>=0&&r.right<=innerWidth&&r.bottom<=innerHeight}; })())") as String)
    check(target.getBoolean("visible")) { "Game control $elementId is outside the viewport: $target" }
    val location = IntArray(2)
    val dimensions = IntArray(2)
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val webView = checkNotNull(findWebView(activity.window.decorView))
        webView.getLocationOnScreen(location)
        dimensions[0] = webView.width
        dimensions[1] = webView.height
    }
    val x = location[0] + target.getDouble("x") * dimensions[0] / target.getDouble("width")
    val y = location[1] + target.getDouble("y") * dimensions[1] / target.getDouble("height")
    check(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).click(x.toInt(), y.toInt()))
}

/** Pulled by CI from the target APK's externalFilesDir/screenshots. */
internal fun captureScreenshot(activity: Activity, name: String) {
    val directory = File(checkNotNull(activity.getExternalFilesDir(null)), "screenshots")
    check(directory.isDirectory || directory.mkdirs())
    val destination = File(directory, "$name.png")
    check(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(destination)) {
        "Could not save screenshot $name"
    }
    check(destination.length() > 0)
}
