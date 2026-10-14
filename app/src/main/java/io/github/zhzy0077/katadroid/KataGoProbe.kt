package io.github.zhzy0077.katadroid

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import io.github.zhzy0077.katadroid.engine.KataGoModel
import io.github.zhzy0077.katadroid.engine.LiteRtNetwork
import io.github.zhzy0077.katadroid.engine.ModelContract
import io.github.zhzy0077.katadroid.engine.ModelValidation
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Engine-generated fixtures checked through the same adapter used by native search. */
object KataGoProbe {
    private const val TAG = "KataDroidModel"

    data class CaseResult(val label: String, val rawError: Float, val policyKl: Double,
                          val winError: Double, val scoreError: Double, val ownershipError: Double)
    data class Result(val backend: String, val initMs: Double, val medianMs: Double,
                      val p95Ms: Double, val cases: List<CaseResult>, val modelId: String = "b6c96") {
        fun display(context: Context? = null): String = buildString {
            append(context?.getString(R.string.model_report, backend, modelId, initMs, medianMs, p95Ms)
                ?: String.format(Locale.ROOT, "%s · %s\nInitialization %.1f ms; inference median %.2f ms, P95 %.2f ms\n",
                    backend, modelId, initMs, medianMs, p95Ms))
            cases.forEach {
                append(context?.getString(R.string.model_case_report, io.github.zhzy0077.katadroid.ui.benchmarkCaseLabel(context.resources, it.label), it.rawError, it.policyKl,
                    it.winError * 100, it.scoreError, it.ownershipError)
                    ?: String.format(Locale.ROOT, "%s: five output heads passed\nMax raw error %.5f; policy KL %.6f\nWin-rate error %.3f pp; score error %.3f points; ownership MAE %.5f\n",
                        it.label, it.rawError, it.policyKl, it.winError * 100, it.scoreError, it.ownershipError))
            }
        }
    }

    suspend fun run(context: Context, accelerator: Accelerator, modelSpec: KataGoModel = KataGoModel.B6): Result = withContext(Dispatchers.Default) {
        val fixtures = ModelValidation.fixtures(context, modelSpec)
        val started = SystemClock.elapsedRealtimeNanos()
        LiteRtNetwork.open(context, accelerator, modelSpec).use { runtime ->
            val initMs = (SystemClock.elapsedRealtimeNanos() - started) / 1e6
            val cases = fixtures.getJSONArray("cases")
            require(cases.length() in 3..32)
            var spatial = FloatArray(22 * 361)
            var global = FloatArray(19)
            val results = (0 until cases.length()).map { index ->
                coroutineContext.ensureActive()
                val case = cases.getJSONObject(index)
                spatial = ModelValidation.floats(case.getJSONObject("inputs").getJSONArray("InputSpatial"), 22 * 361)
                global = ModelValidation.floats(case.getJSONObject("inputs").getJSONArray("InputGlobal"), 19)
                compare(case.getString("name"), heads(runtime.evaluate(spatial, global)),
                    heads(ModelValidation.expected(case, modelSpec.contract)),
                    accelerator == Accelerator.NPU && modelSpec.directory == null && modelSpec.contract == ModelContract.ONNX_V8,
                    fixtures.optJSONObject("postprocess")?.optDouble("scoreMean", 20.0) ?: 20.0)
            }
            repeat(5) { coroutineContext.ensureActive(); runtime.evaluate(spatial, global) }
            val times = DoubleArray(20) {
                coroutineContext.ensureActive()
                val before = SystemClock.elapsedRealtimeNanos()
                runtime.evaluate(spatial, global)
                (SystemClock.elapsedRealtimeNanos() - before) / 1e6
            }.sorted()
            Result(accelerator.name, initMs, (times[9] + times[10]) / 2, times[18], results, modelSpec.id)
                .also { Log.i(TAG, "PASS ${it.display()}") }
        }
    }

    internal fun heads(raw: FloatArray): Map<String, FloatArray> = mapOf(
        "OutputPolicy" to raw.copyOfRange(ModelContract.POLICY, ModelContract.POLICY + 361),
        "OutputPolicyPass" to floatArrayOf(raw[361]),
        "OptimisticPolicy" to raw.copyOfRange(ModelContract.OPTIMISTIC_POLICY, ModelContract.VALUE),
        "OutputValue" to raw.copyOfRange(ModelContract.VALUE, ModelContract.SCORE),
        "OutputScoreValue" to raw.copyOfRange(ModelContract.SCORE, ModelContract.OWNERSHIP),
        "OutputOwnership" to raw.copyOfRange(ModelContract.OWNERSHIP, ModelContract.RAW_SIZE))

    internal fun compare(label: String, actual: Map<String, FloatArray>, expected: Map<String, FloatArray>,
                         accelerated: Boolean, scoreMultiplier: Double = 20.0): CaseResult {
        var maxError = 0f
        for ((name, reference) in expected) {
            val output = actual.getValue(name)
            check(output.size == reference.size) { "$label/$name output size mismatch" }
            output.indices.forEach { i ->
                check(output[i].isFinite()) { "$label/$name contains non-finite values" }
                val error = abs(output[i] - reference[i])
                maxError = maxOf(maxError, error)
                val tolerance = if (accelerated) 0.1f + 0.02f * abs(reference[i]) else 0.001f + 0.0001f * abs(reference[i])
                check(error <= tolerance) { "$label/$name[$i] error $error exceeds $tolerance" }
            }
        }
        fun policy(values: Map<String, FloatArray>) = softmax(values.getValue("OutputPolicy") + values.getValue("OutputPolicyPass"))
        val p = policy(expected)
        val q = policy(actual)
        val kl = p.indices.sumOf { p[it] * ln(p[it] / q[it]) }
        val actualValue = softmax(actual.getValue("OutputValue"))
        val expectedValue = softmax(expected.getValue("OutputValue"))
        val winError = abs(actualValue[0] + 0.5 * actualValue[2] - expectedValue[0] - 0.5 * expectedValue[2])
        val scoreError = abs(actual.getValue("OutputScoreValue")[0] * (1 - actualValue[2]) -
            expected.getValue("OutputScoreValue")[0] * (1 - expectedValue[2])) * scoreMultiplier
        val owner = actual.getValue("OutputOwnership")
        val ownerRef = expected.getValue("OutputOwnership")
        val ownerError = owner.indices.sumOf { abs(tanh(owner[it].toDouble()) - tanh(ownerRef[it].toDouble())) } / owner.size
        check(kl <= 0.005 && winError <= 0.01 && scoreError <= 0.25 && ownerError <= 0.01) {
            "$label precision check failed: KL=$kl, win-rate error=$winError, score error=$scoreError, ownership MAE=$ownerError"
        }
        return CaseResult(label, maxError, kl, winError, scoreError, ownerError)
    }

    private fun softmax(values: FloatArray): DoubleArray {
        val max = values.max().toDouble()
        val exps = DoubleArray(values.size) { exp(values[it] - max) }
        val total = exps.sum()
        return DoubleArray(values.size) { exps[it] / total }
    }
}
