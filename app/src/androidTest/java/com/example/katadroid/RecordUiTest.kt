package com.example.katadroid

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import androidx.compose.ui.state.ToggleableState
import com.example.katadroid.ui.record.BoardPoint
import com.example.katadroid.ui.record.RecordViewModel
import com.example.katadroid.ui.AppPreferences
import androidx.lifecycle.ViewModelProvider

/** Exercise state/gesture boundaries that static screenshots cannot validate. */
class RecordUiTest {
    @get:Rule(order = 0) val locale = AppLocaleRule("zh")
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()

    private fun boardState() = ui.onNodeWithTag("go-board").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    private fun text(tag: String) = ui.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
    private fun setEngine(enabled: Boolean) {
        val on = ui.onNodeWithTag("engine-switch").fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.On
        if (on != enabled) ui.onNodeWithTag("engine-switch").performClick()
    }
    private fun awaitAnalysis() {
        ui.waitUntil(30000) { text("engine-status") == "已开启" && text("analysis-visits").startsWith("500 visits") }
    }
    private fun openSettings() {
        ui.onNodeWithContentDescription("更多选项").performClick()
        ui.onNodeWithText("设置").performClick()
    }
    private fun play(coordinate: String) {
        val point = BoardPoint.parse(coordinate)
        ui.onNodeWithTag("go-board").performTouchInput {
            click(Offset(width * (41 + point.column * 31) / 640f, height * (41 + point.row * 31) / 640f))
        }
    }

    @Before
    fun startFromPausedExample() {
        ui.runOnUiThread {
            val document = ViewModelProvider(ui.activity)[RecordViewModel::class.java]
            document.updatePreferences(AppPreferences(engineEnabled = false))
            document.record.applyRules(com.example.katadroid.engine.GoRules.CHINESE, 7.5f)
            document.record.reset()
        }
        ui.waitForIdle()
    }

    @Test
    fun longPressPreviewSurvivesRecreationAndRestoresTheOriginalPosition() {
        setEngine(true)
        awaitAnalysis()
        setEngine(false)
        val original = boardState()
        val originalRate = text("win-rate-value")
        ui.onNodeWithTag("tab-tree").performClick()
        ui.onNodeWithTag("candidate-A").performTouchInput { longClick() }
        ui.onNodeWithTag("tab-chart").assertIsSelected()
        val preview = boardState()
        assertTrue(preview.contains("A 分支预览"))
        assertNotEquals(original, preview)
        ui.onNodeWithContentDescription("下一手").assertIsNotEnabled()
        ui.onNodeWithContentDescription("上一手").performClick()
        assertNotEquals(preview, boardState())
        ui.onNodeWithContentDescription("下一手").performClick()
        ui.activityRule.scenario.recreate()
        assertEquals(preview, boardState())
        ui.onNodeWithTag("engine-status", useUnmergedTree = true).assertTextEquals("已暂停")
        ui.onNodeWithTag("exit-preview").performClick()
        assertEquals(original, boardState())
        assertEquals(originalRate, text("win-rate-value"))
    }

