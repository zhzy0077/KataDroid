package com.example.katadroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.example.katadroid.ui.theme.GoColors

enum class GoSymbol { More, Settings, First, Previous, Play, Pause, Next, Last, Chart, Tree, Back, Locate, Plus, Minus, Chevron }

@Composable
fun GoIcon(symbol: GoSymbol, modifier: Modifier = Modifier, color: Color = GoColors.Ink) {
    Canvas(modifier.size(24.dp)) {
        withTransform({ scale(size.width / 24f, size.height / 24f, Offset.Zero) }) {
            val stroke = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(vararg points: Float) {
                val path = Path().apply {
                    moveTo(points[0], points[1])
                    for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
                }
                drawPath(path, color, style = stroke)
            }
            fun circle(x: Float, y: Float, r: Float) = drawCircle(color, r, Offset(x, y), style = stroke)
            when (symbol) {
                GoSymbol.More -> listOf(5f, 12f, 19f).forEach { drawCircle(color, 1.5f, Offset(12f, it)) }
                GoSymbol.First -> { line(6f, 5f, 6f, 19f); line(18f, 6f, 11f, 12f, 18f, 18f) }
                GoSymbol.Previous -> line(14f, 6f, 8f, 12f, 14f, 18f)
                GoSymbol.Next, GoSymbol.Chevron -> line(10f, 6f, 16f, 12f, 10f, 18f)
                GoSymbol.Last -> { line(18f, 5f, 18f, 19f); line(6f, 6f, 13f, 12f, 6f, 18f) }
                GoSymbol.Play -> drawPath(Path().apply { moveTo(9f, 5f); lineTo(19f, 12f); lineTo(9f, 19f); close() }, color)
                GoSymbol.Pause -> { drawLine(color, Offset(8f, 5f), Offset(8f, 19f), 3f); drawLine(color, Offset(16f, 5f), Offset(16f, 19f), 3f) }
                GoSymbol.Chart -> { line(4f, 4f, 4f, 20f, 20f, 20f); line(7f, 14f, 11f, 10f, 15f, 12f, 20f, 5f) }
                GoSymbol.Tree -> {
                    circle(6f, 5f, 2f); circle(6f, 19f, 2f); circle(18f, 5f, 2f); line(6f, 7f, 6f, 17f)
                    drawPath(Path().apply { moveTo(6f, 14f); cubicTo(6f, 8f, 18f, 13f, 18f, 7f) }, color, style = stroke)
                }
                GoSymbol.Back -> { line(12f, 5f, 5f, 12f, 12f, 19f); line(5f, 12f, 21f, 12f) }
                GoSymbol.Locate -> { circle(12f, 12f, 7f); circle(12f, 12f, 2.5f); line(12f, 2f, 12f, 5f); line(12f, 19f, 12f, 22f); line(2f, 12f, 5f, 12f); line(19f, 12f, 22f, 12f) }
                GoSymbol.Plus -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
                GoSymbol.Minus -> line(5f, 12f, 19f, 12f)
                GoSymbol.Settings -> {
                    val path = Path().apply {
                        moveTo(9f, 3f); lineTo(8.4f, 5.2f); lineTo(6.4f, 6.1f); lineTo(4f, 5.5f); lineTo(2f, 9f)
                        lineTo(3.7f, 10.6f); lineTo(3.7f, 13.4f); lineTo(2f, 15f); lineTo(4f, 18.5f)
                        lineTo(6.4f, 17.9f); lineTo(8.4f, 18.8f); lineTo(9f, 21f); lineTo(13f, 21f)
                        lineTo(13.6f, 18.8f); lineTo(15.6f, 17.9f); lineTo(18f, 18.5f); lineTo(20f, 15f)
                        lineTo(18.3f, 13.4f); lineTo(18.3f, 10.6f); lineTo(20f, 9f); lineTo(18f, 5.5f)
                        lineTo(15.6f, 6.1f); lineTo(13.6f, 5.2f); lineTo(13f, 3f); close()
                    }
                    drawPath(path, color, style = stroke); circle(11f, 12f, 3f)
                }
            }
        }
    }
}
