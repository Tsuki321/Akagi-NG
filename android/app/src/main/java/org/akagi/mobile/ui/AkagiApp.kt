package org.akagi.mobile.ui

import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.akagi.mobile.browser.GameBrowser
import org.akagi.mobile.browser.findActivity
import org.akagi.mobile.browser.openExternal
import kotlin.math.roundToInt

@Composable
fun AkagiApp(
    state: UiState,
    onCapture: (String) -> Unit,
    onSessionReset: () -> Unit,
    onLoadLocalReplay: () -> Unit,
    onRunModelCheck: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestCapture by rememberUpdatedState(onCapture)
    val latestReset by rememberUpdatedState(onSessionReset)
    var rendererGeneration by remember { mutableIntStateOf(0) }
    val browser = remember(rendererGeneration) {
        GameBrowser(context, onCapture = { latestCapture(it) }, onSessionReset = { latestReset() },
            onRendererGone = { rendererGeneration++ })
    }
    DisposableEffect(browser, lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(browser)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(browser)
            browser.destroy()
        }
    }
    ImmersiveGameWindow()
    AkagiTheme {
        GameScreen(state, browser, onLoadLocalReplay, onRunModelCheck)
    }
}

@Composable
private fun ImmersiveGameWindow() {
    val activity = LocalContext.current.findActivity() ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(activity, lifecycle) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        fun hideBars() {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        hideBars()
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) hideBars() }
        window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = hideBars()
        }
        lifecycle.addObserver(observer)
        onDispose {
            window.decorView.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            lifecycle.removeObserver(observer)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameScreen(
    state: UiState,
    browser: GameBrowser,
    onLoadLocalReplay: () -> Unit,
    onRunModelCheck: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val preferences = remember { context.getSharedPreferences("game_ui", 0) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAccountHelp by rememberSaveable { mutableStateOf(false) }
    var allowPortrait by rememberSaveable { mutableStateOf(preferences.getBoolean("allow_portrait", false)) }
    var xFraction by rememberSaveable { mutableFloatStateOf(preferences.getFloat("chip_x", 0f)) }
    var yFraction by rememberSaveable { mutableFloatStateOf(preferences.getFloat("chip_y", 0f)) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    val browserState = browser.state

    LaunchedEffect(browserState.accountHelpRequested) {
        if (browserState.accountHelpRequested) {
            showAccountHelp = true
            browser.dismissAccountHelpRequest()
        }
    }
    LaunchedEffect(allowPortrait) {
        activity?.requestedOrientation = if (allowPortrait) ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        preferences.edit().putBoolean("allow_portrait", allowPortrait).apply()
    }
    BackHandler(enabled = browserState.canGoBack && !expanded && !showSettings && browser.customView == null) { browser.goBack() }
    BackHandler(enabled = expanded && !showSettings) { expanded = false }
    BackHandler(enabled = browser.customView != null && !showSettings) { browser.hideCustomView() }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("game_root")) {
        key(browser) {
            AndroidView(factory = { browser.webView }, modifier = Modifier.fillMaxSize().testTag("game_webview"))
        }
        browser.customView?.let { fullView ->
            AndroidView(
                factory = { (fullView.parent as? ViewGroup)?.removeView(fullView); fullView },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (browserState.loading) {
            LinearProgressIndicator(
                progress = { browserState.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter),
                color = Mint.copy(alpha = .65f), trackColor = Color.Transparent,
            )
        }
        val density = LocalDensity.current
        val cutout = WindowInsets.displayCutout
        val left = cutout.getLeft(density, androidx.compose.ui.unit.LayoutDirection.Ltr) + with(density) { 8.dp.toPx() }
        val right = cutout.getRight(density, androidx.compose.ui.unit.LayoutDirection.Ltr) + with(density) { 8.dp.toPx() }
        val top = cutout.getTop(density) + with(density) { 4.dp.toPx() }
        val availableX = (constraints.maxWidth - left - right - overlaySize.width).coerceAtLeast(0f)
        // Keep the movable overlay out of the bottom hand and action controls.
        val availableY = (constraints.maxHeight * .55f - top - overlaySize.height).coerceAtLeast(0f)
        val error = browserState.error
        val status = if (error != null) UiStatus("Connection needs attention", error, StatusTone.ERROR) else state.status
        val advice = if (error == null) state.advice else null
        Column(
            Modifier
                .offset { IntOffset((left + xFraction * availableX).roundToInt(), (top + yFraction * availableY).roundToInt()) }
                .widthIn(max = minOf(maxWidth - 24.dp, 344.dp))
                .onSizeChanged { overlaySize = it }
                .testTag("advice_overlay"),
            horizontalAlignment = Alignment.Start,
        ) {
            val dragModifier = Modifier.pointerInput(availableX, availableY) {
                detectDragGesturesAfterLongPress(
                    onDragEnd = { preferences.edit().putFloat("chip_x", xFraction).putFloat("chip_y", yFraction).apply() },
                ) { change, delta ->
                    change.consume()
                    if (availableX > 0) xFraction = (xFraction + delta.x / availableX).coerceIn(0f, 1f)
                    if (availableY > 0) yFraction = (yFraction + delta.y / availableY).coerceIn(0f, 1f)
                }
            }
            if (!expanded) {
                CompactAdvice(advice, status, dragModifier, onClick = { expanded = true })
            } else {
                AdviceStrip(advice, status, dragModifier, onSettings = { showSettings = true }, onCollapse = { expanded = false })
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Ink,
            dragHandle = null,
        ) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(.9f).testTag("settings_sheet")
                    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(top = 18.dp)) {
                        Text("Akagi", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text("Mahjong Soul · advice on your device", color = Muted, fontSize = 13.sp)
                    }
                    SmallIconButton("Close settings", "close", Modifier.testTag("close_settings")) { showSettings = false }
                }
                Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF1B3033)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (browserState.error != null) "Connection needs attention" else state.status.label, fontWeight = FontWeight.SemiBold)
                        Text(browserState.error ?: state.status.detail, color = Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { showSettings = false; browser.reload() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("reload_game")) { Text("Reload game") }
                    FilledTonalButton(onClick = onLoadLocalReplay, enabled = !state.localReplayRunning, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("load_replay")) { Text(if (state.localReplayRunning) "Checking hand…" else "Check saved hand") }
                }
                SettingsToggle("Allow portrait", "Rotate freely while keeping your game open.", allowPortrait) { allowPortrait = it }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("A quieter game screen", fontWeight = FontWeight.Medium)
                        Text("Tap the chip for advice. Hold and drag it to move.", color = Muted, fontSize = 13.sp)
                    }
                    TextButton(onClick = { xFraction = 0f; yFraction = 0f; preferences.edit().remove("chip_x").remove("chip_y").apply() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset") }
                }
                HorizontalDivider(color = Color(0xFF304540))
                TextButton(onClick = { showAccountHelp = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("account_help")) { Text("Using a Google account?") }
                FilledTonalButton(onClick = onRunModelCheck, enabled = !state.modelCheckRunning, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("model_check")) { Text(if (state.modelCheckRunning) "Checking local model…" else "Check local model") }
                state.diagnostic?.let { report ->
                    SelectionContainer { Text(report, color = Muted, fontSize = 12.sp, modifier = Modifier.testTag("diagnostic_report")) }
                }
                Text("Your game session stays on this device. Advice runs locally; Mahjong Soul still needs an internet connection.", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (showAccountHelp) {
        AlertDialog(
            onDismissRequest = { showAccountHelp = false },
            title = { Text("Sign in with Yostar") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Use the game's Yostar email sign-in in this app.")
                    Text("For an existing Google account, first open Mahjong Soul where Google sign-in already works. Go to Settings → Others → Account Settings, bind an unused Yostar email, and finish verification. Then sign in here with that email.")
                    Text("The Yostar account must never have been connected to Mahjong Soul. Check that your player ID and progress match before playing.")
                }
            },
            confirmButton = { TextButton(onClick = { showAccountHelp = false }) { Text("Got it") } },
            dismissButton = { TextButton(onClick = { openExternal(context, Uri.parse("https://mahjongsoul.yo-star.com/")) }) { Text("Yostar support") } },
        )
    }
}

@Composable
private fun CompactAdvice(advice: UiAdvice?, status: UiStatus, dragModifier: Modifier, onClick: () -> Unit) {
    val description = if (advice == null) "Open Akagi advice, ${status.label}"
    else "Open Akagi advice, ${advice.action} ${advice.tile?.let(::tileDescription).orEmpty()}"
    Box(
        Modifier.then(dragModifier).heightIn(min = 48.dp).widthIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description; liveRegion = LiveRegionMode.Polite }
            .testTag("advice_chip"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(34.dp).clip(RoundedCornerShape(18.dp)).background(Ink.copy(alpha = .93f))
                .border(1.dp, Mint.copy(alpha = .23f), RoundedCornerShape(18.dp)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            StatusDot(status.tone)
            Text(advice?.action ?: "Akagi", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
            advice?.tile?.let { TileFace(it, small = true) }
            Glyph("expand", Modifier.size(12.dp), Muted)
        }
    }
}

@Composable
private fun AdviceStrip(advice: UiAdvice?, status: UiStatus, dragModifier: Modifier, onSettings: () -> Unit, onCollapse: () -> Unit) {
    Surface(Modifier.widthIn(min = 240.dp).testTag("advice_strip"), color = Ink.copy(alpha = .96f), shape = RoundedCornerShape(18.dp), shadowElevation = 3.dp, border = androidx.compose.foundation.BorderStroke(1.dp, Mint.copy(alpha = .18f))) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().then(dragModifier), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status.tone)
                Text("AKAGI", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                SmallIconButton("Open settings", "settings", Modifier.testTag("open_settings"), onSettings)
                SmallIconButton("Collapse advice", "collapse", Modifier.testTag("collapse_advice"), onCollapse)
            }
            Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                advice?.tile?.let { TileFace(it) }
                Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(advice?.action ?: status.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val detail = advice?.detail?.takeIf { it.isNotBlank() } ?: status.detail
                    if (detail.isNotBlank()) Text(detail, color = Muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                advice?.latencyMs?.let { Text("${it}ms", color = Mint, fontSize = 10.sp) }
            }
            if (!advice?.alternatives.isNullOrEmpty()) {
                Row(Modifier.padding(top = 10.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Also", color = Muted, fontSize = 10.sp)
                    advice!!.alternatives.take(2).forEach { alternative ->
                        Text(listOfNotNull(alternative.action, alternative.tile).joinToString(" "), color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsToggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, color = Muted, fontSize = 13.sp)
        }
        Switch(checked, onChange, Modifier.semantics { contentDescription = title }.testTag("allow_portrait"))
    }
}

@Composable
private fun StatusDot(tone: StatusTone) {
    val color = when (tone) { StatusTone.READY -> Mint; StatusTone.ERROR -> Color(0xFFFFB4AA); StatusTone.BUSY -> Gold; StatusTone.CONNECTING -> Muted }
    Box(Modifier.size(6.dp).clip(CircleShape).background(color))
}

@Composable
private fun SmallIconButton(label: String, glyph: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Glyph(glyph, Modifier.size(18.dp), Muted)
    }
}

@Composable
private fun Glyph(kind: String, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.7.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(w * x1, h * y1), Offset(w * x2, h * y2), stroke, StrokeCap.Round)
        when (kind) {
            "close" -> { line(.25f,.25f,.75f,.75f); line(.75f,.25f,.25f,.75f) }
            "settings" -> {
                drawCircle(color, w * .28f, style = Stroke(stroke))
                drawCircle(color, w * .08f, style = Stroke(stroke))
                line(.5f,.05f,.5f,.2f); line(.5f,.8f,.5f,.95f)
                line(.05f,.5f,.2f,.5f); line(.8f,.5f,.95f,.5f)
                line(.18f,.18f,.28f,.28f); line(.72f,.72f,.82f,.82f)
                line(.18f,.82f,.28f,.72f); line(.72f,.28f,.82f,.18f)
            }
            else -> {
                val path = Path().apply {
                    val flip = kind == "collapse"
                    moveTo(w * .2f, h * if (flip) .65f else .35f)
                    lineTo(w * .5f, h * if (flip) .35f else .65f)
                    lineTo(w * .8f, h * if (flip) .65f else .35f)
                }
                drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}
