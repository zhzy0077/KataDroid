package com.example.katadroid.ui.record

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** Current-player loss against the best evaluated candidate, in percentage points. */
fun candidateWinRateLoss(winRate: Float, bestWinRate: Float): Float = (bestWinRate - winRate).coerceAtLeast(0f)

/** Near-best moves stay green even in an even or losing position; rank alone has no color. */
fun candidateColor(winRate: Float, bestWinRate: Float): Color {
    val loss = candidateWinRateLoss(winRate, bestWinRate)
    val green = Color(0xFF28784E)
    val yellow = Color(0xFFE1C265)
    val red = Color(0xFFD87959)
    return when {
        !loss.isFinite() -> Color(0xFF8B968A)
        loss <= 2f -> green
        loss < 5f -> lerp(green, yellow, (loss - 2f) / 3f)
        loss < 10f -> lerp(yellow, red, (loss - 5f) / 5f)
        else -> red
    }
}

fun candidateTextColor(fill: Color): Color =
    if ((fill.luminance() + .05f) / .05f >= 1.05f / (fill.luminance() + .05f)) Color.Black else Color.White
