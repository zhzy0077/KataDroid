package io.github.zhzy0077.katadroid

import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import io.github.zhzy0077.katadroid.engine.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit optional-package test; ordinary regression runs need no large weights. */
class OptionalModelIntegrationTest {
    @Test fun importedTransformerMatchesUpstreamForEverySymmetryAndSearchesLegally() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val accelerator = Accelerator.valueOf(arguments.getString("backend") ?: "CPU")
        val name = arguments.getString("modelPackage")
        assumeTrue("Supply -e modelPackage <external-files filename>", name != null)
        require(name!!.matches(Regex("[A-Za-z0-9_.-]+")))
        val archive = File(context.getExternalFilesDir(null), name)
        require(archive.isFile)
        val spec = archive.inputStream().use { ModelPackages.install(context, it) }
        try {
            assertEquals(ModelContract.PYTORCH_V17, spec.contract)
            assertEquals(KataGoModel.B6, EngineConfig().model)
            assertEquals(spec, KataGoModel.fromId(spec.id))
            val fixtures = ModelValidation.fixtures(context, spec)
            val params = fixtures.getJSONObject("postprocess")
            val cases = fixtures.getJSONArray("cases")
            KataGoSession.open(context, accelerator, spec).use { session ->
                for (index in 0 until cases.length()) {
                    val case = cases.getJSONObject(index)
                    val moves = Regex(";[BW]\\[([a-s]{2})\\]").findAll(case.getString("sgf")).map {
                        val coordinate = it.groupValues[1]
                        (coordinate[1] - 'a') * 19 + (coordinate[0] - 'a')
                    }.toList()
                    val position = AnalysisPosition(moves)
                    val features = NativeKataGo.features(position.moves.toIntArray(), position.komi)
                    val spatial = ModelValidation.floats(case.getJSONObject("inputs").getJSONArray("InputSpatial"), 22 * 361)
                    val global = ModelValidation.floats(case.getJSONObject("inputs").getJSONArray("InputGlobal"), 19)
                    assertArrayEquals(spatial + global, features, 1e-6f)
                    val board = BoardSnapshot.from(position)
                    val references = case.getJSONArray("symmetryRaw")
                    assertEquals(8, references.length())
                    for (symmetry in 0..7) {
                        val reference = ModelValidation.floats(references.getJSONArray(symmetry), ModelContract.RAW_SIZE)
                        val actual = session.evaluate(position, symmetry)
                        val emitted = session.rawForVerification()
                        val raw = emitted.copyOf()
                        for (start in listOf(0, 362, 733)) for (y in 0 until 19) for (x in 0 until 19) {
                            var mappedY = if (symmetry and 1 != 0) 18 - y else y
                            var mappedX = if (symmetry and 2 != 0) 18 - x else x
                            if (symmetry and 4 != 0) { val swap = mappedY; mappedY = mappedX; mappedX = swap }
                            raw[start + y * 19 + x] = emitted[start + mappedY * 19 + mappedX]
                        }
                        // First test the CPU graph against upstream at the import
                        // tolerance, then verify native decoding against its exact
                        // received RAW heads, independently of CPU kernel rounding.
                        for (i in raw.indices) assertEquals("${case.getString("name")}/$symmetry raw[$i]",
                            reference[i], raw[i], 0.001f + 0.0001f * abs(reference[i]))
                        val sign = if (moves.size % 2 == 0) 1.0 else -1.0
                        // Chinese uses simple ko, so upstream retains the no-result
                        // class and counts it as half a win in the displayed equity.
                        val maxValue = (724..726).maxOf { raw[it].toDouble() }
                        val categorical = DoubleArray(3) { exp(raw[724 + it] - maxValue) }
                        val noResult = categorical[2] / categorical.sum()
                        val currentEquity = categorical[0] / categorical.sum() + 0.5 * noResult
                        assertEquals((if (sign > 0) currentEquity else 1 - currentEquity) * 100,
                            actual.getDouble("blackWinRate"), 0.003)
                        assertEquals(sign * raw[727] * params.getDouble("scoreMean") * (1 - noResult), actual.getDouble("blackScoreMean"), 0.003)
                        assertEquals(sign * raw[729] * params.getDouble("lead") * (1 - noResult), actual.getDouble("blackLead"), 0.003)
                        fun softplus(x: Double) = if (x > 30) x else ln1p(exp(x))
                        val mean = raw[727] * params.getDouble("scoreMean")
                        val stdev = softplus(raw[728].toDouble()) * params.getDouble("scoreStdev")
                        val expectedVariance = (mean * mean + stdev * stdev) * (1 - noResult) -
                            mean * mean * (1 - noResult) * (1 - noResult)
                        assertEquals(sqrt(max(0.0, expectedVariance)), actual.getDouble("scoreStdev"), 0.003)
                        assertEquals(softplus(raw[730].toDouble()) * params.getDouble("varianceTime"), actual.getDouble("varianceTime"), 0.005)
                        assertEquals(softplus(raw[731] * 0.5) * sqrt(params.getDouble("shorttermValueError")), actual.getDouble("shorttermWinlossError"), 0.001)
                        assertEquals(softplus(raw[732] * 0.5) * sqrt(params.getDouble("shorttermScoreError")), actual.getDouble("shorttermScoreError"), 0.003)
                        val max = (0..361).filter(board::isLegal).maxOf { raw[it].toDouble() }
                        val weights = DoubleArray(362) { if (board.isLegal(it)) exp(raw[it] - max) else 0.0 }
                        val total = weights.sum()
                        for (point in 0..361) {
                            val probability = actual.getJSONArray("policy").getDouble(point)
                            if (board.isLegal(point)) assertEquals(weights[point] / total, probability, 0.0001)
                            else assertTrue(probability < 0)
                        }
                        for (point in 0 until 361) assertEquals(-sign * tanh(raw[733 + point].toDouble()),
                            actual.getJSONArray("whiteOwnership").getDouble(point), 0.001)
                    }
                    session.setPosition(position)
                    val analysis = checkNotNull(session.analyze(8, AtomicBoolean(false)))
                    assertTrue(analysis.visits >= 8)
                    assertTrue(analysis.candidates.isNotEmpty())
                    assertTrue(analysis.candidates.all { board.isLegal(it.move) })
                    assertNull(session.analyze(32, AtomicBoolean(true)))
                }
            }
            assertEquals(spec.id, KataGoProbe.run(context, accelerator, spec).modelId)
        } finally {
            spec.directory!!.deleteRecursively()
            KataGoModel.refresh(context)
        }
    }
}
