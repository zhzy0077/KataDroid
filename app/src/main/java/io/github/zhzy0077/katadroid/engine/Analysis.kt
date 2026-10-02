package io.github.zhzy0077.katadroid.engine

import io.github.zhzy0077.katadroid.R
import androidx.annotation.StringRes

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

const val PASS = 361
const val MAX_VISITS = 50000

enum class GoRules(val id: String, @param:StringRes val labelRes: Int, val defaultKomi: Float) {
    CHINESE("chinese", R.string.rule_chinese, 7.5f), KOREAN("korean", R.string.rule_korean, 6.5f), JAPANESE("japanese", R.string.rule_japanese, 6.5f);
    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.id.equals(id, true) }
            ?: throw IllegalArgumentException("Unsupported rules: $id")
    }
}

fun validKomi(value: Float) = value.isFinite() && value in -400f..400f

/** Complete history is part of the identity: stones alone omit ko and NN history. */
data class AnalysisPosition(
    val moves: List<Int>,
    val komi: Float = 7.5f,
    val rules: GoRules = GoRules.CHINESE,
    val initialBlack: List<Int> = emptyList(),
    val initialWhite: List<Int> = emptyList(),
    val initialPlayer: Int = 1,
    val moveColors: List<Int> = moves.indices.map { if (it % 2 == 0) initialPlayer else 3 - initialPlayer },
    val nextPlayer: Int = moveColors.lastOrNull()?.let { 3 - it } ?: initialPlayer,
) {
    init {
        require(moves.size <= 2000 && moves.all { it in 0..PASS })
        require(validKomi(komi)) { "Komi must be between -400 and 400" }
        require(initialPlayer in 1..2 && nextPlayer in 1..2)
        require(moveColors.size == moves.size && moveColors.all { it in 1..2 })
        require((initialBlack + initialWhite).all { it in 0 until PASS })
        require((initialBlack + initialWhite).distinct().size == initialBlack.size + initialWhite.size)
    }
    val key: String by lazy {
        // Model identity belongs to the cache namespace, not to an SGF position.
        val identity = "katadroid-position-v3|${rules.id}|19|$komi|$initialPlayer|$nextPlayer|" +
            "${initialBlack.sorted()}|${initialWhite.sorted()}|$moves|$moveColors"
        MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun after(variation: List<Int>): AnalysisPosition {
        val colors = variation.indices.map { if (it % 2 == 0) nextPlayer else 3 - nextPlayer }
        return copy(moves = moves + variation, moveColors = moveColors + colors,
            nextPlayer = colors.lastOrNull()?.let { 3 - it } ?: nextPlayer)
    }

    fun json() = JSONObject().put("moves", JSONArray(moves)).put("komi", komi).put("rules", rules.id)
        .put("initialBlack", JSONArray(initialBlack)).put("initialWhite", JSONArray(initialWhite))
        .put("initialPlayer", initialPlayer).put("moveColors", JSONArray(moveColors)).put("nextPlayer", nextPlayer)

    companion object {
        fun parse(json: JSONObject): AnalysisPosition {
            val moves = json.getJSONArray("moves").ints()
            val initial = json.optInt("initialPlayer", 1)
            val colors = json.optJSONArray("moveColors")?.ints() ?: moves.indices.map { if (it % 2 == 0) initial else 3 - initial }
            return AnalysisPosition(moves, json.getDouble("komi").toFloat(), GoRules.fromId(json.optString("rules", "chinese")),
                json.optJSONArray("initialBlack")?.ints().orEmpty(), json.optJSONArray("initialWhite")?.ints().orEmpty(),
                initial, colors, json.optInt("nextPlayer", colors.lastOrNull()?.let { 3 - it } ?: initial))
        }
    }
}

data class AnalysisCandidate(
    val label: String,
    val move: Int,
    val visits: Int,
    val blackWinRate: Float,
    val blackLead: Float,
    val pv: List<Int>,
) {
    fun winRateFor(player: Int): Float {
        require(player in 1..2)
        return (if (player == 1) blackWinRate else 100f - blackWinRate).coerceIn(0f, 100f)
    }

    fun json() = JSONObject().put("move", move).put("visits", visits)
        .put("blackWinRate", blackWinRate).put("blackLead", blackLead).put("pv", JSONArray(pv))
}

data class PositionAnalysis(
    val position: AnalysisPosition,
    val visits: Int,
    val blackWinRate: Float,
    val blackLead: Float,
    val blackScoreMean: Float,
    val candidates: List<AnalysisCandidate>,
    val backend: String,
    val gameFinished: Boolean = false,
    val bestMove: Int? = candidates.firstOrNull()?.move,
) {
    fun json() = position.json()
        .put("visits", visits).put("blackWinRate", blackWinRate).put("blackLead", blackLead)
        .put("blackScoreMean", blackScoreMean).put("backend", backend).put("gameFinished", gameFinished)
        .put("bestMove", bestMove)
        .put("candidates", JSONArray(candidates.map { it.json() }))

    companion object {
        fun parse(position: AnalysisPosition, json: JSONObject, backend: String): PositionAnalysis {
            val finished = json.optBoolean("gameFinished", false)
            val visits = json.getInt("visits").also { require(if (finished) it == 0 else it > 0) }
            fun value(source: JSONObject, name: String) = source.getDouble(name).toFloat().also { require(it.isFinite()) }
            fun winRate(source: JSONObject) = value(source, "blackWinRate").also { require(it in 0f..100f) }
            val array = json.getJSONArray("candidates")
            require(array.length() <= 3)
            require(!finished || array.length() == 0)
            val candidates = List(array.length()) { i ->
                val entry = array.getJSONObject(i)
                val move = entry.getInt("move").also { require(it in 0..PASS) }
                val pv = entry.getJSONArray("pv").ints().also {
                    require(it.isNotEmpty() && it.size <= 8 && it.first() == move && it.all { p -> p in 0..PASS }) {
                        "Invalid principal variation for $move: $it"
                    }
                }
                AnalysisCandidate(('A' + i).toString(), move, entry.getInt("visits").also { require(it > 0) },
                    winRate(entry), value(entry, "blackLead"), pv)
            }
            return PositionAnalysis(position, visits, winRate(json), value(json, "blackLead"),
                value(json, "blackScoreMean"), candidates, backend, finished,
                if (finished) null else if (json.has("bestMove") && !json.isNull("bestMove")) json.getInt("bestMove").also { require(it in 0..PASS) }
                else candidates.firstOrNull()?.move)
        }
    }
}

internal fun JSONArray.ints() = List(length()) { getInt(it) }

/** One serialized worker owns a session; cancellation is the only cross-thread input. */
interface AnalysisSession : AutoCloseable {
    val backend: String
    val supportsTimedAnalysis: Boolean get() = false
    fun setPosition(position: AnalysisPosition)
    /** A benchmark starts each case without a tree or neural evaluation cache. */
    fun resetForBenchmark(position: AnalysisPosition)
    fun analyze(visits: Int, cancelled: AtomicBoolean): PositionAnalysis?
    /** Keep the current search tree and return a snapshot after a time budget. */
    fun analyzeForTime(milliseconds: Int, cancelled: AtomicBoolean, maxVisits: Int = Int.MAX_VALUE): PositionAnalysis? =
        throw UnsupportedOperationException("Timed analysis is not implemented by this session")
}
