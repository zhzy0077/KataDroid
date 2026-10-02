package io.github.zhzy0077.katadroid.ui.record

import androidx.compose.ui.platform.LocalResources
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import io.github.zhzy0077.katadroid.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.zhzy0077.katadroid.ui.GoIcon
import io.github.zhzy0077.katadroid.ui.GoSymbol
import io.github.zhzy0077.katadroid.ui.AppPreferences
import androidx.compose.ui.text.style.TextOverflow
import io.github.zhzy0077.katadroid.ui.theme.GoColors
import io.github.zhzy0077.katadroid.engine.analysisFor
import io.github.zhzy0077.katadroid.engine.BoardSnapshot
import io.github.zhzy0077.katadroid.engine.EnginePhase
import io.github.zhzy0077.katadroid.engine.EngineUiState
import io.github.zhzy0077.katadroid.engine.PASS

@Composable
fun RecordScreen(
    state: RecordUiState,
    viewport: TreeViewportState,
    settings: AppPreferences,
    engine: EngineUiState,
    manualInput: Boolean,
    onEngineEnabled: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onResumeAutomatic: () -> Unit,
    onNewGame: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val position = state.position
    val board = remember(position) { BoardSnapshot.from(position) }
    val offsetHeight = with(LocalDensity.current) { settings.touchOffsetPx.toDp() }
    val header: @Composable (Boolean) -> Unit = { compact ->
        RecordHeader(state, settings, engine, onEngineEnabled, onSettings, compact,
            onPass = { state.play(PASS) }, canPass = manualInput && state.preview == null && !board.finished,
            onImport = onImport, onExport = onExport, onResumeAutomatic = onResumeAutomatic, onNewGame = onNewGame)
    }
    BoxWithConstraints(modifier.fillMaxSize().background(GoColors.Background)) {
        if (maxWidth > maxHeight) {
            // Keep all controls beside the board: no global header or tab bar
            // consumes the limited landscape board height.
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).padding(8.dp)) {
                val rightMinimum = if (maxWidth < 680.dp) 280.dp else 320.dp
                val boardSize = minOf(maxHeight - offsetHeight, maxWidth - rightMinimum - 12.dp).coerceAtLeast(120.dp)
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RecordBoard(state, board, engine, settings, manualInput, Modifier.size(boardSize, boardSize + offsetHeight))
                    Column(Modifier.weight(1f).fillMaxSize()) {
                        header(true)
                        Players(state, board, settings, onResumeAutomatic, Modifier.fillMaxWidth())
                        Transport(state)
                        CurrentAnalysis(state, viewport, engine, settings, onRetry, Modifier.weight(1f).fillMaxWidth())
                        AnalysisTabs(state, insetBottom = false)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                header(false)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                    val fontExtra = ((LocalDensity.current.fontScale - 1f).coerceAtLeast(0f) * 75).dp
                    val boardSize = minOf(maxWidth, (maxHeight - (if (maxHeight < 550.dp) 280.dp else 310.dp) - fontExtra - offsetHeight).coerceAtLeast(120.dp))
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Players(state, board, settings, onResumeAutomatic, Modifier.fillMaxWidth())
                        RecordBoard(state, board, engine, settings, manualInput, Modifier.size(boardSize, boardSize + offsetHeight))
                        Transport(state)
                        Spacer(Modifier.height(4.dp))
                        CurrentAnalysis(state, viewport, engine, settings, onRetry, Modifier.weight(1f).fillMaxWidth())
                    }
                }
                AnalysisTabs(state)
            }
        }
    }
}

@Composable
private fun RecordBoard(state: RecordUiState, board: BoardSnapshot, engine: EngineUiState, settings: AppPreferences, manualInput: Boolean, modifier: Modifier) {
    GoBoard(state, board, engine.analysisFor(state.position), settings.coordinates, settings.showCandidateMoves, modifier, settings.touchOffsetPx, manualInput)
}

