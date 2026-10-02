package io.github.zhzy0077.katadroid

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zhzy0077.katadroid.engine.*
import com.google.ai.edge.litert.Accelerator
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModelBenchmarkTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun b10CpuRawAndOfficialPostprocessedOutputsMatchReferences() = runBlocking {
        assertEquals("b10c128", KataGoProbe.run(context, Accelerator.CPU, KataGoModel.B10).modelId)
        EngineIntegrationTest().verifyOfficialPositions(Accelerator.CPU, KataGoModel.B10)
    }

    @Test
    fun bothCpuModelsCompleteFreshRealSearchBenchmarks() {
        for (model in KataGoModel.entries) {
            val config = EngineConfig(model, EngineBackend.CPU)
            val before = SystemClock.elapsedRealtimeNanos()
            KataGoSession.open(context, Accelerator.CPU, model).use { session ->
                val init = SystemClock.elapsedRealtimeNanos() - before
                val result = checkNotNull(SearchBenchmark.run(session, config, 100, init, AtomicBoolean(false)))
                assertEquals(300, result.visits)
                assertTrue(result.visitsPerSecond.isFinite() && result.visitsPerSecond > 0)
                assertEquals(3, result.samples.size)
                assertEquals(result, BenchmarkResult.parse(result.json()))
                // Reset also discards a completed tree; a smaller target cannot
                // accidentally time a cache hit on an already deeper search.
                session.resetForBenchmark(SearchBenchmark.positions.last().second)
                assertEquals(1, session.analyze(1, AtomicBoolean(false))?.visits)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("stream", "\nCPU_BENCHMARK ${result.json()}\n")
                })
            }
        }
    }

    @Test
    fun benchmarkCountsCumulativeVisitsOnceExcludesWarmupAndUsesWeightedTime() {
        var time = 0L
        var resets = 0
        var visited = 0
        lateinit var position: AnalysisPosition
        val session = object : AnalysisSession {
            override val backend = "CPU"
            override fun setPosition(value: AnalysisPosition) { position = value }
            override fun resetForBenchmark(position: AnalysisPosition) { setPosition(position); resets++; visited = 0 }
            override fun analyze(visits: Int, cancelled: AtomicBoolean): PositionAnalysis {
                val cost = if (resets == 1) 9_000_000L else (resets - 1) * 1_000_000L
                time += (visits - visited) * cost
                visited = visits
                return PositionAnalysis(position, visits, 50f, 0f, 0f, emptyList(), backend)
            }
            override fun close() = Unit
        }
        val progress = mutableListOf<Int>()
        val result = checkNotNull(SearchBenchmark.run(session, EngineConfig(backend = EngineBackend.CPU), 256,
            999_000_000L, AtomicBoolean(false), clock = { time }) { phase, visits, _ ->
            if (phase == BenchmarkPhase.RUNNING) progress += visits
        })
        assertEquals(4, resets)
        assertEquals(768, result.visits)
        assertEquals(1_536_000_000L, result.searchNanos)
        assertEquals(288_000_000L, result.warmupNanos)
        assertEquals(500.0, result.visitsPerSecond, 0.0001)
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(768, progress.last())
    }
}
