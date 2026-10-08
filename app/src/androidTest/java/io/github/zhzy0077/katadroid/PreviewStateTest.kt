package io.github.zhzy0077.katadroid

import io.github.zhzy0077.katadroid.ui.record.BoardPoint
import io.github.zhzy0077.katadroid.ui.record.Candidate
import io.github.zhzy0077.katadroid.ui.record.RecordUiState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PreviewStateTest {
    private val moves = listOf("D16", "Q4", "C16", "R4", "K10", "D4", "Q16", "C4")
        .map { BoardPoint.parse(it).index }
    private fun record() = RecordUiState().apply { newGame() }

    @Test fun previewCapsAtSevenAndNavigationNeverEditsTheGame() {
        val state = record()
        val nodes = state.nodes
        val original = state.exportSgf()
        val selected = state.selectedId
        state.startPreview(Candidate("A", moves))
        assertEquals(7, state.previewStep)
        assertEquals(moves.take(7), state.preview!!.moves)
        assertEquals(moves.take(7), state.position.moves)
        assertFalse(state.autoPlayArmed)
        assertTrue(state.atEnd)
        state.next()
        assertEquals(7, state.previewStep)
        state.first()
        assertTrue(state.atStart)
        assertTrue(state.position.moves.isEmpty())
        state.previous()
        assertEquals(0, state.previewStep)
        repeat(7) { index ->
            state.next()
            assertEquals(index + 1, state.previewStep)
            assertEquals(moves.take(index + 1), state.position.moves)
        }
        state.previous()
        assertEquals(6, state.previewStep)
        state.last()
        assertEquals(7, state.previewStep)
        assertEquals(selected, state.selectedId)
        assertEquals(nodes, state.nodes)
        assertEquals(original, state.exportSgf())
    }

    @Test fun activeAndPartiallySteppedSevenMovePreviewsSurviveDiskRestore() {
        val state = record()
        state.startPreview(Candidate("A", moves))
        for (step in listOf(7, 6, 0)) {
            while (state.previewStep > step) state.previous()
            val restored = RecordUiState.restore(state.serialize())
            assertEquals(step, restored.previewStep)
            assertEquals(moves.take(7), restored.preview!!.moves)
            assertEquals(state.position, restored.position)
            assertEquals(state.nodes, restored.nodes)
            assertFalse(restored.autoPlayArmed)
        }
    }

    @Test fun leavingASevenMovePreviewStillRestoresTheOriginalGame() {
        for (exit in listOf<(RecordUiState) -> Unit>({ it.exitPreview() }, { it.chooseNode(it.selectedId) })) {
            val state = record()
            val position = state.position
            state.startPreview(Candidate("A", moves))
            exit(state)
            assertNull(state.preview)
            val restored = RecordUiState.restore(state.serialize())
            assertNull(restored.preview)
            assertEquals(position, restored.position)
        }
    }

    @Test fun shortAndLegacyThreeMovePreviewsKeepTheirActualLength() {
        for (length in listOf(1, 3, 7)) {
            val state = record()
            state.startPreview(Candidate("B", moves.take(length)))
            assertEquals(length, state.previewStep)
            val restored = RecordUiState.restore(state.serialize())
            assertEquals(moves.take(length), restored.preview!!.moves)
            assertEquals(length, restored.previewStep)
        }
    }

    @Test fun emptyVariationDoesNotStartAPreview() {
        val state = record()
        val before = state.serialize()
        state.startPreview(Candidate("A", emptyList()))
        assertEquals(before, state.serialize())
    }

    @Test fun overlongPersistedPreviewIsRejected() {
        val state = record()
        state.startPreview(Candidate("A", moves))
        val invalid = JSONObject(state.serialize()).put("previewMoves", JSONArray(moves)).put("previewStep", 8)
        assertThrows(IllegalArgumentException::class.java) { RecordUiState.restore(invalid.toString()) }
    }
}