@Composable
private fun CurrentAnalysis(state: RecordUiState, viewport: TreeViewportState, engine: EngineUiState, settings: AppPreferences, onRetry: () -> Unit, modifier: Modifier) {
    if (state.tab == 0) AnalysisPanel(state, engine, settings.engineEnabled, onRetry, modifier, settings.showCandidateMoves)
    else TreePanel(state, viewport, settings.engineEnabled, state::chooseNode, modifier)
}

@Composable
private fun RecordHeader(state: RecordUiState, settings: AppPreferences, engine: EngineUiState, onEnabled: (Boolean) -> Unit,
    onSettings: () -> Unit, compact: Boolean, onPass: () -> Unit, canPass: Boolean,
    onImport: () -> Unit, onExport: () -> Unit, onResumeAutomatic: () -> Unit, onNewGame: () -> Unit) {
    val resources = LocalResources.current
    var confirmClear by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = if (compact) 6.dp else 20.dp, end = if (compact) 0.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(buildAnnotatedString {
                append("Kata")
                withStyle(SpanStyle(color = GoColors.Primary)) { append("Droid") }
            }, fontSize = if (compact) 18.sp else 21.sp, letterSpacing = (-.6).sp,
                fontWeight = FontWeight.SemiBold, color = GoColors.Ink, modifier = Modifier.weight(1f))
            EngineSwitch(settings.engineEnabled, engine.phase, onEnabled)
            Spacer(Modifier.width(4.dp))
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).semantics { contentDescription = resources.getString(R.string.more_options) }) { GoIcon(GoSymbol.More) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.width(232.dp), shape = RoundedCornerShape(16.dp), containerColor = Color(0xFFF0F4E8)) {
                    DropdownMenuItem(text = { Text(resources.getString(R.string.pass_move), fontSize = 13.sp) }, enabled = canPass,
                        onClick = { menu = false; onPass() }, modifier = Modifier.testTag("pass-move"))
                    if (settings.hasAutomaticPlayer) {
                        val active = state.autoPlayArmed && settings.engineEnabled
                        DropdownMenuItem(text = { Text(if (active) resources.getString(R.string.pause_auto) else resources.getString(R.string.resume_auto), fontSize = 13.sp) },
                            onClick = { menu = false; if (active) state.pauseAutomatic() else onResumeAutomatic() },
                            enabled = state.preview == null, modifier = Modifier.testTag("automatic-control"))
                    }
                    DropdownMenuItem(text = { Text(resources.getString(R.string.import_sgf), fontSize = 13.sp) }, onClick = { menu = false; onImport() }, modifier = Modifier.testTag("menu-import-sgf"))
                    DropdownMenuItem(text = { Text(resources.getString(R.string.export_sgf), fontSize = 13.sp) }, onClick = { menu = false; onExport() }, modifier = Modifier.testTag("menu-export-sgf"))
                    DropdownMenuItem(text = { Text(resources.getString(R.string.clear_board), fontSize = 13.sp) }, onClick = { menu = false; confirmClear = true }, modifier = Modifier.testTag("menu-clear-board"))
                    DropdownMenuItem(text = { Text(resources.getString(R.string.settings), fontSize = 13.sp) }, onClick = { menu = false; onSettings() }, modifier = Modifier.testTag("menu-settings"))
                }
            }
        }
        Text(resources.getString(R.string.record_subtitle, state.displayName(resources), resources.getString(state.rules.labelRes), state.komi.toString()),
            fontSize = 11.sp, lineHeight = 15.sp, color = GoColors.Muted,
            modifier = Modifier.fillMaxWidth().padding(start = if (compact) 6.dp else 20.dp, end = 16.dp, bottom = 4.dp).testTag("record-description"))
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(resources.getString(R.string.clear_board)) },
        text = { Text(resources.getString(R.string.clear_board_description)) },
        confirmButton = { TextButton(onClick = { confirmClear = false; onNewGame() }, modifier = Modifier.testTag("confirm-clear-board")) { Text(resources.getString(R.string.clear_board)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(resources.getString(R.string.cancel)) } })
}

