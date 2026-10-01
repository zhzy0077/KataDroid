package com.example.katadroid.ui.record

import android.graphics.Paint
import com.example.katadroid.R
import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.katadroid.ui.GoIcon
import com.example.katadroid.ui.GoSymbol
import com.example.katadroid.ui.theme.GoColors
import kotlin.math.roundToInt
import kotlin.math.abs

private const val COLUMN = 56f
private const val ROW = 57f
private val RecordNode.world get() = Offset(depth * COLUMN, lane * ROW)

@Stable
class TreeViewportState {
    var zoom by mutableFloatStateOf(1f)
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var initialized by mutableStateOf(false)

    fun focus(node: RecordNode, size: Size) {
        x = size.width * .66f - node.world.x * zoom
        y = size.height * .57f - node.world.y * zoom
        initialized = true
    }

    fun transform(center: Offset, pan: Offset, factor: Float, size: Size, maxMove: Int, minLane: Int, maxLane: Int) {
        val newZoom = (zoom * factor).coerceIn(.5f, 2.5f)
        x = center.x - (center.x - x) * newZoom / zoom + pan.x
        y = center.y - (center.y - y) * newZoom / zoom + pan.y
        zoom = newZoom
        // Keep the finite record reachable even after a large fling/pinch.
        x = x.coerceIn(size.width * .15f - maxMove * COLUMN * zoom, size.width * .85f)
        y = y.coerceIn(size.height * .15f - maxLane * ROW * zoom, size.height * .85f - minLane * ROW * zoom)
    }

    companion object {
        val Saver = listSaver<TreeViewportState, Any>(
            save = { listOf(it.zoom, it.x, it.y, it.initialized) },
            restore = { data -> TreeViewportState().apply { zoom = data[0] as Float; x = data[1] as Float; y = data[2] as Float; initialized = data[3] as Boolean } },
        )
    }
}