    @Test
    fun pausedAnalysisSurvivesTabsAndNewMovesNeverReuseOldCandidates() {
        setEngine(true)
        awaitAnalysis()
        val original = boardState()
        val rate = text("win-rate-value")
        val score = text("score-lead-value")
        val candidate = ui.onNodeWithTag("candidate-A").fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        setEngine(false)
        ui.onNodeWithTag("tab-tree").performClick()
        ui.onNodeWithTag("tab-chart").performClick()
        assertEquals(rate, text("win-rate-value"))
        assertEquals(score, text("score-lead-value"))
        assertEquals(candidate, ui.onNodeWithTag("candidate-A").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        play("T19")
        assertTrue(boardState().contains("第 69 手，T19"))
        ui.onNodeWithTag("win-rate-value").assertTextEquals("—")
        assertTrue(ui.onAllNodesWithTag("candidate-A").fetchSemanticsNodes().isEmpty())
        ui.onNodeWithContentDescription("上一手").performClick()
        assertEquals(original, boardState())
        assertEquals(rate, text("win-rate-value"))
        ui.activityRule.scenario.recreate()
        assertEquals(rate, text("win-rate-value"))
        ui.onNodeWithTag("engine-status", useUnmergedTree = true).assertTextEquals("已暂停")
        setEngine(true)
        assertEquals(rate, text("win-rate-value")) // Cached result stays visible during startup/update.
        awaitAnalysis()
        setEngine(false)
    }

    @Test
    fun bothPlayersCanPlaceStonesAndCaptureWhileEngineIsPaused() {
        ui.onNodeWithContentDescription("更多选项").performClick()
        ui.onNodeWithTag("menu-clear-board").performClick()
        ui.onNodeWithTag("confirm-clear-board").performClick()
        assertTrue(boardState().contains("空棋盘"))
        listOf("D16", "C16", "C17", "R3", "B16", "Q4", "C15").forEach(::play)
        assertTrue(boardState().contains("第 7 手，C15，黑 4 子，白 2 子"))
        ui.onNodeWithText("提子 1").assertIsDisplayed()
        val captured = boardState()
        play("D16") // Occupied: no extra move or analysis request.
        assertEquals(captured, boardState())
        ui.activityRule.scenario.recreate()
        assertEquals(captured, boardState())
        ui.onNodeWithTag("engine-status", useUnmergedTree = true).assertTextEquals("已暂停")
        ui.onNodeWithContentDescription("上一手").performClick()
        assertTrue(boardState().contains("黑 3 子，白 3 子"))
        ui.onNodeWithContentDescription("下一手").performClick()
        assertEquals(captured, boardState())
        repeat(2) {
            ui.onNodeWithContentDescription("更多选项").performClick()
            ui.onNodeWithTag("pass-move").performClick()
        }
        ui.onNodeWithTag("turn-label").assertTextEquals("棋局结束")
        setEngine(true)
        ui.waitUntil(30000) { text("analysis-title").startsWith("终局计分") }
        assertTrue(text("analysis-visits").startsWith("0 visits"))
        assertTrue(ui.onAllNodesWithTag("candidate-A").fetchSemanticsNodes().isEmpty())
        ui.onNodeWithContentDescription("更多选项").performClick()
        ui.onNodeWithTag("pass-move").assertIsNotEnabled()
    }

    @Test
    fun choosingAHistoricalBranchKeepsItsRouteWhenSteppingBackAndForward() {
        ui.onNodeWithTag("tab-tree").performClick()
        ui.onNodeWithTag("tree-node-right-3").performClick()
        val branchPosition = boardState()
        assertTrue(branchPosition.contains("第 66 手，S13"))
        ui.onNodeWithContentDescription("上一手").performClick()
        assertTrue(boardState().contains("第 65 手，S14"))
        ui.onNodeWithContentDescription("下一手").performClick()
        assertEquals(branchPosition, boardState())
        ui.onNodeWithContentDescription("最新一手").performClick()
        assertTrue(boardState().contains("第 72 手，Q10"))
        ui.onNodeWithContentDescription("下一手").assertIsNotEnabled()
        ui.onNodeWithContentDescription("回到开局").performClick()
        assertTrue(boardState().contains("空棋盘"))
        ui.onNodeWithContentDescription("上一手").assertIsNotEnabled()
    }

    @Test
    fun treePansAndPinchesWithoutMovingTheBoardAndCanLocateSelection() {
        ui.onNodeWithTag("tab-tree").performClick()
        val before = ui.onNodeWithTag("go-board").fetchSemanticsNode().boundsInRoot
        val original = boardState()
        ui.onNodeWithTag("tree-canvas").performTouchInput { swipeLeft() }
        assertEquals(before, ui.onNodeWithTag("go-board").fetchSemanticsNode().boundsInRoot)
        assertEquals(original, boardState())
        ui.onNodeWithContentDescription("放大变化树").performClick()
        ui.onNodeWithTag("tree-zoom").assertTextEquals("125%")
        ui.onNodeWithTag("tree-canvas").performTouchInput {
            pinch(center + Offset(-30f, 0f), center + Offset(-110f, 0f), center + Offset(30f, 0f), center + Offset(110f, 0f))
        }
        assertNotEquals("125%", ui.onNodeWithTag("tree-zoom").fetchSemanticsNode().config[SemanticsProperties.Text].single().text)
        ui.onNodeWithContentDescription("定位当前节点").performClick()
        ui.onNodeWithTag("tree-node-main-68").assertIsDisplayed()
        assertEquals(original, boardState())
        assertEquals(before, ui.onNodeWithTag("go-board").fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun settingsPreserveThePositionAndKeepModelDiagnosticsReachable() {
        ui.onNodeWithContentDescription("上一手").performClick()
        val original = boardState()
        ui.onNodeWithContentDescription("更多选项").performClick()
        ui.onNodeWithText("设置").performClick()
        val oldSetting = ui.onNodeWithTag("setting-coordinates").fetchSemanticsNode().config[SemanticsProperties.ToggleableState]
        ui.onNodeWithTag("setting-coordinates").performScrollTo().performClick()
        ui.activityRule.scenario.recreate()
        assertNotEquals(oldSetting, ui.onNodeWithTag("setting-coordinates").fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
        ui.onNodeWithTag("setting-coordinates").performScrollTo().performClick()
        ui.onNodeWithTag("apply-settings").performClick()
        ui.waitUntil(5000) { !ViewModelProvider(ui.activity)[RecordViewModel::class.java].busy }
        ui.onNodeWithTag("open-diagnostics").performScrollTo().performClick()
        ui.onNodeWithText("验证 KataGo 模型").assertIsDisplayed()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithContentDescription("返回").performClick()
        assertEquals(original, boardState())
    }
}
