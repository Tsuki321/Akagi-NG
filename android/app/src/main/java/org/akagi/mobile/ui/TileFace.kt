package org.akagi.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun tileDescription(tile: String): String {
    val honors = mapOf("E" to "East", "S" to "South", "W" to "West", "N" to "North", "P" to "White", "F" to "Green", "C" to "Red")
    honors[tile]?.let { return "$it tile" }
    if (tile.length < 2) return tile
    val red = tile.endsWith("r") || tile[0] == '0'
    val number = if (tile[0] == '0') '5' else tile[0]
    val suit = when (tile[1]) { 'm' -> "characters"; 'p' -> "circles"; 's' -> "bamboo"; else -> "tile" }
    return "${if (red) "Red " else ""}$number $suit"
}

@Composable
internal fun TileFace(tile: String, small: Boolean = false) {
    val honor = tile.length == 1
    val red = tile.endsWith("r") || tile.firstOrNull() == '0' || tile == "C"
    val color = when {
        red -> Color(0xFFAF4037)
        tile.endsWith("s") || tile == "F" -> Color(0xFF256344)
        else -> Color(0xFF203E4E)
    }
    val top = when (tile) {
        "E" -> "東"; "S" -> "南"; "W" -> "西"; "N" -> "北"
        "P" -> "□"; "F" -> "發"; "C" -> "中"
        else -> if (tile.startsWith('0')) "5" else tile.take(1)
    }
    val bottom = when {
        honor -> tile
        tile.getOrNull(1) == 'm' -> "萬"
        tile.getOrNull(1) == 'p' -> "●"
        tile.getOrNull(1) == 's' -> "竹"
        else -> tile.drop(1)
    }
    Column(
        modifier = Modifier
            .size(if (small) 23.dp else 34.dp, if (small) 30.dp else 44.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFF1EEE2))
            .semantics { contentDescription = tileDescription(tile) },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(top, color = color, fontSize = if (small) 14.sp else 20.sp, fontWeight = FontWeight.Bold, lineHeight = if (small) 15.sp else 21.sp)
        Text(bottom, color = color, fontSize = if (small) 8.sp else 11.sp, lineHeight = if (small) 9.sp else 12.sp)
    }
}
