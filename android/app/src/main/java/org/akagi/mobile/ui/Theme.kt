package org.akagi.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

internal val Mint = Color(0xFFA7DFC4)
internal val Ink = Color(0xFF101F23)
internal val Muted = Color(0xFFABBFBA)
internal val Gold = Color(0xFFE8C789)

@Composable
fun AkagiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Mint,
            onPrimary = Color(0xFF0C3023),
            primaryContainer = Color(0xFF244D3E),
            onPrimaryContainer = Color(0xFFD0F6E1),
            secondary = Gold,
            secondaryContainer = Color(0xFF29483F),
            onSecondaryContainer = Color(0xFFD0F0DE),
            background = Color(0xFF091316),
            surface = Ink,
            onSurface = Color(0xFFEDF4EE),
            onSurfaceVariant = Muted,
            surfaceContainer = Color(0xFF18292D),
            outline = Color(0xFF415752),
            error = Color(0xFFFFB4AA),
        ),
    ) {
        // MaterialTheme does not set the default text color outside a Surface.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
    }
}
