package com.example.katadroid.engine

import com.example.katadroid.NpuProbe
import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class EnginePhase { OFF, STARTING, ANALYZING, READY, SUSPENDED, ERROR }

data class EngineUiState(
    val enabled: Boolean = false,
    val phase: EnginePhase = EnginePhase.OFF,
    val positionKey: String? = null,
    val analyses: Map<String, PositionAnalysis> = emptyMap(),
    val error: String? = null,
    val maxVisits: Int = 500,
    val completedAnalysis: PositionAnalysis? = null,
    val config: EngineConfig = EngineConfig(),
)

/**
 * A single worker serializes opening, search and closing. UI commands immediately
 * cancel the previous token; results are accepted only for that command's epoch.
 * In particular, neither rapid on/off nor navigating away can publish stale data
 * or close LiteRT buffers while the native NN thread still uses them.
 */
class AnalysisController(
    private val factory: (EngineConfig) -> AnalysisSession,
    private val store: AnalysisStore,
    private val benchmarkStore: BenchmarkStore? = null,
) : AutoCloseable {
    private data class Request(val position: AnalysisPosition?, val enabled: Boolean, val visible: Boolean,
                               val maxVisits: Int, val config: EngineConfig, val benchmarkVisits: Int? = null)
    private data class Command(val request: Request, val epoch: Long, val cacheGeneration: Long, val cancelled: AtomicBoolean)
    private val lock = Any()
    private var epoch = 0L
    private var cacheGeneration = 0L
    private var current: Command? = null
    private var closed = false
    private val commands = Channel<Command>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(EngineUiState())
    val state: StateFlow<EngineUiState> = mutableState.asStateFlow()
    private val mutableBenchmark = MutableStateFlow(BenchmarkUiState())
    val benchmark: StateFlow<BenchmarkUiState> = mutableBenchmark.asStateFlow()

    init {
        scope.launch {
            var session: AnalysisSession? = null
            var sessionConfig: EngineConfig? = null
            var loadedCacheGeneration = -1L
            var loadedConfig: EngineConfig? = null
            try {
                val savedBenchmarks = benchmarkStore?.load().orEmpty()
                synchronized(lock) { mutableBenchmark.value = mutableBenchmark.value.copy(results = savedBenchmarks) }
                for (command in commands) {
                    val request = command.request
                    if (command.cancelled.get()) continue
                    try {
                        if (loadedCacheGeneration != command.cacheGeneration) {
                            val restored = store.load(request.config)
                            if (publish(command) { it.copy(analyses = restored) }) {
                                loadedCacheGeneration = command.cacheGeneration
                                loadedConfig = request.config
                            }
                        }
                        if (sessionConfig != request.config || request.benchmarkVisits != null) {
                            session?.close()
                            session = null
                            sessionConfig = null
                        }
                        if (command.cancelled.get()) continue
                        if (request.benchmarkVisits != null) {
                            // A benchmark owns this same worker: live analysis is already
                            // stopped, and a new runtime's initialization is timed separately.
                            val before = SystemClock.elapsedRealtimeNanos()
                            session = factory(request.config)
                            val initNanos = SystemClock.elapsedRealtimeNanos() - before
                            val result = try {
                                SearchBenchmark.run(checkNotNull(session), resolvedConfig(request.config, checkNotNull(session)), request.benchmarkVisits,
                                    initNanos, command.cancelled) { phase, visits, label ->
                                    publishBenchmark(command) { it.copy(phase = phase, completedVisits = visits, caseLabel = label) }
                                }
                            } finally {
                                session?.close()
                                session = null
                            }
                            if (result != null && publishBenchmark(command) {
                                    it.copy(phase = BenchmarkPhase.COMPLETE, results = it.results + (result.config.cacheKey to result))
                                }) {
                                try { benchmarkStore?.save(benchmark.value.results.values) }
                                catch (error: Exception) { Log.w("KataDroidEngine", "Benchmark could not be saved", error) }
                            }
                            continue
                        }
                        if (!request.enabled || !request.visible || request.position == null) {
                            session?.close()
                            session = null
                            sessionConfig = null
                            continue
                        }
                        if (session == null) {
                            publish(command) { it.copy(phase = EnginePhase.STARTING) }
                            session = factory(request.config)
                            sessionConfig = request.config
                        }
                        if (command.cancelled.get()) continue
                        val activeSession = checkNotNull(session)
                        val actualConfig = resolvedConfig(request.config, activeSession)
                        if (loadedConfig != actualConfig) {
                            val restored = store.load(actualConfig)
                            if (publish(command) { it.copy(analyses = restored) }) loadedConfig = actualConfig
                        }
                        activeSession.setPosition(request.position)
                        publish(command) { it.copy(phase = EnginePhase.ANALYZING) }
                        for (visits in visitTargets(request.maxVisits)) {
                            if (command.cancelled.get()) break
                            val result = activeSession.analyze(visits, command.cancelled) ?: break
                            check(result.position == request.position) { "Analysis result does not match the requested position" }
                            val accepted = publish(command) { state ->
                                val old = state.analyses[result.position.key]
                                val analyses = if (old == null || result.gameFinished || result.visits >= old.visits) {
                                    LinkedHashMap(state.analyses).apply {
                                        remove(result.position.key)
                                        put(result.position.key, result)
                                        while (size > MAX_CACHED_POSITIONS) remove(keys.first())
                                    }.toMap()
                                } else state.analyses
                                val finished = visits == request.maxVisits || result.gameFinished
                                state.copy(analyses = analyses, phase = if (finished) EnginePhase.READY else EnginePhase.ANALYZING,
                                    completedAnalysis = if (finished) result else null)
                            }
                            if (accepted) try {
                                // Capture under the same epoch: a concurrent model switch
                                // must never write another model's map into this namespace.
                                val snapshot = synchronized(lock) {
                                    if (epoch == command.epoch) mutableState.value.analyses.values.toList() else null
                                }
                                if (snapshot != null) store.save(snapshot, actualConfig)
                            }
                            catch (error: Exception) { Log.w("KataDroidEngine", "Analysis cache could not be written", error) }
                            if (result.gameFinished) break
                        }
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        session?.close()
                        session = null
                        sessionConfig = null
                        Log.e("KataDroidEngine", "Analysis failed", error)
                        if (request.benchmarkVisits != null)
                            publishBenchmark(command) { it.copy(phase = BenchmarkPhase.ERROR, error = error.message ?: "Benchmark failed") }
                        else publish(command) { it.copy(phase = EnginePhase.ERROR, error = error.message ?: "Unable to initialize model") }
                    }
                }
            } finally { session?.close() }
        }
    }

    fun setTarget(position: AnalysisPosition?, enabled: Boolean, visible: Boolean = true, retry: Boolean = false,
                  maxVisits: Int = 500, config: EngineConfig = EngineConfig()) {
        require(maxVisits in 1..MAX_VISITS)
        synchronized(lock) {
            enqueue(Request(position, enabled, visible, maxVisits, config), retry)
        }
    }

    fun startBenchmark(config: EngineConfig, visitsPerPosition: Int = 500) {
        require(visitsPerPosition in 1..MAX_VISITS)
        synchronized(lock) {
            val state = mutableState.value
            enqueue(Request(null, state.enabled, false, state.maxVisits, config, visitsPerPosition), retry = true)
        }
    }

    fun cancelBenchmark() {
        synchronized(lock) {
            val request = current?.request ?: return
            if (request.benchmarkVisits != null) enqueue(request.copy(benchmarkVisits = null), retry = true)
        }
    }

    /** Called only while holding lock. */
    private fun enqueue(request: Request, retry: Boolean) {
        if (closed || (!retry && current?.request == request)) return
        current?.cancelled?.set(true)
        val previous = mutableState.value
        if (previous.config != request.config) cacheGeneration++
        val command = Command(request, ++epoch, cacheGeneration, AtomicBoolean(false))
        current = command
        mutableState.value = previous.copy(
                enabled = request.enabled,
                positionKey = request.position?.key,
                phase = when {
                    !request.enabled -> EnginePhase.OFF
                    !request.visible -> EnginePhase.SUSPENDED
                    else -> EnginePhase.STARTING
                },
                error = null,
                maxVisits = request.maxVisits,
                completedAnalysis = null,
                config = request.config,
                analyses = if (previous.config == request.config) previous.analyses else emptyMap(),
            )
        mutableBenchmark.value = when {
            request.benchmarkVisits != null -> BenchmarkUiState(phase = BenchmarkPhase.STARTING, config = request.config,
                visitsPerPosition = request.benchmarkVisits, results = mutableBenchmark.value.results)
            mutableBenchmark.value.running -> mutableBenchmark.value.copy(phase = BenchmarkPhase.CANCELLED)
            mutableBenchmark.value.config.cacheKey != request.config.cacheKey ->
                BenchmarkUiState(config = request.config, results = mutableBenchmark.value.results)
            else -> mutableBenchmark.value
        }
        commands.trySend(command)
    }

    private fun resolvedConfig(config: EngineConfig, session: AnalysisSession): EngineConfig =
        config.copy(backend = EngineBackend.valueOf(session.backend))

    private fun publish(command: Command, transform: (EngineUiState) -> EngineUiState): Boolean = synchronized(lock) {
        if (closed || epoch != command.epoch || command.cancelled.get()) false
        else { mutableState.value = transform(mutableState.value); true }
    }

    private fun publishBenchmark(command: Command, transform: (BenchmarkUiState) -> BenchmarkUiState): Boolean = synchronized(lock) {
        if (closed || epoch != command.epoch || command.cancelled.get()) false
        else { mutableBenchmark.value = transform(mutableBenchmark.value); true }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            current?.cancelled?.set(true)
            commands.close()
            scope.cancel() // The worker's finally block joins native threads and frees resources.
        }
    }
}

internal fun visitTargets(limit: Int): List<Int> = buildList {
    for (step in listOf(32, 128)) { if (step < limit) add(step) }
    var step = 500
    while (step < limit) { add(step); step += 500 }
    add(limit)
}

class KataGoViewModel(application: Application) : AndroidViewModel(application) {
    val controller = AnalysisController(
        factory = { config ->
            openSelectedSession(config, NpuProbe.hasRuntimeLibraries(application)) { accelerator ->
                KataGoSession.open(application, accelerator, config.model)
            }
        },
        store = FileAnalysisStore(File(application.filesDir, "analysis-cache.json")),
        benchmarkStore = BenchmarkStore(File(application.filesDir, "engine-benchmarks.json")),
    )
    override fun onCleared() { controller.close() }
}
