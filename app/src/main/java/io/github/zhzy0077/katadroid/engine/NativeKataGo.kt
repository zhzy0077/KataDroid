package io.github.zhzy0077.katadroid.engine

import androidx.annotation.Keep
import java.util.concurrent.atomic.AtomicBoolean

@Keep
object NativeKataGo {
    init { System.loadLibrary("katadroid") }

    external fun boardPosition(position: String): IntArray
    external fun featuresPosition(position: String): FloatArray
    fun board(position: AnalysisPosition) = boardPosition(position.json().toString())
    fun features(moves: IntArray, komi: Float) = featuresPosition(AnalysisPosition(moves.toList(), komi).json().toString())
    external fun create(runtime: LiteRtNetwork, modelPath: String, modelName: String): Long
    external fun destroy(handle: Long)
    external fun setPositionJson(handle: Long, position: String)
    external fun clearSearchAndCache(handle: Long)
    external fun analyze(handle: Long, visits: Int, cancelled: AtomicBoolean): String?
    external fun analyzeForTime(handle: Long, milliseconds: Int, maxVisits: Int, cancelled: AtomicBoolean): String?
    external fun evaluatePosition(handle: Long, position: String, symmetry: Int): String
}

class BoardSnapshot private constructor(private val data: IntArray) {
    val blackToMove get() = data[361] == 1
    val blackCaptures get() = data[362]
    val whiteCaptures get() = data[363]
    val finished get() = data[364] != 0
    fun color(point: Int) = data[point]
    fun isLegal(point: Int) = point in 0..PASS && !finished && data[366 + point] != 0

    companion object {
        fun from(position: AnalysisPosition) = BoardSnapshot(NativeKataGo.board(position))
    }
}
