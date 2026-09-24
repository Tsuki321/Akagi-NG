package org.akagi.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.akagi.mobile.ui.AkagiApp

class MainActivity : ComponentActivity() {
    private lateinit var analysis: AnalysisViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        analysis = ViewModelProvider(this)[AnalysisViewModel::class.java]
        setContent {
            val state = analysis.state.collectAsStateWithLifecycle()
            AkagiApp(
                state = state.value,
                onCapture = analysis::capture,
                onSessionReset = analysis::resetSession,
                onLoadLocalReplay = analysis::checkSavedHand,
                onRunModelCheck = analysis::checkModels,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (::analysis.isInitialized) analysis.setVisible(true)
    }

    override fun onStop() {
        if (::analysis.isInitialized) analysis.setVisible(false)
        super.onStop()
    }
}