@Composable
private fun EngineSwitch(enabled: Boolean, phase: EnginePhase, onEnabled: (Boolean) -> Unit) {
    val resources = LocalResources.current
    val status = if (!enabled) resources.getString(R.string.status_off) else when (phase) {
        EnginePhase.STARTING -> resources.getString(R.string.status_starting)
        EnginePhase.ANALYZING -> resources.getString(R.string.status_analyzing)
        EnginePhase.ERROR -> resources.getString(R.string.status_error)
        EnginePhase.SUSPENDED -> resources.getString(R.string.status_suspended)
        else -> resources.getString(R.string.status_on)
    }
    val color = if (enabled) GoColors.Primary else GoColors.Muted
    Row(Modifier.height(48.dp).clip(RoundedCornerShape(16.dp))
        .background(if (enabled) Color(0xFFE3EDD9) else Color(0xFFE9ECE3))
        .testTag("engine-switch").semantics { contentDescription = resources.getString(R.string.engine_switch); stateDescription = status }
        .toggleable(enabled, role = Role.Switch, onValueChange = onEnabled)
        .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Column {
            Text("KataGo", fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, color = color)
            Text(status, fontSize = 9.sp, lineHeight = 13.sp, color = color, modifier = Modifier.testTag("engine-status"))
        }
        Canvas(Modifier.size(31.dp, 20.dp)) {
            val scale = size.height / 20f
            drawRoundRect(if (enabled) GoColors.Primary else Color(0xFFDCE1D4), cornerRadius = CornerRadius(size.height / 2))
            drawRoundRect(if (enabled) GoColors.Primary else Color(0xFF85927C), cornerRadius = CornerRadius(size.height / 2), style = Stroke(scale))
            val center = Offset((if (enabled) 21f else 9f) * scale, 10f * scale)
            drawCircle(if (enabled) Color.White else Color(0xFF6B7962), (if (enabled) 7f else 6f) * scale, center)
            if (enabled) drawPath(Path().apply {
                moveTo(center.x - 3f * scale, center.y)
                lineTo(center.x - scale, center.y + 2f * scale)
                lineTo(center.x + 3f * scale, center.y - 2f * scale)
            }, GoColors.Primary, style = Stroke(1.2f * scale))
        }
    }
}

