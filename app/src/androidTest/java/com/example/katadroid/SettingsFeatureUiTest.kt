package com.example.katadroid

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.example.katadroid.engine.BoardSnapshot
import com.example.katadroid.engine.EnginePhase
import com.example.katadroid.engine.GoRules
import com.example.katadroid.engine.KataGoViewModel
import com.example.katadroid.ui.AppPreferences
import com.example.katadroid.ui.record.BoardPoint
import com.example.katadroid.ui.record.RecordViewModel
import java.io.File
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SettingsFeatureUiTest {
    @get:Rule(order = 0) val locale = AppLocaleRule("zh")
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var document: RecordViewModel
    private lateinit var engine: KataGoViewModel

    @Before fun freshManualGame() {
        ui.runOnUiThread {
            document = ViewModelProvider(ui.activity)[RecordViewModel::class.java]
            engine = ViewModelProvider(ui.activity)[KataGoViewModel::class.java]
            document.updatePreferences(AppPreferences(engineEnabled = false))
            document.record.applyRules(GoRules.CHINESE, 7.5f)
            document.record.newGame()
        }
        ui.waitForIdle()
    }

    @After fun restoreDefaults() {
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        ui.runOnUiThread {
            document.updatePreferences(AppPreferences(engineEnabled = false))
            document.record.applyRules(GoRules.CHINESE, 7.5f)
            document.record.reset()
            ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        ui.waitForIdle()
    }

    private fun openSettings() {
        ui.onNodeWithContentDescription("更多选项").performClick()
        ui.onNodeWithText("设置").performClick()
    }
    private fun setEngine(enabled: Boolean) {
        val on = ui.onNodeWithTag("engine-switch").fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.On
        if (on != enabled) ui.onNodeWithTag("engine-switch").performClick()
    }
    private fun count() = ui.runOnIdle { document.record.basePosition.moves.size }
    private fun waitForMoves(expected: Int) = ui.waitUntil(30000) { document.record.basePosition.moves.size >= expected }
    private fun waitForReady() = ui.waitUntil(30000) { engine.controller.state.value.phase == EnginePhase.READY }
    private fun play(coordinate: String) {
        val point = BoardPoint.parse(coordinate)
        val offset = document.preferences.touchOffsetPx
        ui.onNodeWithTag("board-input").performTouchInput {
            click(Offset(width * (41 + point.column * 31) / 640f, width * (41 + point.row * 31) / 640f + offset))
        }
    }
    private fun playLegal() {
        val point = ui.runOnIdle {
            val board = BoardSnapshot.from(document.record.position)
            BoardPoint.fromIndex((0..360).first(board::isLegal))!!.label
        }
        play(point)
    }

    @Test fun customSearchRulesAndKomiPersistAndDriveActualAnalysis() {
        openSettings()
        ui.onNodeWithTag("search-limit-input").performTextReplacement("0")
        ui.onNodeWithTag("apply-settings").assertIsNotEnabled()
        ui.onNodeWithTag("search-limit-input").performTextReplacement("333")
        assertEquals(500, document.preferences.maxVisits) // Entire draft is still pending.
        ui.onNodeWithTag("rule-japanese").performScrollTo().performClick()
        ui.onNodeWithTag("komi-input").performTextReplacement("-2.25")
        ui.onNodeWithTag("apply-settings").performClick()
        ui.waitUntil(5000) { !document.busy && document.record.rules == GoRules.JAPANESE }
        assertEquals(-2.25f, document.record.komi, 0f)
        ui.activityRule.scenario.recreate()
        assertEquals(333, AppPreferences.load(ui.activity).maxVisits)
        assertEquals(GoRules.JAPANESE, AppPreferences.load(ui.activity).rules)
        assertEquals(-2.25f, AppPreferences.load(ui.activity).komi, 0f)
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithTag("record-description").assertTextContains("日本 · 贴 -2.25", substring = true)
        setEngine(true)
        waitForReady()
        assertEquals(333, engine.controller.state.value.completedAnalysis?.visits)
        assertEquals(-2.25f, engine.controller.state.value.completedAnalysis!!.position.komi, 0f)
        assertEquals(GoRules.JAPANESE, engine.controller.state.value.completedAnalysis!!.position.rules)
    }

    @Test fun offsetsUsePhysicalPixelsAndSupportDraggingBottomRowAndCancellation() {
        for (offset in listOf(0, 60, 120)) {
            openSettings()
            ui.onNodeWithTag("touch-offset-$offset").performScrollTo().performClick()
            if (offset != document.preferences.touchOffsetPx) ui.onNodeWithTag("apply-settings").performClick()
            ui.waitUntil(5000) { !document.busy && document.preferences.touchOffsetPx == offset }
            ui.onNodeWithContentDescription("返回").performClick()
            ui.runOnIdle { document.record.newGame() }
            val square = ui.onNodeWithTag("go-board").fetchSemanticsNode().boundsInRoot
            val input = ui.onNodeWithTag("board-input").fetchSemanticsNode().boundsInRoot
            assertEquals(offset.toFloat(), input.height - square.height, 1f)
            play("D16")
            assertEquals(BoardPoint.parse("D16").index, document.record.position.moves.single())
            ui.onNodeWithTag("board-input").performTouchInput {
                down(Offset(width * (41 + 6 * 31) / 640f, width * (41 + 6 * 31) / 640f + offset))
                moveTo(Offset(width * 599 / 640f, width * 599 / 640f + offset), delayMillis = 100)
            }
            ui.onNodeWithTag("placement-preview").assertContentDescriptionEquals("松手落子：T1")
            assertEquals(1, count())
            val ghost = ui.onNodeWithTag("placement-preview").fetchSemanticsNode().boundsInRoot
            assertEquals(square.top + square.width * 599 / 640f, ghost.center.y, 2f)
            ui.onNodeWithTag("board-input").performTouchInput { up() }
            assertEquals(BoardPoint.parse("T1").index, document.record.position.moves.last())
            assertEquals(2, count())
            ui.onNodeWithTag("board-input").performTouchInput {
                down(center); moveTo(center + Offset(30f, 0f)); cancel()
            }
            ui.onNodeWithTag("placement-preview").assertDoesNotExist()
            assertEquals(2, count())
            ui.onNodeWithTag("board-input").performTouchInput {
                down(center); moveTo(Offset(-100f, -100f)); up()
            }
            assertEquals(2, count())
        }
        ui.activityRule.scenario.recreate()
        assertEquals(120, AppPreferences.load(ui.activity).touchOffsetPx)
        assertEquals(2, count())
    }

    @Test fun landscapeUsesAvailableHeightAndRetainsTheGame() {
        play("Q16")
        val original = document.record.position
        ui.runOnUiThread { ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        ui.waitUntil(10000) {
            runCatching { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                ui.onRoot().fetchSemanticsNode().boundsInRoot.width > ui.onRoot().fetchSemanticsNode().boundsInRoot.height }.getOrDefault(false)
        }
        ui.waitForIdle()
        val root = ui.onRoot().fetchSemanticsNode().boundsInRoot
        val board = ui.onNodeWithTag("go-board").fetchSemanticsNode().boundsInRoot
        assertEquals(board.width, board.height, 1f)
        assertTrue("Landscape board should occupy at least 80% of screen height: $board / $root", board.height > root.height * .8f)
        assertTrue(ui.onNodeWithTag("engine-switch").fetchSemanticsNode().boundsInRoot.left > board.right)
        ui.onNodeWithTag("tab-tree").assertIsDisplayed().performClick()
        ui.onNodeWithContentDescription("上一手").assertIsDisplayed().performClick()
        ui.onNodeWithContentDescription("下一手").performClick()
        assertEquals(original, document.record.position)
        ui.activityRule.scenario.recreate()
        assertEquals(original, document.record.position)
    }

    @Test fun eitherColorCanMoveAutomaticallyWithOneVisitAndHistoryPausesIt() {
        ui.runOnIdle { document.updatePreferences(document.preferences.copy(maxVisits = 1, autoBlack = true, engineEnabled = true)) }
        waitForMoves(1)
        assertEquals(listOf(1), document.record.position.moveColors)
        playLegal() // Manual white, then automatic black.
        waitForMoves(3)
        assertEquals(listOf(1, 2, 1), document.record.position.moveColors)
        setEngine(false)
        playLegal()
        assertEquals(4, count())
        ui.mainClock.advanceTimeBy(1000)
        assertEquals(4, count())
        setEngine(true)
        waitForMoves(5)
        ui.onNodeWithContentDescription("上一手").performClick()
        waitForReady()
        ui.mainClock.advanceTimeBy(1000)
        assertEquals(4, count())
        assertFalse(document.record.autoPlayArmed)
        ui.runOnIdle { document.updatePreferences(document.preferences.copy(autoBlack = false, autoWhite = true)) }
        playLegal() // Manual black, then automatic white.
        waitForMoves(6)
        assertEquals(2, document.record.position.moveColors.last())
        assertEquals(6, count())
    }

    @Test fun pausedAutomaticTurnHasDirectResumeAndReenablesTheEngine() {
        ui.runOnIdle {
            document.updatePreferences(document.preferences.copy(maxVisits = 1, autoBlack = true, engineEnabled = false))
            document.record.pauseAutomatic()
        }
        ui.onNodeWithTag("turn-label", useUnmergedTree = true).assertTextEquals("自动已暂停")
        ui.onNodeWithTag("resume-automatic").assertIsDisplayed().assertIsEnabled().performClick()
        waitForMoves(1)
        assertTrue(document.preferences.engineEnabled)
        assertTrue(document.record.autoPlayArmed)
        assertEquals(listOf(1), document.record.position.moveColors)
        ui.onNodeWithTag("resume-automatic").assertDoesNotExist() // White is manual.

        ui.onNodeWithContentDescription("上一手").performClick()
        assertFalse(document.record.autoPlayArmed)
        ui.onNodeWithTag("resume-automatic").assertIsDisplayed().performClick()
        waitForMoves(1)
        assertEquals(listOf(1), document.record.position.moveColors)
    }

    @Test fun resumeActionSurvivesRecreationAndLandscapeButStaysHiddenDuringPreviewAndAfterGameOver() {
        ui.runOnIdle {
            document.updatePreferences(document.preferences.copy(maxVisits = 1, autoBlack = true, autoWhite = true, engineEnabled = false))
            document.record.pauseAutomatic()
        }
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("resume-automatic").assertIsDisplayed()
        ui.runOnUiThread { ui.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        ui.waitUntil(10000) { ui.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        ui.onNodeWithTag("resume-automatic").assertIsDisplayed().assertIsEnabled()
        ui.runOnIdle {
            document.record.startPreview(com.example.katadroid.ui.record.Candidate("A", listOf(60)))
        }
        ui.onNodeWithTag("resume-automatic").assertDoesNotExist()
        ui.runOnIdle { document.record.exitPreview() }
        ui.onNodeWithTag("resume-automatic").assertIsDisplayed()
        ui.runOnIdle {
            assertTrue(document.record.play(com.example.katadroid.engine.PASS))
            assertTrue(document.record.play(com.example.katadroid.engine.PASS))
        }
        ui.onNodeWithTag("resume-automatic").assertDoesNotExist()
        ui.onNodeWithTag("turn-label").assertTextEquals("棋局结束")
    }

    @Test fun selfPlayStopsForSettingsBackgroundAndEngineOff() {
        ui.runOnIdle { document.updatePreferences(document.preferences.copy(maxVisits = 32, autoBlack = true, autoWhite = true, engineEnabled = true)) }
        waitForMoves(4)
        openSettings()
        ui.waitUntil(5000) { engine.controller.state.value.phase == EnginePhase.SUSPENDED }
        val paused = count()
        ui.mainClock.advanceTimeBy(1000)
        assertEquals(paused, count())
        ui.onNodeWithContentDescription("返回").performClick()
        waitForMoves(paused + 1)
        ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val backgroundCount = document.record.position.moves.size
        assertEquals(EnginePhase.SUSPENDED, engine.controller.state.value.phase)
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForMoves(backgroundCount + 1)
        setEngine(false)
        val offCount = count()
        ui.mainClock.advanceTimeBy(1000)
        assertEquals(offCount, count())
        assertEquals(EnginePhase.OFF, engine.controller.state.value.phase)
        ui.onNodeWithContentDescription("上一手").performClick()
        assertFalse(document.record.autoPlayArmed)
        ui.onNodeWithContentDescription("更多选项").performClick()
        ui.onNodeWithTag("automatic-control").performClick()
        waitForMoves(offCount + 1)
        setEngine(false)
    }

    @Test fun fileOperationsKeepBranchesAndFailedImportKeepsTheCurrentDocument() {
        val directory = ui.activity.cacheDir
        val source = File(directory, "roundtrip-input.sgf")
        val output = File(directory, "roundtrip-output.sgf")
        val bad = File(directory, "bad-input.sgf")
        try {
            source.writeText("(;CA[UTF-8]SZ[19]RU[Japanese]KM[0]GN[文件测试]C[保留注释];B[dd](;W[pq])(;W[qp]))")
            ui.runOnIdle { document.importSgf(Uri.fromFile(source)) }
            ui.waitUntil(5000) { !document.busy && document.record.recordName == "文件测试" }
            assertFalse(document.record.autoPlayArmed)
            assertEquals(2, count())
            val before = document.record.exportSgf()
            bad.writeText("(;SZ[19];B[dd];W[dd])")
            ui.runOnIdle { document.importSgf(Uri.fromFile(bad)) }
            ui.waitUntil(5000) { !document.busy }
            assertEquals(before, document.record.exportSgf())
            ui.runOnIdle { document.prepareExport(); document.exportSgf(Uri.fromFile(output)) }
            ui.waitUntil(5000) { !document.busy && output.exists() }
            assertEquals(before, output.readText())
            ui.activityRule.scenario.recreate()
            assertEquals(before, document.record.exportSgf())
            source.writeText("(;SZ[19]GN[一局])(;SZ[19]GN[二局];W[qq])")
            ui.runOnIdle { document.importSgf(Uri.fromFile(source)) }
            ui.waitUntil(5000) { document.importChoices.size == 2 }
            assertEquals(before, document.record.exportSgf())
            ui.onNodeWithText("2. 二局").performClick()
            ui.waitUntil(5000) { !document.busy && document.record.recordName == "二局" }
            assertEquals(listOf(2), document.record.position.moveColors)
        } finally { source.delete(); output.delete(); bad.delete() }
    }
}
