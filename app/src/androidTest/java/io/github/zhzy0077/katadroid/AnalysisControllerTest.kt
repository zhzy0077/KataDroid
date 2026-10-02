package io.github.zhzy0077.katadroid

import androidx.test.platform.app.InstrumentationRegistry
import io.github.zhzy0077.katadroid.engine.AnalysisController
import io.github.zhzy0077.katadroid.engine.AnalysisPosition
import io.github.zhzy0077.katadroid.engine.AnalysisSession
import io.github.zhzy0077.katadroid.engine.AnalysisStore
import io.github.zhzy0077.katadroid.engine.EnginePhase
import io.github.zhzy0077.katadroid.engine.EngineConfig
import io.github.zhzy0077.katadroid.engine.FileAnalysisStore
import io.github.zhzy0077.katadroid.engine.PASS
import io.github.zhzy0077.katadroid.engine.PositionAnalysis
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

class AnalysisControllerTest {
    private fun result(position: AnalysisPosition, visits: Int = 32) = PositionAnalysis(position, visits, 42f, -2.5f, -2.4f, emptyList(), "CPU")
    private class MemoryStore(private var records: Map<String, PositionAnalysis> = emptyMap()) : AnalysisStore {
        override fun load(config: EngineConfig) = records
        override fun save(analyses: Collection<PositionAnalysis>, config: EngineConfig) { records = analyses.associateBy { it.position.key } }
    }

    @Test
    fun cancelledOldPositionCannotPublishAndPauseClosesTheSession() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val old = AnalysisPosition(emptyList())
        val next = AnalysisPosition(listOf(60))
        AnalysisController(factory = {
            object : AnalysisSession {
                override val backend = "CPU"
                private lateinit var position: AnalysisPosition
                override fun setPosition(position: AnalysisPosition) { this.position = position }
                override fun resetForBenchmark(position: AnalysisPosition) = setPosition(position)
                override fun analyze(visits: Int, cancelled: AtomicBoolean): PositionAnalysis {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    // Deliberately return a late result even after cancellation.
                    return result(position, visits)
                }
                override fun close() { closed.countDown() }
            }
        }, store = MemoryStore()).use { controller ->
            controller.setTarget(old, true)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller.setTarget(next, true)
            controller.setTarget(next, false)
            release.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertEquals(next.key, controller.state.value.positionKey)
            assertEquals(EnginePhase.OFF, controller.state.value.phase)
            assertTrue(controller.state.value.analyses.isEmpty())
        }
    }

    @Test
    fun startupFailureAndBackgroundKeepCacheAndRetryReopensResources() = runBlocking {
        val position = AnalysisPosition(listOf(60, 300))
        val cached = result(position, 128)
        val opens = AtomicInteger()
        val closes = CountDownLatch(1)
        AnalysisController(factory = {
            if (opens.incrementAndGet() == 1) error("Injected startup failure")
            object : AnalysisSession {
                override val backend = "CPU"
                override fun setPosition(position: AnalysisPosition) = Unit
                override fun resetForBenchmark(position: AnalysisPosition) = setPosition(position)
                override fun analyze(visits: Int, cancelled: AtomicBoolean) = result(position, visits)
                override fun close() { closes.countDown() }
            }
        }, store = MemoryStore(mapOf(position.key to cached))).use { controller ->
            controller.setTarget(position, true)
            withTimeout(5000) { controller.state.first { it.phase == EnginePhase.ERROR } }
            assertEquals(cached, controller.state.value.analyses[position.key])
            controller.setTarget(position, true, retry = true)
            val ready = withTimeout(5000) { controller.state.first { it.phase == EnginePhase.READY } }
            assertEquals(500, ready.analyses.getValue(position.key).visits)
            controller.setTarget(position, true, visible = false)
            assertTrue(closes.await(5, TimeUnit.SECONDS))
            assertEquals(EnginePhase.SUSPENDED, controller.state.value.phase)
            assertEquals(500, controller.state.value.analyses.getValue(position.key).visits)
            assertEquals(2, opens.get())
            controller.setTarget(position, true, visible = true)
            withTimeout(5000) { controller.state.first { it.phase == EnginePhase.READY } }
            assertEquals(3, opens.get())
        }
    }

    @Test
    fun diskCacheRoundTripsAndKeysDistinguishHistoryAndKomi() {
        val empty = AnalysisPosition(emptyList())
        val passes = AnalysisPosition(listOf(PASS, PASS)) // Same stones, different history/game phase.
        val otherKomi = empty.copy(komi = 6.5f)
        assertEquals(3, setOf(empty.key, passes.key, otherKomi.key).size)
        val file = File.createTempFile("analysis-store-test", ".json", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            val store = FileAnalysisStore(file)
            val records = listOf(result(empty), result(passes), result(otherKomi))
            store.save(records)
            assertEquals(records.associateBy { it.position.key }, FileAnalysisStore(file).load())
            File(file.parentFile, "${file.nameWithoutExtension}-${EngineConfig(backend = io.github.zhzy0077.katadroid.engine.EngineBackend.CPU).cacheKey}.json").writeText("{broken")
            assertTrue(store.load().isEmpty())
        } finally {
            file.parentFile?.listFiles()?.filter { it.name.startsWith(file.nameWithoutExtension) }?.forEach { it.delete() }
        }
    }

    @Test
    fun changingLimitCancelsOldSearchAndKeepsFreshCompletionSeparateFromDeeperCache() = runBlocking {
        val position = AnalysisPosition(emptyList())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val limits = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val cached = result(position, 1000)
        AnalysisController(factory = {
            object : AnalysisSession {
                override val backend = "CPU"
                override fun setPosition(position: AnalysisPosition) = Unit
                override fun resetForBenchmark(position: AnalysisPosition) = setPosition(position)
                override fun analyze(visits: Int, cancelled: AtomicBoolean): PositionAnalysis {
                    limits += visits
                    if (calls.incrementAndGet() == 1) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    return result(position, visits)
                }
                override fun close() = Unit
            }
        }, store = MemoryStore(mapOf(position.key to cached))).use { controller ->
            controller.setTarget(position, true, maxVisits = 5000)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller.setTarget(position, true, maxVisits = 64)
            assertNull(controller.state.value.completedAnalysis)
            release.countDown()
            val ready = withTimeout(5000) { controller.state.first { it.phase == EnginePhase.READY } }
            assertEquals(64, ready.maxVisits)
            assertEquals(64, ready.completedAnalysis?.visits)
            assertEquals(cached, ready.analyses[position.key])
            assertEquals(listOf(32, 32, 64), limits.toList())
            controller.setTarget(position, true, maxVisits = 1)
            val one = withTimeout(5000) { controller.state.first { it.phase == EnginePhase.READY } }
            assertEquals(1, one.completedAnalysis?.visits)
            controller.setTarget(position, false, maxVisits = 1)
            assertNull(controller.state.value.completedAnalysis)
        }
    }
}
