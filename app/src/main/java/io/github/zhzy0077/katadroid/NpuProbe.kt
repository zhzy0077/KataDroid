package io.github.zhzy0077.katadroid

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

    enum class Vendor(val compiler: String, val libraries: List<String>) {
        QUALCOMM("libLiteRtCompilerPlugin_Qualcomm.so", listOf(
            "libLiteRtCompilerPlugin_Qualcomm.so", "libLiteRtDispatch_Qualcomm.so",
            "libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnIr.so", "libQnnSaver.so")),
        MEDIATEK("libLiteRtCompilerPlugin_MediaTek.so", listOf(
            "libLiteRtCompilerPlugin_MediaTek.so", "libLiteRtDispatch_MediaTek.so")),
    }

    /** Packaged plugins are candidates; compilation still determines compatibility. */
    fun vendor(context: Context): Vendor? {
        val available = Vendor.entries.filter { candidate -> candidate.libraries.all {
            File(context.applicationInfo.nativeLibraryDir, it).isFile
        } }
        val preferred = if (Build.SOC_MANUFACTURER.equals("MediaTek", ignoreCase = true)) Vendor.MEDIATEK else Vendor.QUALCOMM
        return preferred.takeIf { it in available } ?: available.singleOrNull()
    }

    fun hasRuntimeLibraries(context: Context): Boolean = vendor(context) != null

    @Synchronized
    fun loadCompiler(context: Context): File {
        val selected = checkNotNull(vendor(context)) { context.getString(R.string.npu_unavailable) }
        val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
        val directory = try {
            NpuRuntimeDirectory.prepare(
                File(context.noBackupFilesDir, "npu-runtime/${selected.name.lowercase(Locale.ROOT)}"),
                nativeDirectory, selected.compiler, "libLiteRtDispatch_${if (selected == Vendor.QUALCOMM) "Qualcomm" else "MediaTek"}.so",
                includeQnn = selected == Vendor.QUALCOMM,
            )
        } catch (error: Exception) {
            Log.e("KataDroidEngine", "Cannot isolate selected NPU libraries", error)
            throw java.io.IOException(context.getString(R.string.npu_runtime_prepare_failed, selected.name), error)
        }
        System.load(File(nativeDirectory, selected.compiler).absolutePath)
        Log.i("KataDroidEngine", "NPU vendor=$selected")
        return directory
    }

    fun options(context: Context, accelerator: Accelerator): CompiledModel.Options =
        CompiledModel.Options(accelerator).apply {
            if (accelerator == Accelerator.NPU && vendor(context) == Vendor.QUALCOMM)
                qualcommOptions = CompiledModel.QualcommOptions(logLevel = CompiledModel.QualcommOptions.LogLevel.WARN)
        }

    fun verifyBuffers(context: Context, types: List<TensorBufferType>, name: String) {
        val hardware = when (vendor(context)) {
            Vendor.QUALCOMM -> TensorBufferType.FastRpc in types
            Vendor.MEDIATEK -> TensorBufferType.Ahwb in types || TensorBufferType.DmaBuf in types
            null -> false
        }
        check(hardware && TensorBufferType.HostMemory !in types) {
            context.getString(R.string.npu_buffer_mismatch, name, vendor(context)?.name ?: "unknown", types.toString())
        }
    }

    suspend fun run(context: Context, accelerator: Accelerator): Result = withContext(Dispatchers.Default) {
        val nativeDir = if (accelerator == Accelerator.NPU) loadCompiler(context).absolutePath
            else context.applicationInfo.nativeLibraryDir
        val envOptions = if (accelerator == Accelerator.NPU) mapOf(
            Environment.Option.CompilerPluginLibraryDir to nativeDir,
            Environment.Option.DispatchLibraryDir to nativeDir,
        ) else emptyMap()
        val start = SystemClock.elapsedRealtimeNanos()
        Environment.create(context, envOptions).use { environment ->
            Log.i(TAG, "Requested=$accelerator available=${environment.getAvailableAccelerators()}")
            // No CPU/GPU fallback requested. Confirm graph assignment in native logs too.
            val options = options(context, accelerator)
            CompiledModel.create(context.assets, "conv_probe.tflite",
                options, environment).use { model ->
                val initMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
                val inputTypes = model.getInputBufferRequirements("board").supportedTypes
                Log.i(TAG, "$accelerator inputBuffers=$inputTypes")
                if (accelerator == Accelerator.NPU) {
                    verifyBuffers(context, inputTypes, "board")
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
