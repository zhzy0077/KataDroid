package io.github.zhzy0077.katadroid

import io.github.zhzy0077.katadroid.engine.*
import com.google.ai.edge.litert.Accelerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class BackendFallbackTest {
    private fun session() = object : AnalysisSession {
        override val backend = "CPU"
        private var position = AnalysisPosition(emptyList())
        override fun setPosition(position: AnalysisPosition) { this.position = position }
        override fun resetForBenchmark(position: AnalysisPosition) = setPosition(position)
        override fun analyze(visits: Int, cancelled: AtomicBoolean) =
            PositionAnalysis(position, visits, 50f, 0f, 0f, emptyList(), backend)
        override fun close() = Unit
    }

    @Test fun autoFallsBackWhenThePackagedRuntimeCannotLoadAndExplicitNpuDoesNot() {
        val attempts = mutableListOf<Accelerator>()
        val open: (Accelerator) -> AnalysisSession = {
            attempts += it
            if (it == Accelerator.NPU) throw UnsatisfiedLinkError("Unsupported runtime")
            session()
        }
        openSelectedSession(EngineConfig(), true, open).use { assertEquals("CPU", it.backend) }
        assertEquals(listOf(Accelerator.NPU, Accelerator.CPU), attempts)
        attempts.clear()
        assertThrows(UnsatisfiedLinkError::class.java) {
            openSelectedSession(EngineConfig(backend = EngineBackend.NPU), true, open)
        }
        assertEquals(listOf(Accelerator.NPU), attempts)
        attempts.clear()
        openSelectedSession(EngineConfig(), false, open).close()
        assertEquals(listOf(Accelerator.CPU), attempts)
    }

    @Test fun autoPersistsAndBenchmarksTheActualBackendWithoutMixingNpuResults() = runBlocking {
        val position = AnalysisPosition(emptyList())
        val saved = CountDownLatch(1)
        val cpu = EngineConfig(backend = EngineBackend.CPU)
        val store = object : AnalysisStore {
            override fun load(config: EngineConfig) = if (config.backend == EngineBackend.AUTO)
                mapOf(position.key to PositionAnalysis(position, 1000, 90f, 10f, 10f, emptyList(), "NPU"))
            else emptyMap()
            override fun save(analyses: Collection<PositionAnalysis>, config: EngineConfig) {
                assertEquals(cpu, config)
                assertTrue(analyses.all { it.backend == "CPU" && it.blackWinRate == 50f })
                saved.countDown()
            }
        }
        AnalysisController({ session() }, store).use { controller ->
            controller.setTarget(position, true, maxVisits = 1)
            val ready = withTimeout(5000) { controller.state.first { it.phase == EnginePhase.READY } }
            assertEquals("CPU", ready.analyses.getValue(position.key).backend)
            assertEquals(1, ready.analyses.getValue(position.key).visits)
            assertTrue(saved.await(5, TimeUnit.SECONDS))
            controller.startBenchmark(EngineConfig(), 100)
            val complete = withTimeout(5000) { controller.benchmark.first { it.phase == BenchmarkPhase.COMPLETE } }
            assertEquals(cpu, complete.resultFor(EngineConfig())!!.config)
            assertEquals(setOf(cpu.cacheKey), complete.results.keys)
        }
    }
}
