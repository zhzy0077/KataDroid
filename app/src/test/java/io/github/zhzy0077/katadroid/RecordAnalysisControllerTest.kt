package io.github.zhzy0077.katadroid

import io.github.zhzy0077.katadroid.engine.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RecordAnalysisControllerTest {
    private fun result(position: AnalysisPosition, visits: Int) =
        PositionAnalysis(position, visits, 42f, -2.5f, -2.4f, emptyList(), "CPU")

    private class MemoryStore(var records: Map<String, PositionAnalysis> = emptyMap()) : AnalysisStore {
        override fun load(config: EngineConfig) = records
        override fun save(analyses: Collection<PositionAnalysis>, config: EngineConfig) {
            records = analyses.associateBy { it.position.key }
        }
    }

    @Test fun continuousSearchStartsAfterHistoryAndPauseRejectsLateResultsBeyondSettingsBudget() = runBlocking {
        val current = AnalysisPosition(listOf(60))
        val historical = AnalysisPosition(emptyList())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = mutableListOf<Pair<AnalysisPosition, Int>>()
        val intervals = mutableListOf<Int>()
        AnalysisController(factory = {
            object : AnalysisSession {
                override val backend = "CPU"
                private lateinit var position: AnalysisPosition
                override fun setPosition(position: AnalysisPosition) { this.position = position }
                override fun resetForBenchmark(position: AnalysisPosition) = setPosition(position)
                override fun analyze(visits: Int, cancelled: AtomicBoolean): PositionAnalysis {
                    calls += position to visits
                    return result(position, visits)
                }
                override fun analyzeForTime(milliseconds: Int, cancelled: AtomicBoolean, maxVisits: Int): PositionAnalysis {
                    intervals += milliseconds
                    assertEquals(current, position)
                    if (intervals.size == 2) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    return result(position, if (intervals.size == 1) 50537 else 50629)
                }
                override fun close() = Unit
            }
        }, store = MemoryStore(mapOf(current.key to result(current, 100000)))).use { controller ->
            try {
                controller.setTarget(current, true, maxVisits = 50000,
                    history = listOf(current, historical), continuous = true)
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val state = controller.state.value
                assertEquals(2, state.historyCompleted)
                assertFalse(state.historyAnalyzing)
                assertTrue(state.continuousAnalyzing)
                assertEquals(historical to 50000, calls.last())
                assertEquals(listOf(250, 250), intervals)
                assertEquals(50537, state.completedAnalysis?.visits)
                assertEquals(50537, state.analysisFor(current)?.visits)
                assertEquals(100000, state.analyses.getValue(current.key).visits)
                controller.setTarget(current, false)
            } finally { release.countDown() }
            val paused = withTimeout(5000) { controller.state.first { it.phase == EnginePhase.OFF } }
            assertFalse(paused.continuousAnalyzing)
            assertEquals(100000, paused.analyses.getValue(current.key).visits)
        }
    }

    @Test fun timedInitialSearchPublishesReadyOnlyAfterReachingTheAutomaticMoveBudget() = runBlocking {
        val position = AnalysisPosition(emptyList())
        val budgets = mutableListOf<Pair<Int, Int>>()
        AnalysisController(factory = {
            object : AnalysisSession {
                override val backend = "CPU"
                override val supportsTimedAnalysis = true
                private var visits = 0
                override fun setPosition(position: AnalysisPosition) { visits = 0 }
                override fun resetForBenchmark(position: AnalysisPosition) = setPosition(position)
                override fun analyze(visits: Int, cancelled: AtomicBoolean): PositionAnalysis = error("Current search must use timed snapshots")
                override fun analyzeForTime(milliseconds: Int, cancelled: AtomicBoolean, maxVisits: Int): PositionAnalysis {
                    budgets += milliseconds to maxVisits
                    visits = minOf(visits + 37, maxVisits)
                    return result(position, visits)
                }
                override fun close() = Unit
            }
        }, store = MemoryStore()).use { controller ->
            controller.setTarget(position, true, maxVisits = 64)
            val ready = withTimeout(5000) { controller.state.first { it.phase == EnginePhase.READY } }
            assertEquals(64, ready.completedAnalysis?.visits)
            assertEquals(listOf(250 to 64, 250 to 64), budgets)
        }
    }

    @Test fun currentPositionComesFirstThenHistoryReusesSufficientCacheAndUpgradesShallowResults() = runBlocking {
        val positions = (0..3).map { AnalysisPosition((0 until it).toList()) }
        val calls = mutableListOf<Pair<AnalysisPosition, Int>>()
        val store = MemoryStore(mapOf(positions[1].key to result(positions[1], 128),
            positions[2].key to result(positions[2], 32)))
        AnalysisController(factory = {
            object : AnalysisSession {
                override val backend = "CPU"
                private lateinit var position: AnalysisPosition
                override fun setPosition(position: AnalysisPosition) { this.position = position }
                override fun resetForBenchmark(position: AnalysisPosition) = setPosition(position)
                override fun analyze(visits: Int, cancelled: AtomicBoolean): PositionAnalysis {
                    calls += position to visits
                    return result(position, visits)
                }
                override fun close() = Unit
            }
        }, store = store).use { controller ->
            controller.setTarget(positions.last(), true, maxVisits = 64, history = positions + positions[1])
            val complete = withTimeout(5000) { controller.state.first {
                it.historyTotal == 4 && it.historyCompleted == 4 && !it.historyAnalyzing
            } }
            assertEquals(listOf(positions[3] to 32, positions[3] to 64,
                positions[0] to 64, positions[2] to 64), calls)
            assertEquals(EnginePhase.READY, complete.phase)
            assertEquals(positions[3].key, complete.positionKey)
            assertEquals(positions[3], complete.completedAnalysis?.position)
            assertEquals(128, complete.analyses.getValue(positions[1].key).visits)
            assertEquals(64, complete.analyses.getValue(positions[2].key).visits)
        }
    }

    @Test fun navigationCancelsHistoryAndRejectsLateResultsWithoutReplacingCurrentCandidates() = runBlocking {
        val history = AnalysisPosition(emptyList())
        val current = AnalysisPosition(listOf(60))
        val next = AnalysisPosition(listOf(60, 300))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        AnalysisController(factory = {
            object : AnalysisSession {
                override val backend = "CPU"
                private lateinit var position: AnalysisPosition
                override fun setPosition(position: AnalysisPosition) { this.position = position }
                override fun resetForBenchmark(position: AnalysisPosition) = setPosition(position)
                override fun analyze(visits: Int, cancelled: AtomicBoolean): PositionAnalysis {
                    if (position == history) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    return result(position, visits) // Deliberately return after cancellation.
                }
                override fun close() = Unit
            }
        }, store = MemoryStore()).use { controller ->
            try {
                controller.setTarget(current, true, maxVisits = 1, history = listOf(history, current))
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(current, controller.state.value.completedAnalysis?.position)
                assertEquals(EnginePhase.READY, controller.state.value.phase)
                controller.setTarget(next, true, maxVisits = 1)
            } finally { release.countDown() }
            val ready = withTimeout(5000) { controller.state.first {
                it.positionKey == next.key && it.phase == EnginePhase.READY
            } }
            assertFalse(ready.analyses.containsKey(history.key))
            assertEquals(next, ready.completedAnalysis?.position)
            assertFalse(ready.historyAnalyzing)
        }
    }

    @Test fun modelSwitchDuringHistoryCannotPublishOrSaveOldModelResultsInTheNewCache() = runBlocking {
        val history = AnalysisPosition(emptyList())
        val current = AnalysisPosition(listOf(60))
        val oldConfig = EngineConfig(KataGoModel.B6, EngineBackend.CPU)
        val newConfig = EngineConfig(KataGoModel.B10, EngineBackend.CPU)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val records = mutableMapOf<String, Map<String, PositionAnalysis>>()
        val store = object : AnalysisStore {
            override fun load(config: EngineConfig) = synchronized(records) { records[config.cacheKey].orEmpty() }
            override fun save(analyses: Collection<PositionAnalysis>, config: EngineConfig) {
                synchronized(records) { records[config.cacheKey] = analyses.associateBy { it.position.key } }
            }
        }
        AnalysisController(factory = { config ->
            object : AnalysisSession {
                override val backend = "CPU"
                private lateinit var position: AnalysisPosition
                override fun setPosition(position: AnalysisPosition) { this.position = position }
                override fun resetForBenchmark(position: AnalysisPosition) = setPosition(position)
                override fun analyze(visits: Int, cancelled: AtomicBoolean): PositionAnalysis {
                    if (config == oldConfig && position == history) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    return result(position, visits).copy(blackWinRate = if (config == oldConfig) 42f else 21f)
                }
                override fun close() = Unit
            }
        }, store = store).use { controller ->
            try {
                controller.setTarget(current, true, maxVisits = 1, config = oldConfig, history = listOf(history, current))
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                controller.setTarget(current, true, maxVisits = 1, config = newConfig, history = listOf(history, current))
            } finally { release.countDown() }
            val ready = withTimeout(5000) { controller.state.first {
                it.config == newConfig && it.historyCompleted == 2 && !it.historyAnalyzing
            } }
            assertTrue(ready.analyses.values.all { it.blackWinRate == 21f })
            synchronized(records) {
                assertFalse(records.getValue(oldConfig.cacheKey).containsKey(history.key))
                assertTrue(records.getValue(newConfig.cacheKey).values.all { it.blackWinRate == 21f })
            }
        }
    }

    @Test fun aRecordLargerThanTheOldCacheLimitKeepsEveryNodeAndTheDisplayedPosition() = runBlocking {
        val positions = (0..300).map { AnalysisPosition(listOf(it)) }
        AnalysisController(factory = {
            object : AnalysisSession {
                override val backend = "CPU"
                private lateinit var position: AnalysisPosition
                override fun setPosition(position: AnalysisPosition) { this.position = position }
                override fun resetForBenchmark(position: AnalysisPosition) = setPosition(position)
                override fun analyze(visits: Int, cancelled: AtomicBoolean) = result(position, visits)
                override fun close() = Unit
            }
        }, store = MemoryStore()).use { controller ->
            controller.setTarget(positions.last(), true, maxVisits = 1, history = positions)
            val complete = withTimeout(10000) { controller.state.first {
                it.historyCompleted == positions.size && !it.historyAnalyzing
            } }
            assertEquals(positions.map { it.key }.toSet(), complete.analyses.keys)
            assertEquals(positions.last(), complete.completedAnalysis?.position)
        }
    }
}
