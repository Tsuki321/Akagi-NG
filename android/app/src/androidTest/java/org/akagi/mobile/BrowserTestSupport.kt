package org.akagi.mobile

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONTokener
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
