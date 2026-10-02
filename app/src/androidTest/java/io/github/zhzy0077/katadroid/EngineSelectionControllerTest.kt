package io.github.zhzy0077.katadroid

import androidx.test.platform.app.InstrumentationRegistry
import io.github.zhzy0077.katadroid.engine.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class EngineSelectionControllerTest {
    private val b6 = EngineConfig(KataGoModel.B6, EngineBackend.CPU)
    private val b10 = EngineConfig(KataGoModel.B10, EngineBackend.CPU)
    private fun result(position: AnalysisPosition, visits: Int, config: EngineConfig) =
        PositionAnalysis(position, visits, if (config.model == KataGoModel.B6) 40f else 60f, 0f, 0f, emptyList(), "CPU")
    private class MemoryStore : AnalysisStore {
        val records = java.util.concurrent.ConcurrentHashMap<String, Map<String, PositionAnalysis>>()
        override fun load(config: EngineConfig) = records[config.cacheKey].orEmpty()
        override fun save(analyses: Collection<PositionAnalysis>, config: EngineConfig) { records[config.cacheKey] = analyses.associateBy { it.position.key } }
    }

    @Test
    fun modelChangeDiscardsLateResultsAndRestoresOnlyItsOwnCache() = runBlocking {
        val position = AnalysisPosition(emptyList())
        val historical = AnalysisPosition(listOf(60))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = MemoryStore().apply { records[b10.cacheKey] = mapOf(historical.key to result(historical, 500, b10)) }
        val live = AtomicInteger()
        AnalysisController(factory = { config ->
            assertEquals(1, live.incrementAndGet())
            object : AnalysisSession {
                override val backend = "CPU"
                override fun setPosition(position: AnalysisPosition) = Unit
                override fun resetForBenchmark(position: AnalysisPosition) = Unit
                override fun analyze(visits: Int, cancelled: AtomicBoolean): PositionAnalysis {
                    if (config == b6) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                    return result(position, visits, config) // Intentionally ignores cancellation.
                }
                override fun close() { live.decrementAndGet() }
            }
        }, store = store).use { controller ->
            controller.setTarget(position, true, config = b6)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller.setTarget(position, true, maxVisits = 64, config = b10)
            assertNull(controller.state.value.completedAnalysis)
            assertTrue(controller.state.value.analyses.isEmpty())
            release.countDown()
            val ready = withTimeout(5000) { controller.state.first { it.phase == EnginePhase.READY } }
            assertEquals(b10, ready.config)
            assertEquals(60f, ready.completedAnalysis!!.blackWinRate, 0f)
            assertEquals(2, ready.analyses.size)
            assertEquals(500, ready.analyses.getValue(historical.key).visits)
            assertTrue(store.load(b6).isEmpty())
        }
    }

    @Test
    fun rapidModelRoundTripReloadsCacheEvenWhenIntermediateCommandIsConflated() = runBlocking {
        val position = AnalysisPosition(emptyList())
        val historical = AnalysisPosition(listOf(60))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val restored = CountDownLatch(1)
        val loads = AtomicInteger()
        val cached = result(historical, 500, b6)
        val store = object : AnalysisStore {
            override fun load(config: EngineConfig): Map<String, PositionAnalysis> {
                if (loads.incrementAndGet() > 1) restored.countDown()
                return if (config == b6) mapOf(historical.key to cached) else emptyMap()
            }
            override fun save(analyses: Collection<PositionAnalysis>, config: EngineConfig) = Unit
        }
        AnalysisController(factory = { config ->
            object : AnalysisSession {
                override val backend = "CPU"
                override fun setPosition(position: AnalysisPosition) = Unit
                override fun resetForBenchmark(position: AnalysisPosition) = Unit
                override fun analyze(visits: Int, cancelled: AtomicBoolean): PositionAnalysis {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    return result(position, visits, config)
                }
                override fun close() = Unit
            }
        }, store = store).use { controller ->
            controller.setTarget(position, true, config = b6)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller.setTarget(position, false, config = b10)
            controller.setTarget(position, false, config = b6)
            release.countDown()
            assertTrue(restored.await(5, TimeUnit.SECONDS))
            withTimeout(5000) { controller.state.first { it.analyses[historical.key] == cached } }
            assertEquals(mapOf(historical.key to cached), controller.state.value.analyses)
            assertNull(controller.state.value.completedAnalysis)
        }
    }

    @Test
    fun benchmarkSerializesWithAnalysisAndCancelledWarmupCannotPublishOrPlay() = runBlocking {
        val position = AnalysisPosition(listOf(60))
        val warming = CountDownLatch(1)
        val release = CountDownLatch(1)
        val benchmarkClosed = CountDownLatch(1)
        val live = AtomicInteger()
        AnalysisController(factory = { config ->
            assertEquals("Overlapping runtimes", 1, live.incrementAndGet())
            object : AnalysisSession {
                override val backend = "CPU"
                private var testing = false
                private var target = position
                override fun setPosition(position: AnalysisPosition) { target = position }
                override fun resetForBenchmark(position: AnalysisPosition) { setPosition(position); testing = true }
                override fun analyze(visits: Int, cancelled: AtomicBoolean): PositionAnalysis {
                    if (testing) { warming.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                    return result(target, visits, config)
                }
                override fun close() { live.decrementAndGet(); if (testing) benchmarkClosed.countDown() }
            }
        }, store = MemoryStore()).use { controller ->
            controller.setTarget(position, true, maxVisits = 64, config = b6)
            withTimeout(5000) { controller.state.first { it.phase == EnginePhase.READY } }
            controller.startBenchmark(b6, 100)
            assertNull(controller.state.value.completedAnalysis)
            assertTrue(warming.await(5, TimeUnit.SECONDS))
            controller.setTarget(position, false, visible = false, config = b10)
            assertEquals(BenchmarkPhase.CANCELLED, controller.benchmark.value.phase)
            release.countDown()
            assertTrue(benchmarkClosed.await(5, TimeUnit.SECONDS))
            assertTrue(controller.benchmark.value.results.isEmpty())
            assertNull(controller.state.value.completedAnalysis)
            controller.setTarget(position, true, maxVisits = 64, config = b10)
            val ready = withTimeout(5000) { controller.state.first { it.phase == EnginePhase.READY } }
            assertEquals(b10, ready.config)
            assertEquals(60f, ready.completedAnalysis!!.blackWinRate, 0f)
            assertTrue(controller.benchmark.value.results.isEmpty())
        }
    }

    @Test
    fun diskNamespacesSeparateModelWeightsAndCpuNpuAndRejectWrongIdentity() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "models-${System.nanoTime()}").apply { mkdirs() }
        val file = File(directory, "analysis.json")
        val position = AnalysisPosition(emptyList())
        try {
            val store = FileAnalysisStore(file)
            val cpu = result(position, 64, b6)
            val bigger = result(position, 500, b10)
            val npu = b6.copy(backend = EngineBackend.NPU)
            store.save(listOf(cpu), b6)
            store.save(listOf(bigger), b10)
            assertEquals(cpu, FileAnalysisStore(file).load(b6)[position.key])
            assertEquals(bigger, FileAnalysisStore(file).load(b10)[position.key])
            assertTrue(store.load(npu).isEmpty())
            val b10File = File(directory, "analysis-${b10.cacheKey}.json")
            b10File.writeText(b10File.readText().replace(b10.cacheKey, b6.cacheKey))
            assertTrue(store.load(b10).isEmpty())
            assertEquals(cpu, store.load(b6)[position.key])
        } finally { directory.deleteRecursively() }
    }
}
