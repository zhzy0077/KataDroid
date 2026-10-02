package io.github.zhzy0077.katadroid

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import io.github.zhzy0077.katadroid.engine.GoRules
import io.github.zhzy0077.katadroid.sgf.SgfCodec
import io.github.zhzy0077.katadroid.sgf.SgfGame
import io.github.zhzy0077.katadroid.ui.AppPreferences
import io.github.zhzy0077.katadroid.ui.record.BoardPoint
import io.github.zhzy0077.katadroid.ui.record.Candidate
import io.github.zhzy0077.katadroid.ui.record.RecordViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Chart gestures are exercised on the AVD, including letterboxing in both orientations. */
class WinRateNavigationUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var document: RecordViewModel

    @Before fun reset() {
        ui.runOnUiThread {
            document = ViewModelProvider(ui.activity)[RecordViewModel::class.java]
            document.updatePreferences(AppPreferences(engineEnabled = false))
            document.record.newGame()
        }
        ui.waitForIdle()
    }

    @After fun cleanup() {
        ui.runOnUiThread {
            ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            document.updatePreferences(AppPreferences(engineEnabled = false))
            document.record.reset()
        }
        ui.waitForIdle()
    }

    private fun load(sgf: String) {
        val game = SgfGame.from(SgfCodec.parse(sgf).single(), GoRules.CHINESE, 7.5f)
        ui.runOnIdle { document.record.load(game, "chart.sgf") }
    }

    private fun tapMove(move: Int, endMove: Int) {
        ui.onNodeWithTag("win-rate-chart").performTouchInput {
            // The design's plot spans x=30..290 in a centered 300 x 98 canvas.
            val scale = minOf(width / 300f, height / 98f)
            click(Offset((width - 300f * scale) / 2f + (30f + 260f * move / endMove) * scale, height / 2f))
        }
        ui.waitForIdle()
    }

    private fun boardState() = ui.onNodeWithTag("go-board").fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    @Test fun tappingBackAndForwardPreservesTheChosenBranchAndPausesAutomation() {
        load("(;SZ[19];B[dd];W[pp](;B[qd];W[dp]C[main])(;B[pd];W[dp];B[jj];W[]C[side]))")
        val record = document.record
        val leaf = record.nodes.single { it.properties["C"] == listOf("side") }.id
        ui.runOnIdle {
            record.chooseNode(leaf)
            document.updatePreferences(document.preferences.copy(autoBlack = true, autoWhite = true))
            record.resumeAutomatic()
        }
        val branchBoard = boardState()
        assertTrue(record.autoPlayArmed)
        tapMove(2, 6)
        assertEquals(2, record.node.move)
        assertEquals(leaf, record.routeLeafId)
        assertFalse(record.autoPlayArmed)
        ui.onNodeWithTag("win-rate-value").assertTextEquals("—") // Unanalyzed hands remain navigable.
        tapMove(6, 6)
        assertEquals(leaf, record.selectedId)
        assertEquals(branchBoard, boardState())
        tapMove(2, 6)
        tapMove(6, 6)
        assertEquals(leaf, record.selectedId)
        ui.activityRule.scenario.recreate()
        tapMove(0, 6)
        tapMove(6, 6)
        assertEquals(branchBoard, boardState())
    }

    @Test fun accessibleSeekingHandlesCommentNodesDeclaredPlayerAndPasses() {
        load("(;SZ[19]PL[W];C[setup];W[dd];W[pp];PL[W]C[white-again];W[];C[after-pass])")
        val record = document.record
        ui.onNodeWithTag("win-rate-chart").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(2f)) }
        assertEquals(listOf("white-again"), record.node.properties["C"])
        assertEquals(2, record.position.nextPlayer)
        tapMove(0, 3)
        assertEquals(listOf("setup"), record.node.properties["C"])
        tapMove(3, 3)
        assertEquals(listOf("after-pass"), record.node.properties["C"])
        assertEquals(io.github.zhzy0077.katadroid.engine.PASS, record.position.moves.last())
        val beforePlayerChange = record.nodes.single { it.move == 2 && it.color == 2 }.id
        ui.runOnIdle { record.chooseNode(beforePlayerChange) }
        tapMove(2, 3)
        assertEquals(beforePlayerChange, record.selectedId) // Current same-hand node must stay exact.
    }

    @Test fun previewSamplesNeverBecomeRecordNodesAndHistoryTapExitsPreview() {
        load("(;SZ[19];B[dd];W[pp];B[qd];W[dp])")
        val record = document.record
        val leaf = record.selectedId
        val nodes = record.nodes
        ui.runOnIdle { record.startPreview(Candidate("A", listOf("K10", "K11", "L10").map { BoardPoint.parse(it).index })) }
        tapMove(7, 7)
        assertNotNull(record.preview)
        assertEquals(leaf, record.selectedId)
        assertEquals(nodes, record.nodes)
        tapMove(2, 7)
        assertNull(record.preview)
        assertEquals(2, record.node.move)
        assertFalse(record.autoPlayArmed)
        assertEquals(leaf, record.routeLeafId)
        tapMove(4, 4)
        assertEquals(leaf, record.selectedId)
        assertEquals(nodes, record.nodes)
    }

    @Test fun firstMoveCanBeSelectedAfterAnEmptyBoardAndInLandscape() {
        // 0 and 1 moves have the same minimum drawing extent; the gesture must update anyway.
        ui.onNodeWithTag("win-rate-chart").assertIsDisplayed()
        ui.runOnIdle { assertTrue(document.record.play(BoardPoint.parse("D16").index)) }
        val played = boardState()
        tapMove(0, 1)
        assertEquals(0, document.record.node.move)
        tapMove(1, 1)
        assertEquals(played, boardState())
        ui.runOnUiThread { ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        ui.waitUntil(5000) { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        tapMove(0, 1)
        assertEquals(0, document.record.node.move)
        tapMove(1, 1)
        assertEquals(played, boardState())
    }
}
