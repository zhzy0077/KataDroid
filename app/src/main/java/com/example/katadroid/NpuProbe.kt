package com.example.katadroid

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorBufferType
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A deterministic convolution checks runtime integration, not KataGo performance. */
object NpuProbe {
    private const val TAG = "KataDroidProbe"
    private const val SIZE = 19
    private const val CHANNELS = 4

    data class Result(val backend: String, val initMs: Double, val medianMs: Double,
                      val p95Ms: Double, val maxError: Float) {
        fun display(context: Context? = null): String = context?.getString(R.string.conv_report,
            backend, initMs, medianMs, p95Ms, maxError) ?: String.format(Locale.ROOT,
            "%s: output validated\nInitialization %.2f ms\nMedian %.3f ms, P95 %.3f ms\nMax error %.6f",
            backend, initMs, medianMs, p95Ms, maxError)
    }

    fun deviceDescription(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}\n${Build.SOC_MODEL} · Android ${Build.VERSION.RELEASE}"

    /** A packaged runtime is a candidate, not a promise of hardware support. */
    fun hasRuntimeLibraries(context: Context): Boolean = requiredLibraries.all {
        File(context.applicationInfo.nativeLibraryDir, it).isFile
    }

    private val requiredLibraries = listOf("libLiteRtCompilerPlugin_Qualcomm.so", "libLiteRtDispatch_Qualcomm.so",
        "libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnIr.so", "libQnnSaver.so")

    fun loadCompiler(context: Context) {
        check(hasRuntimeLibraries(context)) { context.getString(R.string.npu_unavailable) }
        System.load(File(context.applicationInfo.nativeLibraryDir, "libLiteRtCompilerPlugin_Qualcomm.so").absolutePath)
    }

    suspend fun run(context: Context, accelerator: Accelerator): Result = withContext(Dispatchers.Default) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        if (accelerator == Accelerator.NPU) loadCompiler(context)
        val envOptions = if (accelerator == Accelerator.NPU) mapOf(
            Environment.Option.CompilerPluginLibraryDir to nativeDir,
            Environment.Option.DispatchLibraryDir to nativeDir,
        ) else emptyMap()
        val start = SystemClock.elapsedRealtimeNanos()
        Environment.create(context, envOptions).use { environment ->
            Log.i(TAG, "Requested=$accelerator available=${environment.getAvailableAccelerators()}")
            // No CPU/GPU fallback requested. Confirm graph assignment in native logs too.
            val options = CompiledModel.Options(accelerator).apply {
                if (accelerator == Accelerator.NPU) qualcommOptions = CompiledModel.QualcommOptions(
                    logLevel = CompiledModel.QualcommOptions.LogLevel.INFO)
            }
            CompiledModel.create(context.assets, "conv_probe.tflite",
                options, environment).use { model ->
                val initMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                val inputTypes = model.getInputBufferRequirements("board").supportedTypes
                Log.i(TAG, "$accelerator inputBuffers=$inputTypes")
                if (accelerator == Accelerator.NPU) {
                    // This probe has exactly one Conv2D. Its boundary must be QNN-backed.
                    // This is not a general proof of full delegation for multi-op networks.
                    check(TensorBufferType.FastRpc in inputTypes &&
                        TensorBufferType.HostMemory !in inputTypes) {
                        "QNN execution path not confirmed; possible CPU fallback: $inputTypes"
                    }
                }
                val buffers = mutableListOf<TensorBuffer>()
                try {
                    val inputs = model.createInputBuffers().also { buffers.addAll(it) }
                    val outputs = model.createOutputBuffers().also { buffers.addAll(it) }
                    check(inputs.size == 1 && outputs.size == 1)
                    var maxError = 0f
                    // Check padding, layout, signs, and input updates against a scalar reference.
                    for (seed in 0..2) {
                        coroutineContext.ensureActive()
                        val input = FloatArray(SIZE * SIZE) { ((it * 7 + seed * 3) % 31 - 15) / 16f }
                        inputs.single().writeFloat(input)
                        model.run(inputs, outputs)
                        val actual = outputs.single().readFloat()
                        val expected = reference(input)
                        check(actual.size == expected.size)
                        for (i in actual.indices) {
                            check(actual[i].isFinite()) { "Output contains non-finite values" }
                            maxError = maxOf(maxError, abs(actual[i] - expected[i]))
                        }
                    }
                    check(maxError <= 0.01f) { "Output validation failed; maximum absolute error $maxError" }
                    repeat(10) { coroutineContext.ensureActive(); model.run(inputs, outputs) }
                    val times = DoubleArray(100) {
                        coroutineContext.ensureActive()
                        val before = SystemClock.elapsedRealtimeNanos()
                        model.run(inputs, outputs)
                        outputs.single().readFloat() // Synchronize and include readback.
                        (SystemClock.elapsedRealtimeNanos() - before) / 1e6
                    }.sorted()
                    Result(accelerator.name, initMs, (times[49] + times[50]) / 2,
                        times[94], maxError).also { Log.i(TAG, it.display()) }
                } finally {
                    buffers.asReversed().forEach { it.close() }
                }
            }
        }
    }

    private fun reference(input: FloatArray): FloatArray = FloatArray(SIZE * SIZE * CHANNELS) { i ->
        val channel = i % CHANNELS
        val y = i / CHANNELS / SIZE
        val x = i / CHANNELS % SIZE
        var sum = channel / 8f
        for (ky in 0..2) for (kx in 0..2) {
            val iy = y + ky - 1
            val ix = x + kx - 1
            if (iy in 0 until SIZE && ix in 0 until SIZE) {
                sum += input[iy * SIZE + ix] * ((ky * 3 + kx - 4) / 16f)
            }
        }
        sum
    }
}
