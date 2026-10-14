package io.github.zhzy0077.katadroid.engine

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import io.github.zhzy0077.katadroid.NpuProbe
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
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
    private val result = FloatArray(ModelContract.RAW_SIZE)
    private val spatialBuffer = FloatArray(19 * 19 * 22)
    private var gpuExecutor: ExecutorService? = null

    fun evaluate(spatial: FloatArray, global: FloatArray): FloatArray = gpuExecutor?.let {
        onThread(it) { evaluateDirect(spatial, global) }
    } ?: evaluateDirect(spatial, global)

    private fun evaluateDirect(spatial: FloatArray, global: FloatArray): FloatArray {
        require(spatial.size == 19 * 19 * 22 && global.size == 19)
        val contract = modelSpec.contract
        inputs.getValue(contract.spatialInput).writeFloat(contract.spatial(spatial, spatialBuffer))
        inputs.getValue(contract.globalInput).writeFloat(global)
        model.run(inputs, outputs, SIGNATURE)
        return contract.pack(outputs.mapValues { it.value.readFloat() }, result)
    }

    internal fun rawForVerification(): FloatArray = result.copyOf()

    override fun close() {
        val executor = gpuExecutor
        if (executor == null) closeDirect()
        else try { onThread(executor) { closeDirect() } } finally { executor.shutdown() }
    }

    private fun closeDirect() {
        outputs.values.toList().asReversed().forEach { it.close() }
        inputs.values.toList().asReversed().forEach { it.close() }
        model.close()
        environment.close()
        Log.i(TAG, "CLOSED backend=$backend")
    }

    companion object {
        private const val TAG = "KataDroidEngine"
        private const val SIGNATURE = "serving_default"

        private fun <T> onThread(executor: ExecutorService, block: () -> T): T = try {
            executor.submit(Callable { block() }).get()
        } catch (error: ExecutionException) { throw (error.cause ?: error) }

        fun open(context: Context, accelerator: Accelerator = Accelerator.CPU,
                 modelSpec: KataGoModel = KataGoModel.B6): LiteRtNetwork {
            if (accelerator != Accelerator.GPU) return openDirect(context, accelerator, modelSpec)
            // OpenGL implementations require compile, invocation, readback and
            // destruction on one context-owning thread. Native NN callbacks join
            // this executor instead of migrating the GPU context across threads.
            val executor = Executors.newSingleThreadExecutor { Thread(it, "katadroid-gpu") }
            try { return onThread(executor) { openDirect(context, accelerator, modelSpec) }
                .also { it.gpuExecutor = executor } }
            catch (error: Throwable) { executor.shutdown(); throw error }
        }

        private fun openDirect(context: Context, accelerator: Accelerator, modelSpec: KataGoModel): LiteRtNetwork {
            require(accelerator in listOf(Accelerator.CPU, Accelerator.GPU, Accelerator.NPU))
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
                val model = modelSpec.directory?.let {
                    CompiledModel.create(File(it, "model.tflite").absolutePath, options, environment)
                } ?: CompiledModel.create(context.assets, modelSpec.asset, options, environment)
                compiled = model
                val contract = modelSpec.contract
                val inputNames = listOf(contract.spatialInput, contract.globalInput)
                for ((name, shapes) in contract.shapes()) {
                    val type = if (name in inputNames) model.getInputTensorType(name, SIGNATURE)
                        else model.getOutputTensorType(name, SIGNATURE)
                    check(type.elementType == TensorType.ElementType.FLOAT && (type.layout?.dimensions ?: emptyList()) in shapes) {
                        "Invalid graph boundary: $name $type"
                    }
                }
                if (npu) for (name in inputNames + contract.outputSizes.keys) {
                    val types = if (name in inputNames) model.getInputBufferRequirements(name, SIGNATURE).supportedTypes
                        else model.getOutputBufferRequirements(name, SIGNATURE).supportedTypes
                    NpuProbe.verifyBuffers(context, types, name)
                    Log.i(TAG, "NPU boundary=$name types=$types")
                }
                val inputs = inputNames.associateWith { model.createInputBuffer(it, SIGNATURE).also(allocated::add) }
                val outputs = contract.outputSizes.keys.associateWith { model.createOutputBuffer(it, SIGNATURE).also(allocated::add) }
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
            check(hash(spec.openGraph(context)) == spec.sha256) { "KataGo model checksum mismatch" }
            if (spec.directory != null) {
                PortableGraph.check(File(spec.directory, "model.tflite"))
                val descriptor = File(spec.directory, "descriptor.bin")
                check(hash(descriptor.inputStream()) == spec.descriptorSha256) { "KataGo descriptor checksum mismatch" }
                check(hash(spec.openFixtures(context)) == spec.fixturesSha256) { "KataGo fixtures checksum mismatch" }
                return descriptor
            }
            val directory = File(context.noBackupFilesDir, "katago").apply { mkdirs() }
            val descriptor = File(directory, "${spec.id}.bin")
            if (!descriptor.isFile || hash(descriptor.inputStream()) != spec.descriptorSha256) {
                val temporary = File.createTempFile(spec.id, ".bin.tmp", directory)
                try {
                    spec.openDescriptor(context).use { input -> temporary.outputStream().use(input::copyTo) }
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

    internal fun rawForVerification(): FloatArray = runtime.rawForVerification()

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
            try {
                // Imported packages require the CPU reference tolerance on every backend.
                if (accelerator == Accelerator.GPU || (model.directory != null && accelerator != Accelerator.CPU))
                    ModelValidation.verify(runtime, ModelValidation.fixtures(context, model))
                return KataGoSession(runtime, NativeKataGo.create(runtime, runtime.descriptor.absolutePath,
                    model.descriptorName, model.contract.modelVersion))
            } catch (error: Throwable) { runtime.close(); throw error }
        }
    }
}
