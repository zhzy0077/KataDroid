package com.example.katadroid.engine

import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

enum class BenchmarkPhase { IDLE, STARTING, WARMING, RUNNING, COMPLETE, CANCELLED, ERROR }

data class BenchmarkUiState(
    val phase: BenchmarkPhase = BenchmarkPhase.IDLE,
    val config: EngineConfig = EngineConfig(),
    val visitsPerPosition: Int = 500,
    val completedVisits: Int = 0,
    val caseLabel: String = "",
    val error: String? = null,
    val results: Map<String, BenchmarkResult> = emptyMap(),
) {
    val running get() = phase in listOf(BenchmarkPhase.STARTING, BenchmarkPhase.WARMING, BenchmarkPhase.RUNNING)
    val totalVisits get() = visitsPerPosition * SearchBenchmark.positions.size
    fun resultFor(config: EngineConfig): BenchmarkResult? = if (config.backend == EngineBackend.AUTO)
        results.values.filter { it.config.model == config.model }.maxByOrNull { it.timestamp }
    else results[config.cacheKey]
}

data class BenchmarkSample(val label: String, val visits: Int, val nanos: Long) {
    val visitsPerSecond get() = visits * 1e9 / nanos
    fun json() = JSONObject().put("label", label).put("visits", visits).put("nanos", nanos)
}

data class BenchmarkResult(
    val config: EngineConfig,
    val visitsPerPosition: Int,
    val samples: List<BenchmarkSample>,
    val initializationNanos: Long,
    val warmupNanos: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val device: String = "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SOC_MODEL}",
) {
    val visits get() = samples.sumOf { it.visits }
    val searchNanos get() = samples.sumOf { it.nanos }
    // Weighted throughput, never the mean of per-case rates or 1 / NN latency.
    val visitsPerSecond get() = visits * 1e9 / searchNanos
    fun json() = JSONObject().put("method", SearchBenchmark.METHOD).put("engine", config.cacheKey)
        .put("model", config.model.id).put("backend", config.accelerator.name)
        .put("visitsPerPosition", visitsPerPosition).put("initializationNanos", initializationNanos)
        .put("warmupNanos", warmupNanos).put("timestamp", timestamp).put("device", device)
        .put("samples", JSONArray(samples.map { it.json() }))

    companion object {
        fun parse(json: JSONObject): BenchmarkResult {
            require(json.getString("method") == SearchBenchmark.METHOD)
            val config = EngineConfig(KataGoModel.fromId(json.getString("model")), EngineBackend.valueOf(json.getString("backend")))
            require(config.cacheKey == json.getString("engine"))
            val limit = json.getInt("visitsPerPosition").also { require(it in 1..MAX_VISITS) }
            val array = json.getJSONArray("samples")
            require(array.length() == SearchBenchmark.positions.size)
            val samples = List(array.length()) { i ->
                val sample = array.getJSONObject(i)
                BenchmarkSample(sample.getString("label"), sample.getInt("visits"), sample.getLong("nanos")).also {
                    require(it.label == SearchBenchmark.positions[i].first && it.visits == limit && it.nanos > 0)
                }
            }
            return BenchmarkResult(config, limit, samples, json.getLong("initializationNanos"), json.getLong("warmupNanos"),
                json.getLong("timestamp"), json.getString("device"))
        }
    }
}

/** The same single-threaded search/settings as live analysis, on fixed positions. */
object SearchBenchmark {
    const val METHOD = "katago-1.18.2-search-v2"
    const val WARMUP_VISITS = 32
    val positions = listOf(
        "empty" to AnalysisPosition(emptyList()),
        "opening" to AnalysisPosition(listOf(72, 60, 301, 288, 309, 249, 111, 51)),
        "capture" to AnalysisPosition(listOf(60, 59, 40, 320, 58, 300, 78)),
    )

    fun run(
        session: AnalysisSession,
        config: EngineConfig,
        visitsPerPosition: Int,
        initializationNanos: Long,
        cancelled: AtomicBoolean,
        clock: () -> Long = SystemClock::elapsedRealtimeNanos,
        progress: (BenchmarkPhase, Int, String) -> Unit = { _, _, _ -> },
    ): BenchmarkResult? {
        require(visitsPerPosition in 1..MAX_VISITS)
        if (cancelled.get()) return null
        check(session.backend == config.accelerator.name) { "Benchmark backend does not match the selected engine" }
        progress(BenchmarkPhase.WARMING, 0, "warmup")
        session.resetForBenchmark(positions.first().second)
        val warmStart = clock()
        if (session.analyze(WARMUP_VISITS, cancelled) == null || cancelled.get()) return null
        val warmup = clock() - warmStart
        val samples = mutableListOf<BenchmarkSample>()
        for ((label, position) in positions) {
            if (cancelled.get()) return null
            session.resetForBenchmark(position)
            var elapsed = 0L
            var actualVisits = 0
            progress(BenchmarkPhase.RUNNING, samples.sumOf { it.visits }, label)
            for (target in visitTargets(visitsPerPosition)) {
                if (cancelled.get()) return null
                val before = clock()
                val result = session.analyze(target, cancelled) ?: return null
                elapsed += clock() - before
                if (cancelled.get()) return null
                check(result.position == position && !result.gameFinished && result.visits == target) {
                    "Search did not complete the requested visits"
                }
                actualVisits = result.visits // Successive results are cumulative, not additional visits.
                progress(BenchmarkPhase.RUNNING, samples.sumOf { it.visits } + actualVisits, label)
            }
            check(elapsed > 0)
            samples += BenchmarkSample(label, actualVisits, elapsed)
        }
        if (cancelled.get()) return null
        return BenchmarkResult(config, visitsPerPosition, samples, initializationNanos, warmup)
    }
}

class BenchmarkStore(file: File) {
    private val file = AtomicFile(file)

    fun load(): Map<String, BenchmarkResult> = runCatching {
        val records = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        buildMap {
            for (i in 0 until records.length()) runCatching { BenchmarkResult.parse(records.getJSONObject(i)) }
                .getOrNull()?.let { put(it.config.cacheKey, it) }
        }
    }.getOrDefault(emptyMap())

    fun save(results: Collection<BenchmarkResult>) {
        file.baseFile.parentFile?.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(JSONArray(results.map { it.json() }).toString().toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) { file.failWrite(stream); throw error }
    }
}
