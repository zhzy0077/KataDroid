package io.github.zhzy0077.katadroid

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.zhzy0077.katadroid.engine.AnalysisCandidate
import io.github.zhzy0077.katadroid.engine.BoardSnapshot
import io.github.zhzy0077.katadroid.engine.EngineUiState
import io.github.zhzy0077.katadroid.engine.PASS
import io.github.zhzy0077.katadroid.engine.PositionAnalysis
import io.github.zhzy0077.katadroid.ui.record.AnalysisPanel
import io.github.zhzy0077.katadroid.ui.record.BoardPoint
import io.github.zhzy0077.katadroid.ui.record.GoBoard
import io.github.zhzy0077.katadroid.ui.record.RecordUiState
import io.github.zhzy0077.katadroid.ui.theme.KataDroidTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Deterministic PV fixtures exercise each preview entry point and a narrow footer. */
class PreviewUiTest {
    @get:Rule(order = 0) val locale = AppLocaleRule("zh")
    @get:Rule(order = 1) val ui = createComposeRule()
    private lateinit var record: RecordUiState
    private val moves = listOf("D16", "Q4", "C16", "R4", "K10", "D4", "Q16", "C4")
        .map { BoardPoint.parse(it).index }

    private fun show(pv: List<Int> = moves) {
        record = RecordUiState().apply { newGame() }
        val base = record.position
        val analysis = PositionAnalysis(base, 100, 50f, 0f, 0f,
            listOf(AnalysisCandidate("A", pv.first(), 100, 50f, 0f, pv)), "CPU")
        val engine = EngineUiState(analyses = mapOf(base.key to analysis))
        ui.setContent {
            KataDroidTheme {
                Column(Modifier.width(320.dp)) {
                    GoBoard(record, BoardSnapshot.from(record.position), analysis, true, true, Modifier.size(320.dp))
                    AnalysisPanel(record, engine, false, {}, Modifier.fillMaxWidth().height(210.dp))
                }
            }
        }
    }

    private fun checkAndExit(pv: List<Int>) {
        assertEquals(7, record.previewStep)
        assertEquals(pv.take(7), record.preview!!.moves)
        ui.onNodeWithTag("preview-moves").assertIsDisplayed()
        ui.onNodeWithTag("exit-preview").assertIsDisplayed()
        val layout = mutableListOf<TextLayoutResult>()
        ui.onNodeWithTag("preview-moves").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        assertFalse("Seven preview coordinates must remain readable", layout.single().hasVisualOverflow)
        val line = ui.onNodeWithTag("preview-moves").fetchSemanticsNode().boundsInRoot
        val exit = ui.onNodeWithTag("exit-preview").fetchSemanticsNode().boundsInRoot
        assertTrue("Coordinate text must leave room for Exit preview", line.right <= exit.left)
        ui.onNodeWithTag("exit-preview").performClick()
        assertNull(record.preview)
        assertTrue(record.position.moves.isEmpty())
        assertEquals(1, record.nodes.size)
    }

    @Test fun physicalLongPressUsesSevenMoves() {
        show()
        val point = BoardPoint.fromIndex(moves.first())!!
        ui.onNodeWithTag("go-board").performTouchInput {
            longClick(Offset(width * (41 + point.column * 31) / 640f, height * (41 + point.row * 31) / 640f))
        }
        checkAndExit(moves)
    }

    @Test fun accessibleLongClickUsesSevenMoves() {
        show()
        ui.onNodeWithTag("candidate-A").performSemanticsAction(SemanticsActions.OnLongClick) { assertTrue(it()) }
        checkAndExit(moves)
    }

    @Test fun passCandidateUsesTheSameLimitAndKeepsExitVisible() {
        val pv = listOf(PASS) + moves
        show(pv)
        ui.onNodeWithTag("candidate-pass").performClick()
        checkAndExit(pv)
    }
}
