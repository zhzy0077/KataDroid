package io.github.zhzy0077.katadroid

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import io.github.zhzy0077.katadroid.engine.*
import io.github.zhzy0077.katadroid.ui.AppPreferences
import io.github.zhzy0077.katadroid.ui.record.RecordViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Run only on the AVD along with ordinary UI regression tests. */
class EngineFeatureUiTest {
    @get:Rule(order = 0) val locale = AppLocaleRule("zh")
    @get:Rule(order = 1) val ui = createAndroidComposeRule<MainActivity>()
    private lateinit var document: RecordViewModel
    private lateinit var engine: KataGoViewModel

    @Before fun reset() {
        ui.runOnUiThread {
            document = ViewModelProvider(ui.activity)[RecordViewModel::class.java]
            engine = ViewModelProvider(ui.activity)[KataGoViewModel::class.java]
            document.updatePreferences(AppPreferences(engineEnabled = false, maxVisits = 64))
            document.record.newGame()
        }
        ui.waitForIdle()
    }

    @After fun cleanup() {
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        ui.runOnUiThread {
            engine.controller.cancelBenchmark()
            document.updatePreferences(AppPreferences(engineEnabled = false))
            document.record.reset()
        }
        ui.waitForIdle()
    }

    private fun openEnginePage() {
        ui.onNodeWithContentDescription("更多选项").performClick()
        ui.onNodeWithText("设置").performClick()
        ui.onNodeWithTag("open-engines").performScrollTo().performClick()
    }

    @Test fun selectingModelPersistsBenchmarksAndDrivesLiveAnalysisWithWhitePerspective() {
        openEnginePage()
        ui.onNodeWithTag("model-b10c128").performClick().assertIsSelected()
        ui.onNodeWithTag("backend-CPU").performScrollTo().performClick()
        ui.onNodeWithTag("backend-NPU").assertIsNotEnabled()
        ui.onNodeWithTag("benchmark-limit-100").performScrollTo().performClick()
        ui.onNodeWithTag("start-benchmark").performScrollTo().performClick()
        ui.waitUntil(60000) { engine.controller.benchmark.value.phase in listOf(BenchmarkPhase.COMPLETE, BenchmarkPhase.ERROR) }
        assertEquals(engine.controller.benchmark.value.error, BenchmarkPhase.COMPLETE, engine.controller.benchmark.value.phase)
        val config = EngineConfig(KataGoModel.B10, EngineBackend.CPU)
        val measured = engine.controller.benchmark.value.results.getValue(config.cacheKey)
        assertEquals(300, measured.visits)
        assertEquals(64, document.preferences.maxVisits)
        assertEquals(0, document.record.position.moves.size)
        ui.onNodeWithTag("benchmark-rate").performScrollTo().assertIsDisplayed()
        ui.activityRule.scenario.recreate()
        assertEquals(config, AppPreferences.load(ui.activity).engineConfig)
        ui.onNodeWithTag("model-b10c128").performScrollTo().assertIsSelected()
        assertEquals(measured, engine.controller.benchmark.value.results[config.cacheKey])
        assertEquals(measured, BenchmarkStore(java.io.File(ui.activity.filesDir, "engine-benchmarks.json")).load()[config.cacheKey])
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithContentDescription("返回").performClick()
        ui.onNodeWithTag("engine-switch").performClick()
        ui.waitUntil(30000) { engine.controller.state.value.completedAnalysis?.position == document.record.position }
        assertEquals(config, engine.controller.state.value.config)
        assertTrue((engine.controller.state.value.completedAnalysis?.visits ?: 0) >= 64)
        ui.onNodeWithTag("candidate-A").performClick() // Black, then analyze the white turn.
        ui.waitUntil(30000) {
            engine.controller.state.value.completedAnalysis?.position == document.record.position && engine.controller.state.value.completedAnalysis?.position?.moves?.size == 1
        }
        val description = ui.onNodeWithTag("candidate-A").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        assertTrue(description, description.contains("白棋胜率"))
        val result = engine.controller.state.value.analyses.getValue(document.record.position.key)
        val expected = String.format(java.util.Locale.ROOT, "%.1f", result.candidates.first().winRateFor(2))
        assertTrue(description, description.contains("$expected%"))
        val best = result.candidates.maxOf { it.winRateFor(2) }
        val loss = String.format(java.util.Locale.ROOT, "%.1f", best - result.candidates.first().winRateFor(2))
        assertTrue(description, description.contains("比最佳低 $loss 个百分点"))
    }

    @Test fun cancellingBackgroundingAndLeavingBenchmarkKeepAutomaticPlayersPaused() {
        openEnginePage()
        ui.onNodeWithTag("model-b10c128").performClick()
        ui.runOnIdle { document.updatePreferences(document.preferences.copy(engineEnabled = true, autoBlack = true, autoWhite = true)) }
        ui.onNodeWithTag("benchmark-limit-1000").performScrollTo().performClick()
        val before = engine.controller.benchmark.value.results
        for (action in listOf("cancel", "background", "back")) {
            ui.onNodeWithTag("start-benchmark").performScrollTo().performClick()
            ui.waitUntil(10000) { engine.controller.benchmark.value.phase in listOf(BenchmarkPhase.WARMING, BenchmarkPhase.RUNNING) }
            assertNull(engine.controller.state.value.completedAnalysis)
            assertEquals(0, document.record.position.moves.size)
            when (action) {
                "cancel" -> ui.onNodeWithTag("cancel-benchmark").performScrollTo().performClick()
                "background" -> {
                    ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                    ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                }
                else -> ui.onNodeWithContentDescription("返回").performClick()
            }
            ui.waitUntil(5000) { engine.controller.benchmark.value.phase == BenchmarkPhase.CANCELLED }
            assertEquals(before, engine.controller.benchmark.value.results)
            assertEquals(0, document.record.position.moves.size)
            assertNull(engine.controller.state.value.completedAnalysis)
        }
        ui.onNodeWithTag("settings-screen").assertIsDisplayed()
    }
}