@Composable
fun TreePanel(state: RecordUiState, viewport: TreeViewportState, engineEnabled: Boolean, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val node = state.node
    val nodes = state.nodes
    val byId = state.byId
    val maxMove = nodes.maxOf { it.depth }.coerceAtLeast(1)
    val minLane = nodes.minOf { it.lane }
    val maxLane = nodes.maxOf { it.lane }
    var pixels by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density
    val canvasSize = Size(pixels.width / density, pixels.height / density)
    val currentPath = remember(node.id, nodes) { state.path(node.id).map { it.id }.toSet() }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    fun screen(n: RecordNode) = Offset(viewport.x, viewport.y) + n.world * viewport.zoom

    LaunchedEffect(pixels, node.id) {
        if (pixels == IntSize.Zero) return@LaunchedEffect
        val p = screen(node)
        if (!viewport.initialized || p.x !in 20f..(canvasSize.width - 20f) || p.y !in 20f..(canvasSize.height - 20f)) viewport.focus(node, canvasSize)
    }

    Surface(modifier.testTag("tree-panel"), shape = RoundedCornerShape(20.dp), color = GoColors.Surface, border = BorderStroke(1.dp, GoColors.Outline)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (engineEnabled) resources.getString(R.string.full_record) else resources.getString(R.string.saved_tree), fontSize = 10.sp, color = GoColors.Primary)
                Text(resources.getString(R.string.tree_position, node.move, if (engineEnabled) node.branchLabel(resources) else resources.getString(R.string.engine_paused)), fontSize = 10.sp, color = GoColors.Muted, maxLines = 1)
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().clipToBounds().background(GoColors.Tree)
                    .onSizeChanged { pixels = it }.testTag("tree-canvas")
                    .pointerInput(density, pixels, maxMove, minLane, maxLane) {
                        detectTreePanZoom { centroid, pan, zoom ->
                            viewport.transform(centroid / density, pan / density, zoom, canvasSize, maxMove, minLane, maxLane)
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val dotStep = 12.dp.toPx()
                    for (x in 0..(size.width / dotStep).toInt()) for (y in 0..(size.height / dotStep).toInt()) {
                        drawCircle(Color(0xFFCEDAC3), .6.dp.toPx(), Offset(x * dotStep, y * dotStep))
                    }
                    // Draw only nodes/edges intersecting the viewport; the rest of the
                    // record remains in the model, not thousands of composables.
                    nodes.forEach { n ->
                        val parent = n.parentId?.let(byId::getValue) ?: return@forEach
                        val from = screen(parent) * density
                        val to = screen(n) * density
                        if (maxOf(from.x, to.x) < -24 * density || minOf(from.x, to.x) > size.width + 24 * density ||
                            maxOf(from.y, to.y) < -24 * density || minOf(from.y, to.y) > size.height + 24 * density) return@forEach
                        val path = Path().apply {
                            moveTo(from.x, from.y)
                            cubicTo((from.x + to.x) / 2, from.y, (from.x + to.x) / 2, to.y, to.x, to.y)
                        }
                        drawPath(path, if (n.id in currentPath) Color(0xFF54865A) else Color(0xFFC5D1BB), style = Stroke((if (n.id in currentPath) 1.8f else 1.25f) * density * viewport.zoom))
                    }
                    nodes.forEach { n ->
                        val p = screen(n) * density
                        val radius = 12.5f * density * viewport.zoom
                        if (p.x !in -radius..(size.width + radius) || p.y !in -radius..(size.height + radius)) return@forEach
                        val onPath = n.id in currentPath
                        if (n.id == node.id) {
                            drawCircle(GoColors.Container, radius * 1.5f, p)
                            drawCircle(GoColors.Primary, radius * 1.5f, p, style = Stroke(1.7f * density))
                        }
                        drawCircle(if (n.black) (if (onPath) Color(0xFF344F32) else Color(0xFF72846B)) else GoColors.Tree, radius, p)
                        drawCircle(if (onPath) Color(0xFF799868) else Color(0xFFBBC9AF), radius, p, style = Stroke(density * .85f))
                        paint.textSize = 10f * density * viewport.zoom
                        paint.color = (if (n.black) Color.White else Color(0xFF405B38)).toArgb()
                        drawContext.canvas.nativeCanvas.drawText(if (n.move == 0) resources.getString(R.string.tree_start) else n.move.toString(), p.x, p.y - (paint.ascent() + paint.descent()) / 2f, paint)
                        if (n.id == node.id) {
                            val badge = Size(48f * density, 18f * density)
                            val topLeft = Offset(p.x - badge.width / 2, p.y + radius + 10 * density)
                            drawRoundRect(GoColors.Primary, topLeft, badge, CornerRadius(9 * density))
                            paint.textSize = 10 * density; paint.color = Color.White.toArgb()
                            drawContext.canvas.nativeCanvas.drawText(resources.getString(R.string.current), p.x, topLeft.y + 12.5f * density, paint)
                        }
                    }
                }
                nodes.forEach { n ->
                    val p = screen(n)
                    val touch = (40f * viewport.zoom).coerceIn(28f, 48f)
                    if (p.x in 0f..canvasSize.width && p.y in 0f..canvasSize.height) {
                        Box(Modifier.offset((p.x - touch / 2).dp, (p.y - touch / 2).dp).size(touch.dp)
                            .clip(CircleShape).testTag("tree-node-${n.id}")
                            .semantics { contentDescription = resources.getString(R.string.tree_node_accessibility, n.move, n.moveLabel(resources), n.branchLabel(resources)); selected = n.id == node.id }
                            .clickable(role = Role.Button, onClickLabel = resources.getString(R.string.view_position)) { onSelect(n.id) })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(resources.getString(R.string.toward_start), fontSize = 9.sp, color = GoColors.Muted)
                    Text(resources.getString(R.string.toward_variations), fontSize = 9.sp, color = GoColors.Muted)
                }
                TreeMinimap(viewport, canvasSize, nodes, byId, Modifier.align(Alignment.BottomStart).padding(start = 9.dp, bottom = 7.dp).size(78.dp, 37.dp))
            }
            Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(resources.getString(R.string.tree_gestures), fontSize = 9.sp, color = GoColors.Muted, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewport.transform(Offset(canvasSize.width / 2, canvasSize.height / 2), Offset.Zero, 1 / 1.25f, canvasSize, maxMove, minLane, maxLane) }, enabled = viewport.zoom > .5f, modifier = Modifier.size(36.dp).semantics { contentDescription = resources.getString(R.string.zoom_out) }) {
                    GoIcon(GoSymbol.Minus, Modifier.size(16.dp))
                }
                Text("${(viewport.zoom * 100).roundToInt()}%", fontSize = 10.sp, color = GoColors.Muted, modifier = Modifier.testTag("tree-zoom"))
                IconButton(onClick = { viewport.transform(Offset(canvasSize.width / 2, canvasSize.height / 2), Offset.Zero, 1.25f, canvasSize, maxMove, minLane, maxLane) }, enabled = viewport.zoom < 2.5f, modifier = Modifier.size(36.dp).semantics { contentDescription = resources.getString(R.string.zoom_in) }) {
                    GoIcon(GoSymbol.Plus, Modifier.size(16.dp))
                }
                TextButton(onClick = { viewport.focus(node, canvasSize) }, modifier = Modifier.height(40.dp).semantics { contentDescription = resources.getString(R.string.focus_node) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
                    GoIcon(GoSymbol.Locate, Modifier.size(13.dp), GoColors.Primary)
                    Text(resources.getString(R.string.tree_current), fontSize = 11.sp)
                }
            }
        }
    }
}

