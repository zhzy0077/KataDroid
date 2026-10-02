package io.github.zhzy0077.katadroid

import androidx.test.platform.app.InstrumentationRegistry
import io.github.zhzy0077.katadroid.engine.AnalysisPosition
import io.github.zhzy0077.katadroid.engine.BoardSnapshot
import io.github.zhzy0077.katadroid.engine.KataGoSession
import io.github.zhzy0077.katadroid.engine.KataGoModel
import io.github.zhzy0077.katadroid.engine.NativeKataGo
import io.github.zhzy0077.katadroid.engine.PASS
import io.github.zhzy0077.katadroid.ui.record.BoardPoint
import io.github.zhzy0077.katadroid.ui.record.RecordUiState
import com.google.ai.edge.litert.Accelerator
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.exp
import kotlin.math.tanh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EngineIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun moves(vararg coordinates: String) = AnalysisPosition(coordinates.map { if (it == "pass") PASS else BoardPoint.parse(it).index })

    @Test
    fun officialFeaturesAndPostprocessedCpuOutputsMatchGoldenPositions() {
        verifyOfficialPositions(Accelerator.CPU)
    }

    fun verifyOfficialPositions(accelerator: Accelerator, model: KataGoModel = KataGoModel.B6) {
        val fixtures = JSONObject(context.assets.open(model.fixturesAsset).bufferedReader().use { it.readText() }).getJSONArray("cases")
        KataGoSession.open(context, accelerator, model).use { session ->
            repeat(fixtures.length()) { index ->
                val fixture = fixtures.getJSONObject(index)
                val position = AnalysisPosition(Regex(";[BW]\\[([a-s]{2})\\]").findAll(fixture.getString("sgf")).map {
                    val sgf = it.groupValues[1]
                    (sgf[1] - 'a') * 19 + (sgf[0] - 'a')
                }.toList())
                assertEquals(fixture.getInt("moveNum"), position.moves.size)
                val features = NativeKataGo.features(position.moves.toIntArray(), position.komi)
                val spatial = fixture.getJSONObject("inputs").getJSONArray("InputSpatial")
                val global = fixture.getJSONObject("inputs").getJSONArray("InputGlobal")
                assertEquals(spatial.length() + global.length(), features.size)
                for (i in features.indices) assertEquals("${fixture.getString("name")} feature $i",
                    if (i < spatial.length()) spatial.getDouble(i) else global.getDouble(i - spatial.length()), features[i].toDouble(), 1e-6)

                val actual = session.evaluate(position)
                val expected = fixture.getJSONObject("outputs")
                val value = expected.getJSONArray("OutputValue")
                val currentWin = 1.0 / (1.0 + exp(value.getDouble(1) - value.getDouble(0))) // Chinese rules exclude no-result.
                val blackToMove = position.moves.size % 2 == 0
                val score = expected.getJSONArray("OutputScoreValue")
                val sign = if (blackToMove) 1.0 else -1.0
                val winTolerance = if (accelerator == Accelerator.NPU) 1.0 else 0.001
                val scoreTolerance = if (accelerator == Accelerator.NPU) 0.25 else 0.001
                assertEquals((if (blackToMove) currentWin else 1 - currentWin) * 100, actual.getDouble("blackWinRate"), winTolerance)
                assertEquals(sign * score.getDouble(0) * 20, actual.getDouble("blackScoreMean"), scoreTolerance)
                assertEquals(sign * score.getDouble(2) * 20, actual.getDouble("blackLead"), scoreTolerance)
                val owner = expected.getJSONArray("OutputOwnership")
                for (i in 0..360) assertEquals(-sign * tanh(owner.getDouble(i)), actual.getJSONArray("whiteOwnership").getDouble(i),
                    if (accelerator == Accelerator.NPU) 0.02 else 0.001)

                val board = BoardSnapshot.from(position)
                val logits = DoubleArray(362) { if (it == PASS) expected.getJSONArray("OutputPolicyPass").getDouble(0) else expected.getJSONArray("OutputPolicy").getDouble(it) }
                val max = logits.indices.filter(board::isLegal).maxOf { logits[it] }
                val weights = DoubleArray(362) { if (board.isLegal(it)) exp(logits[it] - max) else 0.0 }
                val total = weights.sum()
                val policy = actual.getJSONArray("policy")
                for (i in 0..PASS) {
                    if (board.isLegal(i)) assertEquals(weights[i] / total, policy.getDouble(i), if (accelerator == Accelerator.NPU) 0.01 else 0.0001)
                    else assertTrue("Illegal move received a policy probability", policy.getDouble(i) < 0)
                }
            }
        }
    }

    @Test
    fun officialRulesHandleCapturesSuicideKoAndPassesAndReplayEveryDemoBranch() {
        val record = RecordUiState()
        record.nodes.forEach { BoardSnapshot.from(record.positionFor(it.id)) }
        val capture = BoardSnapshot.from(moves("D16", "C16", "C17", "R3", "B16", "Q4", "C15"))
        assertEquals(0, capture.color(BoardPoint.parse("C16").index))
        assertEquals(1, capture.blackCaptures)
        assertFalse(capture.blackToMove)
        assertFalse(capture.isLegal(BoardPoint.parse("D16").index))
        val suicide = BoardSnapshot.from(moves("A2", "T19", "B1"))
        assertFalse(suicide.isLegal(BoardPoint.parse("A1").index))
        val ko = BoardSnapshot.from(moves("B3", "B2", "D3", "D2", "C4", "C1", "T19", "C3", "C2"))
        assertEquals(0, ko.color(BoardPoint.parse("C3").index))
        assertFalse(ko.isLegal(BoardPoint.parse("C3").index))
        val ended = BoardSnapshot.from(moves("pass", "pass"))
        assertTrue(ended.finished)
        assertFalse(ended.isLegal(PASS))
        assertThrows(IllegalStateException::class.java) { NativeKataGo.features(moves("D4", "D4").moves.toIntArray(), 7.5f) }
    }

    @Test
    fun finishedGameUsesOfficialScoreWithoutSearchOrCandidates() {
        KataGoSession.open(context, Accelerator.CPU).use { session ->
            val finished = moves("pass", "pass")
            session.setPosition(finished)
            val score = checkNotNull(session.analyze(32, AtomicBoolean(false)))
            assertTrue(score.gameFinished)
            assertEquals(0, score.visits)
            assertEquals(0f, score.blackWinRate, 0f)
            assertEquals(-7.5f, score.blackLead, 0f)
            assertTrue(score.candidates.isEmpty())
            session.setPosition(AnalysisPosition(emptyList()))
            val resumed = checkNotNull(session.analyze(32, AtomicBoolean(false)))
            assertFalse(resumed.gameFinished)
            assertTrue(resumed.candidates.isNotEmpty())
        }
    }

    @Test
    fun realSearchReturnsLegalVariationsAndCancelsPromptly() = runBlocking {
        KataGoSession.open(context, Accelerator.CPU).use { session ->
            val position = RecordUiState().position
            session.setPosition(position)
            val first = checkNotNull(session.analyze(32, AtomicBoolean(false)))
            val second = checkNotNull(session.analyze(128, AtomicBoolean(false)))
            assertEquals(position, second.position)
            assertTrue(second.visits >= 128 && second.visits > first.visits)
            assertTrue(second.blackWinRate in 0f..100f && second.blackLead.isFinite())
            assertTrue(second.candidates.isNotEmpty())
            second.candidates.forEach { candidate ->
                assertTrue(BoardSnapshot.from(position).isLegal(candidate.move))
                BoardSnapshot.from(position.after(candidate.pv))
            }
            session.setPosition(AnalysisPosition(emptyList()))
            val stop = AtomicBoolean(false)
            val running = async(Dispatchers.IO) { session.analyze(50000, stop) }
            delay(100)
            stop.set(true)
            withTimeout(3000) { assertNull(running.await()) }
        }
    }
}