@Composable
private fun Players(state: RecordUiState, board: BoardSnapshot, settings: AppPreferences, onResumeAutomatic: () -> Unit,
                    modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val automaticPaused = settings.automatic(if (board.blackToMove) 1 else 2) &&
        (!state.autoPlayArmed || !settings.engineEnabled) && state.preview == null && !board.finished
    val status = state.moveError?.let { resources.getString(it) } ?: state.preview?.let { resources.getString(R.string.preview_label, it.label) } ?: when {
        board.finished -> resources.getString(R.string.game_over)
        settings.automatic(if (board.blackToMove) 1 else 2) -> if (state.autoPlayArmed && settings.engineEnabled) resources.getString(R.string.player_thinking, resources.getString(if (board.blackToMove) R.string.black else R.string.white)) else resources.getString(R.string.auto_paused)
        else -> resources.getString(R.string.player_turn, resources.getString(if (board.blackToMove) R.string.black else R.string.white))
    }
    Row(modifier.heightIn(min = 52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PlayerStone(true, Modifier.size(20.dp))
            PlayerDetails(state, true, settings.autoBlack, board.blackCaptures, Modifier.weight(1f))
        }
        if (automaticPaused) {
            TextButton(onClick = onResumeAutomatic,
                modifier = Modifier.heightIn(min = 48.dp).testTag("resume-automatic")
                    .semantics { contentDescription = resources.getString(R.string.resume_auto) },
                colors = ButtonDefaults.textButtonColors(containerColor = GoColors.Container, contentColor = GoColors.Primary),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(status, fontSize = 10.sp, lineHeight = 14.sp,
                        modifier = Modifier.testTag("turn-label"))
                    Text(resources.getString(R.string.resume_auto_action), fontSize = 12.sp, lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            Text(status, fontSize = 10.sp, lineHeight = 14.sp, color = GoColors.Primary,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(GoColors.Container).padding(horizontal = 8.dp, vertical = 4.dp).testTag("turn-label"))
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            PlayerDetails(state, false, settings.autoWhite, board.whiteCaptures, Modifier.weight(1f).padding(end = 4.dp))
            PlayerStone(false, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PlayerDetails(state: RecordUiState, black: Boolean, automatic: Boolean, captures: Int, modifier: Modifier) {
    val resources = LocalResources.current
    val identity = PlayerIdentity.from(state.nodes.first().properties, black)
    val name = identity.name ?: resources.getString(if (black) R.string.black else R.string.white)
    val label = identity.rank?.let { resources.getString(R.string.player_name_rank, name, it) } ?: name
    Column(modifier, horizontalAlignment = if (black) Alignment.Start else Alignment.End) {
        Text(label, fontSize = 12.sp, lineHeight = 16.sp, color = GoColors.Ink,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag(if (black) "black-player-name" else "white-player-name"))
        Text(resources.getString(R.string.player_mode_captures,
            resources.getString(if (automatic) R.string.mode_auto else R.string.mode_manual), captures),
            fontSize = 9.sp, lineHeight = 12.sp, color = GoColors.Muted)
    }
}

@Composable
private fun Transport(state: RecordUiState) {
    val resources = LocalResources.current
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.weight(1f).padding(start = 5.dp).testTag("move-indicator"), verticalAlignment = Alignment.CenterVertically) {
            Text(state.node.move.toString(), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = GoColors.Ink)
            Text(if (state.preview != null) resources.getString(R.string.preview_move_suffix, state.previewStep) else resources.getString(R.string.move_location_suffix, state.node.moveLabel(resources)), fontSize = 11.sp, color = GoColors.Muted, maxLines = 1)
        }
        TransportButton(GoSymbol.First, resources.getString(R.string.first_move), !state.atStart, state::first)
        TransportButton(GoSymbol.Previous, resources.getString(R.string.previous_move), !state.atStart, state::previous)
        TransportButton(GoSymbol.Next, resources.getString(R.string.next_move), !state.atEnd, state::next)
        TransportButton(GoSymbol.Last, resources.getString(R.string.last_move), !state.atEnd, state::last)
    }
}

@Composable
private fun TransportButton(symbol: GoSymbol, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(42.dp, 48.dp).semantics { contentDescription = label }) {
        GoIcon(symbol, Modifier.size(21.dp), if (enabled) GoColors.Ink else GoColors.Muted.copy(alpha = .35f))
    }
}

@Composable
private fun AnalysisTabs(state: RecordUiState, insetBottom: Boolean = true) {
    val resources = LocalResources.current
    Row(Modifier.fillMaxWidth().background(GoColors.Navigation).border(.5.dp, GoColors.Outline)
        .then(if (insetBottom) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) else Modifier)
        .padding(start = 14.dp, end = 14.dp, top = 5.dp, bottom = 3.dp).height(44.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(resources.getString(R.string.tab_chart) to GoSymbol.Chart, resources.getString(R.string.tab_tree) to GoSymbol.Tree).forEachIndexed { index, (label, icon) ->
            Row(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(24.dp))
                .background(if (state.tab == index) GoColors.Surface else Color.Transparent)
                .testTag(if (index == 0) "tab-chart" else "tab-tree")
                .selectable(selected = state.tab == index, role = Role.Tab) { if (index == 1) state.exitPreview(); state.tab = index },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally)) {
                GoIcon(icon, Modifier.size(16.dp), if (state.tab == index) GoColors.Primary else GoColors.Muted)
                Text(label, fontSize = 13.sp, fontWeight = if (state.tab == index) FontWeight.SemiBold else FontWeight.Normal, color = if (state.tab == index) GoColors.Primary else GoColors.Muted)
            }
        }
    }
}
