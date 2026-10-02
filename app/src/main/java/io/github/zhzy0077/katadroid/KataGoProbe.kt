package io.github.zhzy0077.katadroid

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import io.github.zhzy0077.katadroid.engine.KataGoModel
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Fixed 19x19 engine-generated positions, checked against official KataGo ONNX outputs. */
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
        val fixtures = JSONObject(context.assets.open(modelSpec.fixturesAsset).bufferedReader().use { it.readText() })
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(modelSpec.asset).use { stream ->
            val block = ByteArray(65536)
            while (true) {
                val count = stream.read(block)
                if (count < 0) break
                digest.update(block, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        check(hash == fixtures.getString("tfliteSha256") && hash == modelSpec.sha256) { "SHA-256 mismatch between model and reference" }
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val isNpu = accelerator == Accelerator.NPU
        if (isNpu) NpuProbe.loadCompiler(context)
        val envOptions = if (isNpu) mapOf(
            Environment.Option.CompilerPluginLibraryDir to nativeDir,
            Environment.Option.DispatchLibraryDir to nativeDir,
        ) else emptyMap()
        Log.i(TAG, "BEGIN backend=$accelerator model=${fixtures.getString("modelName")}")
        val started = SystemClock.elapsedRealtimeNanos()
        Environment.create(context, envOptions).use { env ->
            val options = NpuProbe.options(context, accelerator)
            CompiledModel.create(context.assets, modelSpec.asset, options, env).use { model ->
                val initMs = (SystemClock.elapsedRealtimeNanos() - started) / 1e6
                val signature = fixtures.getString("signature")
                val inputNames = fixtures.getJSONObject("inputShapes").keys().asSequence().toList()
                val outputNames = fixtures.getJSONObject("outputShapes").keys().asSequence().toList()
                if (isNpu) {
                    // Check every boundary. Full graph delegation still requires the native log audit.
                    for (name in inputNames + outputNames) {
                        val types = if (name in inputNames) model.getInputBufferRequirements(name, signature).supportedTypes
                            else model.getOutputBufferRequirements(name, signature).supportedTypes
                        Log.i(TAG, "NPU boundary=$name types=$types")
                        NpuProbe.verifyBuffers(context, types, name)
                    }
                }
                val allocated = mutableListOf<TensorBuffer>()
                try {
                    val inputs = inputNames.associateWith { model.createInputBuffer(it, signature).also(allocated::add) }
                    val outputs = outputNames.associateWith { model.createOutputBuffer(it, signature).also(allocated::add) }
                    val cases = fixtures.getJSONArray("cases")
                    val results = (0 until cases.length()).map { index ->
                        coroutineContext.ensureActive()
                        val case = cases.getJSONObject(index)
                        inputs.forEach { (name, buffer) -> buffer.writeFloat(case.getJSONObject("inputs").getJSONArray(name).floats()) }
                        model.run(inputs, outputs, signature)
                        val actual = outputs.mapValues { it.value.readFloat() }
                        val expected = outputNames.associateWith { case.getJSONObject("outputs").getJSONArray(it).floats() }
                        compare(case.getString("name"), actual, expected, isNpu)
                    }
                    repeat(5) { coroutineContext.ensureActive(); model.run(inputs, outputs, signature) }
                    val times = DoubleArray(20) {
                        coroutineContext.ensureActive()
                        val before = SystemClock.elapsedRealtimeNanos()
                        model.run(inputs, outputs, signature)
                        outputs.values.forEach { it.readFloat() }
                        (SystemClock.elapsedRealtimeNanos() - before) / 1e6
                    }.sorted()
                    Result(accelerator.name, initMs, (times[9] + times[10]) / 2, times[18], results, modelSpec.id)
                        .also { Log.i(TAG, "PASS ${it.display()}") }
                } finally {
                    allocated.asReversed().forEach { it.close() }
                }
            }
        }
    }

    private fun compare(label: String, actual: Map<String, FloatArray>, expected: Map<String, FloatArray>,
                        npu: Boolean): CaseResult {
        var maxError = 0f
        for ((name, reference) in expected) {
            val output = actual.getValue(name)
            check(output.size == reference.size) { "$label/$name output size mismatch" }
            output.indices.forEach { i ->
                check(output[i].isFinite()) { "$label/$name contains non-finite values" }
                val error = abs(output[i] - reference[i])
                maxError = maxOf(maxError, error)
                val tolerance = if (npu) 0.1f + 0.02f * abs(reference[i]) else 0.001f + 0.0001f * abs(reference[i])
                check(error <= tolerance) { "$label/$name[$i] error $error exceeds $tolerance" }
            }
        }
        fun policy(values: Map<String, FloatArray>) = softmax(values.getValue("OutputPolicy") + values.getValue("OutputPolicyPass"))
        val p = policy(expected)
        val q = policy(actual)
        val kl = p.indices.sumOf { p[it] * ln(p[it] / q[it]) }
        val winError = abs(softmax(actual.getValue("OutputValue"))[0] - softmax(expected.getValue("OutputValue"))[0])
        val scoreError = abs(actual.getValue("OutputScoreValue")[0] - expected.getValue("OutputScoreValue")[0]) * 20.0
        val owner = actual.getValue("OutputOwnership")
        val ownerRef = expected.getValue("OutputOwnership")
        val ownerError = owner.indices.sumOf { abs(tanh(owner[it].toDouble()) - tanh(ownerRef[it].toDouble())) } / owner.size
        check(kl <= 0.005 && winError <= 0.01 && scoreError <= 0.25 && ownerError <= 0.01) {
            "$label precision check failed: KL=$kl, win-rate error=$winError, score error=$scoreError, ownership MAE=$ownerError"
        }
        return CaseResult(label, maxError, kl, winError, scoreError, ownerError)
    }

    private fun JSONArray.floats() = FloatArray(length()) { getDouble(it).toFloat() }
    private fun softmax(values: FloatArray): DoubleArray {
        val max = values.max().toDouble()
        val exps = DoubleArray(values.size) { exp(values[it] - max) }
        val total = exps.sum()
        return DoubleArray(values.size) { exps[it] / total }
    }
}
