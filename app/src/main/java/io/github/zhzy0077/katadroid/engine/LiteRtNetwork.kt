package io.github.zhzy0077.katadroid.engine

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import io.github.zhzy0077.katadroid.NpuProbe
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Reused input/output buffers, called by KataGo's single NN server thread. */
@Keep
class LiteRtNetwork private constructor(
    private val environment: Environment,
    private val model: CompiledModel,
    private val inputs: Map<String, TensorBuffer>,
    private val outputs: Map<String, TensorBuffer>,
    val backend: String,
    val descriptor: File,
    val modelSpec: KataGoModel,
) : AutoCloseable {
    private val result = FloatArray(730)

    fun evaluate(spatial: FloatArray, global: FloatArray): FloatArray {
        require(spatial.size == 19 * 19 * 22 && global.size == 19)
        inputs.getValue("InputSpatial").writeFloat(spatial)
        inputs.getValue("InputGlobal").writeFloat(global)
        model.run(inputs, outputs, SIGNATURE)
        var offset = 0
        OUTPUTS.forEach { (name, size) ->
            val values = outputs.getValue(name).readFloat()
            check(values.size == size && values.all { it.isFinite() }) { "$name has invalid output shape or values" }
            values.copyInto(result, offset)
            offset += size
        }
        return result
    }

    override fun close() {
        outputs.values.toList().asReversed().forEach { it.close() }
        inputs.values.toList().asReversed().forEach { it.close() }
        model.close()
        environment.close()
        Log.i(TAG, "CLOSED backend=$backend")
    }

    companion object {
        private const val TAG = "KataDroidEngine"
        private const val SIGNATURE = "serving_default"
        private val OUTPUTS = linkedMapOf("OutputPolicy" to 361, "OutputPolicyPass" to 1,
            "OutputValue" to 3, "OutputScoreValue" to 4, "OutputOwnership" to 361)

        fun open(context: Context, accelerator: Accelerator = Accelerator.CPU,
                 modelSpec: KataGoModel = KataGoModel.B6): LiteRtNetwork {
            require(accelerator == Accelerator.CPU || accelerator == Accelerator.NPU)
            val descriptor = prepareModel(context, modelSpec)
            val npu = accelerator == Accelerator.NPU
            val nativeDir = if (npu) NpuProbe.loadCompiler(context).absolutePath
                else context.applicationInfo.nativeLibraryDir
            Log.i(TAG, "BEGIN backend=$accelerator model=${modelSpec.id}")
            val environment = Environment.create(context, if (npu) mapOf(
                Environment.Option.CompilerPluginLibraryDir to nativeDir,
                Environment.Option.DispatchLibraryDir to nativeDir,
            ) else emptyMap())
            var compiled: CompiledModel? = null
            val allocated = mutableListOf<TensorBuffer>()
            try {
                val options = NpuProbe.options(context, accelerator)
                val model = CompiledModel.create(context.assets, modelSpec.asset, options, environment)
                compiled = model
                val inputNames = listOf("InputSpatial", "InputGlobal")
                if (npu) for (name in inputNames + OUTPUTS.keys) {
                    val types = if (name in inputNames) model.getInputBufferRequirements(name, SIGNATURE).supportedTypes
                        else model.getOutputBufferRequirements(name, SIGNATURE).supportedTypes
                    NpuProbe.verifyBuffers(context, types, name)
                    Log.i(TAG, "NPU boundary=$name types=$types")
                }
                val inputs = inputNames.associateWith { model.createInputBuffer(it, SIGNATURE).also(allocated::add) }
                val outputs = OUTPUTS.keys.associateWith { model.createOutputBuffer(it, SIGNATURE).also(allocated::add) }
                Log.i(TAG, "READY backend=$accelerator")
                return LiteRtNetwork(environment, model, inputs, outputs, accelerator.name, descriptor, modelSpec)
            } catch (error: Throwable) {
                allocated.asReversed().forEach { it.close() }
                compiled?.close()
                environment.close()
                throw error
            }
        }

        private fun prepareModel(context: Context, spec: KataGoModel): File {
            fun hash(stream: java.io.InputStream): String = stream.use {
                val digest = MessageDigest.getInstance("SHA-256")
                val bytes = ByteArray(65536)
                while (true) {
                    val count = it.read(bytes)
                    if (count < 0) break
                    digest.update(bytes, 0, count)
                }
                digest.digest().joinToString("") { b -> "%02x".format(b.toInt() and 255) }
            }
            check(hash(context.assets.open(spec.asset)) == spec.sha256) { "KataGo model checksum mismatch" }
            val directory = File(context.noBackupFilesDir, "katago").apply { mkdirs() }
            val descriptor = File(directory, "${spec.id}.bin")
            if (!descriptor.isFile || hash(descriptor.inputStream()) != spec.descriptorSha256) {
                val temporary = File.createTempFile(spec.id, ".bin.tmp", directory)
                try {
                    context.assets.open(spec.descriptorAsset).use { input -> temporary.outputStream().use(input::copyTo) }
                    check(hash(temporary.inputStream()) == spec.descriptorSha256) { "KataGo descriptor checksum mismatch" }
                    check(temporary.renameTo(descriptor)) { "Unable to save KataGo model" }
                } finally { temporary.delete() }
            }
            return descriptor
        }
    }
}

class KataGoSession private constructor(private val runtime: LiteRtNetwork, private var handle: Long) : AnalysisSession {
    override val backend get() = runtime.backend
    override val supportsTimedAnalysis get() = true
    private lateinit var position: AnalysisPosition

    override fun setPosition(position: AnalysisPosition) {
        check(handle != 0L)
        NativeKataGo.setPositionJson(handle, position.json().toString())
        this.position = position
    }

    override fun resetForBenchmark(position: AnalysisPosition) {
        setPosition(position)
        NativeKataGo.clearSearchAndCache(handle)
    }

    override fun analyze(visits: Int, cancelled: AtomicBoolean): PositionAnalysis? {
        check(handle != 0L)
        val result = NativeKataGo.analyze(handle, visits, cancelled) ?: return null
        return PositionAnalysis.parse(position, JSONObject(result), backend)
    }

    override fun analyzeForTime(milliseconds: Int, cancelled: AtomicBoolean, maxVisits: Int): PositionAnalysis? {
        check(handle != 0L)
        val result = NativeKataGo.analyzeForTime(handle, milliseconds, maxVisits, cancelled) ?: return null
        return PositionAnalysis.parse(position, JSONObject(result), backend)
    }

    fun evaluate(position: AnalysisPosition, symmetry: Int = 0): JSONObject {
        check(handle != 0L)
        return JSONObject(NativeKataGo.evaluatePosition(handle, position.json().toString(), symmetry))
    }

    override fun close() {
        if (handle == 0L) return
        NativeKataGo.destroy(handle) // Joins the NN thread before its LiteRT buffers are freed.
        handle = 0
        runtime.close()
    }

    companion object {
        fun open(context: Context, accelerator: Accelerator = Accelerator.CPU,
                 model: KataGoModel = KataGoModel.B6): KataGoSession {
            val runtime = LiteRtNetwork.open(context, accelerator, model)
            try { return KataGoSession(runtime, NativeKataGo.create(runtime, runtime.descriptor.absolutePath, model.descriptorName)) }
            catch (error: Throwable) { runtime.close(); throw error }
        }
    }
}
