package io.github.zhzy0077.katadroid

import android.content.Intent
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zhzy0077.katadroid.engine.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Release-only performance work; the foreground app stays awake to prevent vendor background throttling. */
class ProductionBenchmarkTest {
    @Test fun selectedModelAndBackendUseTheInstalledProductionRuntime() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Requires the signed, non-debuggable production APK",
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        val arguments = InstrumentationRegistry.getArguments()
        val backend = EngineBackend.valueOf(arguments.getString("backend") ?: "CPU")
        require(backend != EngineBackend.AUTO)
        val model = KataGoModel.fromId(arguments.getString("model") ?: "b6c96")
        if (backend == EngineBackend.NPU)
            assumeTrue("Requires the packaged NPU runtime", NpuProbe.hasRuntimeLibraries(context))
        fun report(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$text\n") })
        report("KATADROID_PID=${Process.myPid()}")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        report("PRODUCTION_BUILD ${info.versionName} code=${info.longVersionCode} debuggable=false")
        // A signing-key switch requires reinstalling the app. The optional,
        // app-owned archive restores its backed-up games/settings before testing.
        val backupName = InstrumentationRegistry.getArguments().getString("restoreAppData")
        if (backupName != null) {
            val backup = File(context.getExternalFilesDir(null), backupName)
            val data = File(context.applicationInfo.dataDir)
            ZipInputStream(backup.inputStream()).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    require(entry.name.startsWith("files/") || entry.name.startsWith("shared_prefs/"))
                    val target = File(data, entry.name)
                    require(target.canonicalPath.startsWith(data.canonicalPath + File.separator))
                    if (entry.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { archive.copyTo(it) }
                    }
                }
            }
            check(backup.delete())
            report("PRODUCTION_DATA_RESTORED")
        }
        val preferences = context.getSharedPreferences("board_preferences", Context.MODE_PRIVATE)
        val hadEngineSetting = preferences.contains("engine_enabled")
        val originalEngineSetting = preferences.getBoolean("engine_enabled", true)
        check(preferences.edit().putBoolean("engine_enabled", false).commit())
        var activity: android.app.Activity? = null
        try {
            // Show only the production app. Its own analysis stays paused so it
            // cannot compete with the benchmark. No UI assertions or display changes.
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            instrumentation.waitForIdleSync()
            if (arguments.getString("verifyTimed") == "true") {
                require(backend == EngineBackend.NPU && model == KataGoModel.B6)
                NpuTimedAnalysisTest().timedSnapshotsKeepTheTreeAndDoNotLimitSubsequentVisitBudgetSearches()
                report("PASS production timed NPU search")
                return
            }
            if (arguments.getString("verifySuite") == "true") {
                require(backend == EngineBackend.NPU && model == KataGoModel.B6)
                NpuNetworkProbeTest().convolution()
                NpuNetworkProbeTest().kataGoB6()
                NpuEngineIntegrationTest().officialNpuBackendSupportsLiveSearchCancellationAndRestart()
                NpuModelBenchmarkTest().b10AccuracyBothModelBenchmarksAndCancellationPassOnHtp()
                NpuTimedAnalysisTest().timedSnapshotsKeepTheTreeAndDoNotLimitSubsequentVisitBudgetSearches()
                report("PASS production NPU numerical, both models, lifecycle and timed search suite")
                return
            }
            if (arguments.getString("verifyHistory") == "true") {
                require(backend == EngineBackend.NPU && model == KataGoModel.B6)
                val record = io.github.zhzy0077.katadroid.ui.record.RecordUiState()
                val history = record.nodes.map { record.positionFor(it.id) }.distinctBy { it.key }
                val selected = record.position
                val store = object : AnalysisStore {
                    override fun load(config: EngineConfig) = emptyMap<String, PositionAnalysis>()
                    override fun save(analyses: Collection<PositionAnalysis>, config: EngineConfig) = Unit
                }
                AnalysisController(factory = { selectedConfig ->
                    KataGoSession.open(context, selectedConfig.accelerator, selectedConfig.model)
                }, store = store).use { controller ->
                    controller.setTarget(selected, true, maxVisits = 500, config = EngineConfig(model, backend),
                        history = history, continuous = true)
                    val state = kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withTimeout(240_000) {
                            controller.state.first {
                                it.phase == EnginePhase.ERROR ||
                                    (it.historyCompleted == history.size && it.continuousAnalyzing &&
                                        (it.liveAnalysis?.visits ?: 0) > 500)
                            }
                        }
                    }
                    assertNotEquals(state.error, EnginePhase.ERROR, state.phase)
                    assertEquals(51, history.size)
                    assertEquals(history.size, state.historyTotal)
                    assertTrue(history.all { (state.analyses[it.key]?.visits ?: 0) >= 500 })
                    report("PRODUCTION_HISTORY completed=${state.historyCompleted}/${state.historyTotal} selectedVisits=${state.liveAnalysis?.visits}")
                    controller.setTarget(selected, false)
                }
                report("PASS production full sample history and continuous NPU search")
                return
            }
            val rounds = (arguments.getString("rounds") ?: "3").toInt().also { require(it in 1..10) }
            val measured = mutableListOf<BenchmarkResult>()
            // One configuration per instrumentation process keeps configurations
            // isolated. Each round clears the search tree and NN cache.
            val config = EngineConfig(model, backend)
            if (arguments.getString("controllerPath") == "true") {
                // Same factory, coroutine worker, fresh-runtime lifecycle and
                // benchmark path as the production Engines & models screen.
                val viewModel = KataGoViewModel(context.applicationContext as android.app.Application)
                try {
                    repeat(rounds) { round ->
                        viewModel.controller.startBenchmark(config, 500)
                        val state = kotlinx.coroutines.runBlocking {
                            kotlinx.coroutines.withTimeout(300_000) {
                                viewModel.controller.benchmark.first {
                                    it.phase in listOf(BenchmarkPhase.COMPLETE, BenchmarkPhase.ERROR, BenchmarkPhase.CANCELLED)
                                }
                            }
                        }
                        assertEquals(state.error, BenchmarkPhase.COMPLETE, state.phase)
                        val result = checkNotNull(state.results[config.cacheKey])
                        assertEquals(1500, result.visits)
                        assertEquals(backend, result.config.backend)
                        measured += result
                        report("PRODUCTION_BENCHMARK " + result.json().put("round", round + 1))
                    }
                } finally { viewModel.controller.close() }
            } else {
                val before = SystemClock.elapsedRealtimeNanos()
                KataGoSession.open(context, config.accelerator, model).use { session ->
                    assertEquals(backend.name, session.backend)
                    val init = SystemClock.elapsedRealtimeNanos() - before
                    repeat(rounds) { round ->
                        val result = checkNotNull(SearchBenchmark.run(session, config, 500, init, AtomicBoolean(false)))
                        assertEquals(1500, result.visits)
                        assertTrue(result.visitsPerSecond.isFinite() && result.visitsPerSecond > 0)
                        measured += result
                        report("PRODUCTION_BENCHMARK " + result.json().put("round", round + 1))
                    }
                }
            }
            report("PRODUCTION_SESSION_CLOSED ${backend.name} ${model.id}")
            val median = measured.sortedBy { it.visitsPerSecond }[measured.size / 2]
            val store = BenchmarkStore(File(context.filesDir, "engine-benchmarks.json"))
            val merged = store.load().toMutableMap()
            merged[median.config.cacheKey] = median
            store.save(merged.values)
            report("PASS production ${backend.name} ${model.id} benchmark")
        } finally {
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            instrumentation.waitForIdleSync()
            val edit = preferences.edit()
            if (hadEngineSetting) edit.putBoolean("engine_enabled", originalEngineSetting)
            else edit.remove("engine_enabled")
            check(edit.commit())
        }
    }
}
