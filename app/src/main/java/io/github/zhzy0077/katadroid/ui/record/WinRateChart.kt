package io.github.zhzy0077.katadroid.ui.record

import android.graphics.Paint
import io.github.zhzy0077.katadroid.R
import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import io.github.zhzy0077.katadroid.ui.theme.GoColors
import kotlin.math.roundToInt

/** A null node is a transient engine PV, never a node to insert into the SGF. */
internal data class ChartPoint(val move: Int, val winRate: Float?, val nodeId: String?) {
    val preview get() = nodeId == null
}

@Composable
internal fun WinRateChart(samples: List<ChartPoint>, currentMove: Int, onSelectNode: (String) -> Unit, modifier: Modifier) {
    val resources = LocalResources.current
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f } }
    val lastMove = samples.lastOrNull()?.move ?: 0
    val endMove = lastMove.coerceAtLeast(1)
    val navigable by rememberUpdatedState(samples.filter { it.nodeId != null })
    val selectNode by rememberUpdatedState(onSelectNode)
    val lastRecordedMove = navigable.lastOrNull()?.move ?: 0
    fun selectMove(move: Int): Boolean {
        val nodeId = navigable.firstOrNull { it.move == move }?.nodeId ?: return false
        selectNode(nodeId)
        return true
    }

    Canvas(modifier.testTag("win-rate-chart").semantics {
        contentDescription = resources.getString(R.string.chart_accessibility)
        stateDescription = resources.getString(R.string.chart_state, resources.getString(if (currentMove > lastRecordedMove) R.string.preview else R.string.current), currentMove, lastRecordedMove)
        progressBarRangeInfo = ProgressBarRangeInfo(currentMove.coerceAtMost(lastRecordedMove).toFloat(),
            0f..lastRecordedMove.toFloat(), (lastRecordedMove - 1).coerceAtLeast(0))
        setProgress(resources.getString(R.string.seek_move)) { value ->
            value.isFinite() && selectMove(value.coerceIn(0f, lastRecordedMove.toFloat()).roundToInt())
        }
    }.pointerInput(lastMove) {
        detectTapGestures { touch ->
            // Use the same centered, aspect-preserving transform as the drawing.
            val scale = minOf(size.width / 300f, size.height / 98f)
            if (scale > 0f) {
                val plotX = (touch.x - (size.width - 300f * scale) / 2f) / scale
                val move = ((plotX - 30f) / 260f * endMove).roundToInt().coerceIn(0, lastMove)
                selectMove(move)
            }
        }
    }) {
        val scale = minOf(size.width / 300f, size.height / 98f)
        if (scale <= 0f) return@Canvas
        withTransform({ translate((size.width - 300f * scale) / 2f, (size.height - 98f * scale) / 2f); scale(scale, scale, Offset.Zero) }) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))
            listOf(12f, 42f, 72f).forEach { drawLine(GoColors.Outline, Offset(29f, it), Offset(294f, it), 1f, pathEffect = dash) }
            paint.color = GoColors.Muted.toArgb()
            paint.textAlign = Paint.Align.RIGHT
            listOf("100" to 15f, "50" to 45f, "0" to 75f).forEach { (label, y) -> drawContext.canvas.nativeCanvas.drawText(label, 21f, y, paint) }
            fun x(move: Int) = 30f + 260f * move / endMove
            fun y(value: Float) = 72f - value.coerceIn(0f, 100f) * .6f
            paint.textAlign = Paint.Align.CENTER
            (0..4).map { lastMove * it / 4 }.distinct().forEach {
                drawContext.canvas.nativeCanvas.drawText(it.toString(), x(it), 91f, paint)
            }
            // Connect only adjacent evaluated positions. A gap is not an estimate.
            samples.zipWithNext().forEach { (before, after) ->
                val first = before.winRate ?: return@forEach
                val second = after.winRate ?: return@forEach
                val a = Offset(x(before.move), y(first))
                val b = Offset(x(after.move), y(second))
                if (!after.preview) {
                    val area = Path().apply { moveTo(a.x, 72f); lineTo(a.x, a.y); lineTo(b.x, b.y); lineTo(b.x, 72f); close() }
                    drawPath(area, Brush.verticalGradient(listOf(Color(0xFFB8D2A6).copy(alpha = .65f), Color(0xFFDCE7D3).copy(alpha = .1f)), 12f, 72f))
                }
                drawLine(if (after.preview) Color(0xFFA07836) else Color(0xFF47754E), a, b, 2f,
                    cap = StrokeCap.Round, pathEffect = if (after.preview) dash else null)
            }
            samples.forEach { sample ->
                sample.winRate?.let { rate -> drawCircle(if (sample.preview) Color(0xFFA07836) else GoColors.Primary,
                    if (sample.move == currentMove) 3f else 2f, Offset(x(sample.move), y(rate))) }
            }
            drawLine(Color(0xFF83A36C), Offset(x(currentMove), 12f), Offset(x(currentMove), 72f), 1f, pathEffect = dash)
        }
    }
}
