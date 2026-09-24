package org.akagi.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.akagi.mobile.browser.DEBUG_FIXTURE_EXTRA
import org.akagi.mobile.ui.AkagiApp
import org.akagi.mobile.ui.StatusTone
import org.akagi.mobile.ui.UiState
import org.akagi.mobile.ui.UiStatus
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Internal debug-only host for the real shell. It never loads models or fabricates advice. */
class BrowserFixtureActivity : ComponentActivity() {
    val captures = CopyOnWriteArrayList<String>()
    val resets = AtomicInteger()
    val replayRequests = AtomicInteger()
    val modelCheckRequests = AtomicInteger()
    var fixtureState by mutableStateOf(UiState(status = UiStatus("Waiting for game", "The local UI fixture is open.", StatusTone.CONNECTING)))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!intent.hasExtra(DEBUG_FIXTURE_EXTRA)) intent.putExtra(DEBUG_FIXTURE_EXTRA, "table")
        getSharedPreferences("game_ui", MODE_PRIVATE).edit().clear().commit()
        setContent {
            AkagiApp(
                state = fixtureState,
                onCapture = { captures.add(it) },
                onSessionReset = { resets.incrementAndGet() },
                onLoadLocalReplay = { replayRequests.incrementAndGet() },
                onRunModelCheck = {
                    modelCheckRequests.incrementAndGet()
                    fixtureState = fixtureState.copy(diagnostic = "Model check callback received")
                },
            )
        }
    }
}
