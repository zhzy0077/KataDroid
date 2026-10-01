package com.example.katadroid.engine

import android.util.AtomicFile
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

interface AnalysisStore {
    fun load(config: EngineConfig = EngineConfig()): Map<String, PositionAnalysis>
    fun save(analyses: Collection<PositionAnalysis>, config: EngineConfig = EngineConfig())
}

/** Only compact results are persisted. No JNI handles, search trees or tensors. */
class FileAnalysisStore(private val base: File) : AnalysisStore {
    private fun scopedFile(config: EngineConfig) = AtomicFile(File(base.parentFile, "${base.nameWithoutExtension}-${config.cacheKey}.json"))

    override fun load(config: EngineConfig): Map<String, PositionAnalysis> {
        if (config.backend == EngineBackend.AUTO) {
            val latest = listOf(EngineBackend.CPU, EngineBackend.NPU).map { config.copy(backend = it) }
                .maxBy { scopedFile(it).baseFile.lastModified() }
            return load(latest)
        }
        val scoped = scopedFile(config)
        // Read legacy b6 results once; their position keys are recomputed and
        // their CPU/NPU values are separated during migration.
        val legacy = !scoped.baseFile.exists() && config.model == KataGoModel.B6 && base.exists()
        val file = if (legacy) AtomicFile(base) else scoped
        return try {
            val root = JSONObject(file.openRead().bufferedReader().use { it.readText() })
            val valid = if (legacy) root.getInt("version") in 1..2 && root.getString("model") == config.model.sha256
                else root.getInt("version") == 3 && root.getString("engine") == config.cacheKey
            if (!valid) emptyMap()
            else buildMap {
                val records = root.getJSONArray("analyses")
                for (index in maxOf(0, records.length() - MAX_CACHED_POSITIONS) until records.length()) {
                    val record = records.getJSONObject(index)
                    if (record.getString("backend") != config.backend.name) continue
                    val position = AnalysisPosition.parse(record)
                    val analysis = PositionAnalysis.parse(position, record, record.getString("backend"))
                    put(position.key, analysis)
                }
            }
        } catch (error: Exception) {
            if (file.baseFile.exists()) Log.w("KataDroidEngine", "Ignoring invalid analysis cache", error)
            emptyMap()
        }
    }

    override fun save(analyses: Collection<PositionAnalysis>, config: EngineConfig) {
        if (config.backend == EngineBackend.AUTO) {
            analyses.groupBy { it.backend }.forEach { (backend, values) ->
                save(values, config.copy(backend = EngineBackend.valueOf(backend)))
            }
            return
        }
        require(analyses.all { it.backend == config.backend.name })
        val file = scopedFile(config)
        val root = JSONObject().put("version", 3).put("engine", config.cacheKey)
            .put("analyses", JSONArray(analyses.toList().takeLast(MAX_CACHED_POSITIONS).map { it.json() }))
        file.baseFile.parentFile?.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(root.toString().toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}

internal const val MAX_CACHED_POSITIONS = 256