/** Node buttons may consume a second finger's down event. Only consumed movement
 * cancels the canvas gesture; otherwise a pinch beginning on nodes gets lost. */
private suspend fun PointerInputScope.detectTreePanZoom(onTransform: (Offset, Offset, Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var accumulatedPan = Offset.Zero
        var accumulatedZoom = 1f
        var transforming = false
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
            if (event.changes.any { it.isConsumed && it.positionChangedIgnoreConsumed() }) break
            val pan = event.calculatePan()
            val zoom = event.calculateZoom()
            if (!transforming) {
                accumulatedPan += pan
                accumulatedZoom *= zoom
                val zoomDistance = abs(accumulatedZoom - 1f) * event.calculateCentroidSize(useCurrent = false)
                transforming = accumulatedPan.getDistance() > viewConfiguration.touchSlop ||
                    zoomDistance > viewConfiguration.touchSlop
            }
            if (transforming) {
                onTransform(event.calculateCentroid(useCurrent = false), pan, zoom)
                event.changes.filter { it.positionChanged() }.forEach { it.consume() }
            }
        }
    }
}

@Composable
private fun TreeMinimap(viewport: TreeViewportState, canvasSize: Size, nodes: List<RecordNode>, byId: Map<String, RecordNode>, modifier: Modifier) {
    val resources = LocalResources.current
    Canvas(modifier.clip(RoundedCornerShape(6.dp)).background(GoColors.Surface.copy(alpha = .94f)).semantics { contentDescription = resources.getString(R.string.tree_minimap) }) {
        val minY = (nodes.minOf { it.lane } - 1) * ROW
        val rangeY = (nodes.maxOf { it.lane } - nodes.minOf { it.lane } + 2) * ROW
        val pad = 4.dp.toPx()
        val sx = (size.width - pad * 2) / (nodes.maxOf { it.depth }.coerceAtLeast(1) * COLUMN)
        val sy = (size.height - pad * 2) / rangeY
        fun project(p: Offset) = Offset(pad + p.x * sx, pad + (p.y - minY) * sy)
        nodes.forEach { n ->
            n.parentId?.let { parent -> drawLine(Color(0xFFA2B496), project(byId.getValue(parent).world), project(n.world), .6.dp.toPx()) }
        }
        val origin = project(Offset(-viewport.x / viewport.zoom, -viewport.y / viewport.zoom))
        val rect = Size(canvasSize.width / viewport.zoom * sx, canvasSize.height / viewport.zoom * sy)
        drawRect(GoColors.Primary.copy(alpha = .12f), origin, rect)
        drawRect(GoColors.Primary, origin, rect, style = Stroke(.8.dp.toPx()))
        drawRoundRect(Color(0xFFBCCDAE), cornerRadius = CornerRadius(6.dp.toPx()), style = Stroke(1.dp.toPx()))
    }
}
