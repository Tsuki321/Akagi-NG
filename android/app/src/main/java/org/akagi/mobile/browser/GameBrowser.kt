package org.akagi.mobile.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.akagi.mobile.BuildConfig
import org.json.JSONObject

const val GAME_URL = "https://mahjongsoul.game.yo-star.com/"
const val DEBUG_FIXTURE_EXTRA = "akagi.debug.fixture"
const val DEBUG_PORT_EXTRA = "akagi.debug.fixturePort"

data class BrowserState(
    val progress: Int = 0,
    val loading: Boolean = true,
    val canGoBack: Boolean = false,
    val captureReady: Boolean = false,
    val error: String? = null,
    val accountHelpRequested: Boolean = false,
)

/** The Activity handles configuration changes so its one WebView survives rotation. */
@SuppressLint("SetJavaScriptEnabled")
class GameBrowser(
    context: Context,
    initialUrl: String = initialGameUrl(context.findActivity()?.intent),
    private val onCapture: (String) -> Unit,
    private val onSessionReset: () -> Unit,
    private val onRendererGone: () -> Unit,
) : DefaultLifecycleObserver {
    var state by mutableStateOf(BrowserState())
        private set
    var customView by mutableStateOf<View?>(null)
        private set
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var destroyed = false
    private var setupError: String? = null
    private val startUrl = initialUrl
    private val origins = buildSet {
        add("https://mahjongsoul.game.yo-star.com")
        add("https://game.mahjongsoul.com")
        if (BuildConfig.DEBUG) {
            add("https://appassets.androidplatform.net")
            val uri = Uri.parse(initialUrl)
            if (uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1024..65535) {
                add("http://127.0.0.1:${uri.port}")
            }
        }
    }
    private val assets = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    val webView = WebView(context).apply {
        id = View.generateViewId()
        contentDescription = "Mahjong Soul game"
        keepScreenOn = true
        overScrollMode = View.OVER_SCROLL_NEVER
        setBackgroundColor(android.graphics.Color.rgb(9, 19, 22))
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            safeBrowsingEnabled = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(this, true)
    }

    init {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                state = state.copy(progress = newProgress)
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) callback.onCustomViewHidden()
                else {
                    customView = view
                    customViewCallback = callback
                }
            }

            override fun onHideCustomView() = hideCustomView()
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (BuildConfig.DEBUG) assets.shouldInterceptRequest(request.url) else null

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame || isGameOrigin(request.url)) return false
                if (request.url.host == "accounts.google.com") {
                    state = state.copy(accountHelpRequested = true)
                    return true
                }
                if (request.url.scheme == "https" || request.url.scheme == "http") {
                    openExternal(context, request.url)
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                state = state.copy(loading = true, progress = 0, error = setupError, captureReady = false)
                onSessionReset()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                state = state.copy(loading = false, canGoBack = view.canGoBack())
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    state = state.copy(loading = false, error = "The game could not load. Check your connection and reload.")
                    onSessionReset()
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                state = state.copy(captureReady = false, error = "The game browser restarted. Reconnecting…")
                onSessionReset()
                destroy()
                onRendererGone()
                return true
            }
        }
        installCapture()
        webView.loadUrl(startUrl)
    }

    private fun installCapture() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            setupError = "Update Android System WebView to enable live advice."
            state = state.copy(error = setupError)
            return
        }
        // Both are installed before loadUrl. The object is available before page script.
        WebViewCompat.addWebMessageListener(webView, "AkagiCapture", origins) { _, message, origin, mainFrame, _ ->
            if (destroyed || !isGameOrigin(origin)) return@addWebMessageListener
            val data = runCatching { message.data }.getOrNull() ?: return@addWebMessageListener
            if (data.length > 6 * 1024 * 1024) {
                state = state.copy(captureReady = false, error = "A game message exceeded the capture limit. Reload to synchronize.")
                onSessionReset()
                return@addWebMessageListener
            }
            try {
                val json = JSONObject(data)
                if (json.optString("generation").isBlank() || json.optLong("sequence", 0) <= 0) return@addWebMessageListener
                json.put("sourceOrigin", origin.toString())
                json.put("mainFrame", mainFrame)
                when (json.optString("type")) {
                    "capture_ready" -> if (mainFrame) state = state.copy(captureReady = true)
                    "capture_error" -> {
                        state = state.copy(captureReady = false, error = json.optString("message", "Capture stopped. Reload the game."))
                        onSessionReset()
                    }
                }
                onCapture(json.toString())
            } catch (_: Exception) {
                state = state.copy(captureReady = false, error = "Game capture was interrupted. Reload to synchronize.")
                onSessionReset()
            }
        }
        WebViewCompat.addDocumentStartJavaScript(
            webView, webView.context.assets.open("browser/capture.js").bufferedReader().use { it.readText() }, origins,
        )
    }

    private fun isGameOrigin(uri: Uri): Boolean {
        val authority = uri.host ?: return false
        val port = if (uri.port == -1 || (uri.scheme == "https" && uri.port == 443)) "" else ":${uri.port}"
        return "${uri.scheme}://$authority$port" in origins
    }

    fun reload() {
        if (destroyed) return
        onSessionReset()
        webView.reload()
    }

    fun goBack() {
        if (!destroyed && webView.canGoBack()) webView.goBack()
    }

    fun dismissAccountHelpRequest() {
        state = state.copy(accountHelpRequested = false)
    }

    fun hideCustomView() {
        (customView?.parent as? ViewGroup)?.removeView(customView)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    override fun onResume(owner: LifecycleOwner) {
        if (!destroyed) webView.onResume()
    }

    override fun onPause(owner: LifecycleOwner) {
        if (!destroyed) {
            CookieManager.getInstance().flush()
            webView.onPause()
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        hideCustomView()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
    }
}

fun initialGameUrl(intent: Intent?): String {
    if (!BuildConfig.DEBUG) return GAME_URL
    val fixture = intent?.getStringExtra(DEBUG_FIXTURE_EXTRA)
    if (fixture !in setOf("table", "capture")) return GAME_URL
    val port = intent?.getIntExtra(DEBUG_PORT_EXTRA, 0) ?: 0
    return if (fixture == "capture" && port in 1024..65535) {
        "http://127.0.0.1:$port/capture.html?socket=ws://127.0.0.1:$port/socket"
    } else {
        "https://appassets.androidplatform.net/assets/fixtures/$fixture.html"
    }
}

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun openExternal(context: Context, uri: Uri) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
