package com.example.katadroid.ui.record

import android.graphics.Paint
import android.graphics.Typeface
import com.example.katadroid.R
import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.katadroid.ui.theme.GoColors
import com.example.katadroid.engine.BoardSnapshot
import com.example.katadroid.engine.PositionAnalysis
import kotlin.math.roundToInt

@Composable
fun GoBoard(
    state: RecordUiState,
    board: BoardSnapshot,
    analysis: PositionAnalysis?,
    showCoordinates: Boolean,
    showCandidates: Boolean,
    modifier: Modifier = Modifier,
    touchOffsetPx: Int = 0,
    manualInput: Boolean = true,
) {
    val resources = LocalResources.current
    val position = state.position
    val preview = state.preview
    val previewStep = state.previewStep
    val stones = remember(board) { (0..360).filter { board.color(it) != 0 } }
    val previewMoves = preview?.moves?.take(previewStep).orEmpty()
    val evaluatedCandidates = if (preview == null && showCandidates && analysis?.position == position && !board.finished)
        analysis.candidates.filter { board.isLegal(it.move) } else emptyList()
    // Include pass in the reference even though it has no intersection marker.
    val bestWinRate = evaluatedCandidates.maxOfOrNull { it.winRateFor(position.nextPlayer) } ?: 0f
    val candidates = evaluatedCandidates.filter { it.move in 0..360 }
    val haptics = LocalHapticFeedback.current
    val currentCandidates by rememberUpdatedState(candidates)
    var touchPoint by remember(position, touchOffsetPx) { mutableStateOf<Int?>(null) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.NORMAL) } }
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(modifier.testTag("board-input").pointerInput(position, preview, touchOffsetPx, manualInput) {
        fun pointAt(offset: Offset): Int? {
            val column = ((offset.x / size.width * 640f - 41f) / 31f).roundToInt()
            // Pointer coordinates are already physical pixels. The extra input
            // space below the square keeps the bottom row reachable too.
            val row = (((offset.y - touchOffsetPx) / size.width * 640f - 41f) / 31f).roundToInt()
            return if (column in 0..18 && row in 0..18) row * 19 + column else null
        }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val candidate = if (preview == null) currentCandidates.firstOrNull { it.move == pointAt(down.position) } else null
            var canLongPress = candidate != null
            var longPressed = false
            var released = false
            var current = down
            down.consume()
            try {
                while (!released) {
                    touchPoint = if (manualInput && preview == null && !longPressed)
                        pointAt(current.position)?.takeIf(board::isLegal) else null
                    val remaining = viewConfiguration.longPressTimeoutMillis - (current.uptimeMillis - down.uptimeMillis)
                    val event = if (canLongPress && remaining > 0) withTimeoutOrNull(remaining) { awaitPointerEvent() }
                        else if (canLongPress) null else awaitPointerEvent()
                    if (event == null) {
                        canLongPress = false; longPressed = true; touchPoint = null
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        candidate?.let { state.startPreview(Candidate(it.label, it.pv.take(3))) }
                        continue
                    }
                    if (event.changes.any { it.id != down.id && (it.pressed || it.previousPressed) }) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) canLongPress = false
                    change.consume()
                    current = change
                    released = !change.pressed
                    if (released && !longPressed && manualInput && preview == null) pointAt(change.position)?.let(state::play)
                }
            } finally { touchPoint = null }
        }
    }) {
        val boardSize = maxWidth
        if (touchOffsetPx > 0) Text(resources.getString(R.string.touch_offset_indicator, touchOffsetPx), fontSize = 10.sp, color = GoColors.Muted,
            modifier = Modifier.align(Alignment.BottomCenter))
        Box(Modifier.size(boardSize).clip(shape).background(GoColors.Board)
            .border(1.dp, if (preview == null) GoColors.BoardBorder else Color(0xFF779A64), shape)
            .testTag("go-board")
            .semantics {
                contentDescription = resources.getString(R.string.board_description)
                stateDescription = resources.getString(R.string.board_state, position.moves.size, position.moves.lastOrNull()?.let { BoardPoint.fromIndex(it)?.label ?: resources.getString(R.string.pass_move) } ?: resources.getString(R.string.empty_board), stones.count { board.color(it) == 1 }, stones.count { board.color(it) == 2 }) +
                    if (preview != null) resources.getString(R.string.board_preview_state, preview.label, previewStep) else ""
            }) {
        Canvas(Modifier.fillMaxSize()) {
            withTransform({ scale(size.width / 640f, size.height / 640f, Offset.Zero) }) {
                repeat(19) { i ->
                    val p = 41f + i * 31f
                    drawLine(GoColors.BoardLine, Offset(41f, p), Offset(599f, p), .85f)
                    drawLine(GoColors.BoardLine, Offset(p, 41f), Offset(p, 599f), .85f)
                }
                for (x in listOf(134f, 320f, 506f)) for (y in listOf(134f, 320f, 506f)) {
                    drawCircle(Color(0xFF776442), 3.2f, Offset(x, y))
                }
                if (showCoordinates) {
                    paint.color = Color(0xFF6C5A3C).toArgb()
                    paint.textSize = 10f
                    paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    repeat(19) { i ->
                        val p = 41f + i * 31f
                        drawContext.canvas.nativeCanvas.drawText(BoardPoint.COLUMNS[i].toString(), p, 21f, paint)
                        drawContext.canvas.nativeCanvas.drawText(BoardPoint.COLUMNS[i].toString(), p, 626f, paint)
                        drawContext.canvas.nativeCanvas.drawText((19 - i).toString(), 18f, p + 4f, paint)
                        drawContext.canvas.nativeCanvas.drawText((19 - i).toString(), 622f, p + 4f, paint)
                    }
                }
                stones.forEach { drawGoStone(BoardPoint.fromIndex(it)!!.boardOffset(), 14.5f, board.color(it) == 1) }
                position.moves.lastOrNull()?.let(BoardPoint::fromIndex)?.let { drawCircle(GoColors.Primary, 5f, it.boardOffset(), style = Stroke(2f)) }
                candidates.forEach { candidate ->
                    val point = BoardPoint.fromIndex(candidate.move)!!.boardOffset()
                    val fill = candidateColor(candidate.winRateFor(position.nextPlayer), bestWinRate)
                    val outline = lerp(fill, Color.Black, .24f)
                    if (candidate.label == "A") drawCircle(fill.copy(alpha = .35f), 20f, point, style = Stroke(1.5f))
                    drawCircle(fill, 14f, point)
                    drawCircle(outline, 14f, point, style = Stroke(1.3f))
                    paint.color = candidateTextColor(fill).toArgb()
                    paint.textSize = 14f
                    paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    drawContext.canvas.nativeCanvas.drawText(candidate.label, point.x, point.y + 5f, paint)
                }
                previewMoves.withIndex().associateBy { it.value }.values.forEach { (index, move) ->
                    val point = BoardPoint.fromIndex(move) ?: return@forEach
                    if (board.color(move) == 0) return@forEach
                    val p = point.boardOffset()
                    drawCircle(Color(0xFF638347), 17f, p, style = Stroke(1.5f))
                    paint.color = (if (board.color(move) == 1) Color.White else GoColors.Primary).toArgb()
                    paint.textSize = 14f
                    paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    drawContext.canvas.nativeCanvas.drawText((index + 1).toString(), p.x, p.y + 5f, paint)
                }
                touchPoint?.let { move ->
                    val p = BoardPoint.fromIndex(move)!!.boardOffset()
                    drawCircle((if (board.blackToMove) Color(0xFF15201B) else Color.White).copy(alpha = .65f), 14.5f, p)
                    drawCircle(GoColors.Primary, 18f, p, style = Stroke(2f))
                }
            }
        }
        // Semantic targets support TalkBack. Physical touches use the board's
        // nearest intersection, so expanded button targets cannot steal an
        // adjacent legal move on a dense 19x19 board.
        candidates.forEach { candidate ->
            val point = BoardPoint.fromIndex(candidate.move)!!
            Box(
                Modifier.offset(boardSize * ((41 + point.column * 31) / 640f) - 18.dp,
                    boardSize * ((41 + point.row * 31) / 640f) - 18.dp)
                    .size(36.dp).clip(CircleShape)
                    .testTag("candidate-${candidate.label}")
                    .semantics {
                        role = Role.Button
                        val winRate = candidate.winRateFor(position.nextPlayer)
                        contentDescription = resources.getString(R.string.candidate_accessibility, candidate.label, point.label, resources.getString(if (position.nextPlayer == 1) R.string.black else R.string.white), winRate.oneDecimal(), candidateWinRateLoss(winRate, bestWinRate).oneDecimal())
                        if (manualInput) onClick(resources.getString(R.string.play_here)) { state.play(candidate.move) }
                        onLongClick(resources.getString(R.string.preview_variation)) { state.startPreview(Candidate(candidate.label, candidate.pv.take(3))); true }
                    },
            )
        }
        touchPoint?.let { move ->
            val point = BoardPoint.fromIndex(move)!!
            Box(Modifier.offset(boardSize * ((41 + point.column * 31) / 640f) - 10.dp,
                boardSize * ((41 + point.row * 31) / 640f) - 10.dp).size(20.dp)
                .testTag("placement-preview").semantics { contentDescription = resources.getString(R.string.release_to_play, point.label) })
        }
        }
    }
}

private fun BoardPoint.boardOffset() = Offset(41f + column * 31f, 41f + row * 31f)

@Composable
fun PlayerStone(black: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(26.dp)) { drawGoStone(center, size.minDimension * .47f, black) }
}

private fun DrawScope.drawGoStone(center: Offset, radius: Float, black: Boolean) {
    drawCircle(Color(0xFF493B24).copy(alpha = .13f), radius * 1.08f, center + Offset(0f, radius * .12f))
    drawCircle(
        Brush.radialGradient(
            colorStops = if (black) arrayOf(0f to Color(0xFF4A4D49), .6f to Color(0xFF222623), 1f to Color(0xFF121713))
            else arrayOf(0f to Color(0xFFFFFEFA), .7f to Color(0xFFF4F3EC), 1f to Color(0xFFD6D6CE)),
            center = center + Offset(-radius * .3f, -radius * .5f), radius = radius * 1.6f,
        ), radius, center,
    )
    if (!black) drawCircle(Color(0xFFD9DED1), radius, center, style = Stroke(radius * .035f))
}
