package io.github.zhzy0077.katadroid.engine

import io.github.zhzy0077.katadroid.R
import androidx.annotation.StringRes
import com.google.ai.edge.litert.Accelerator
import android.content.Context
import android.content.res.Resources
import java.io.File

/** Only paired, numerically verified exports belong in this catalog. */
data class KataGoModel(
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    val sourceName: String,
    val descriptorName: String,
    val sha256: String,
    val descriptorSha256: String,
    val contract: ModelContract = ModelContract.ONNX_V8,
    val directory: File? = null,
    val fixturesSha256: String? = null,
) {
    val asset get() = "katago/$id.tflite"
    val descriptorAsset get() = "katago/$id.bin"
    val fixturesAsset get() = if (this == B6) "katago/fixtures.json" else "katago/$id-fixtures.json"
    fun title(resources: Resources) = if (directory != null) sourceName else resources.getString(titleRes)
    fun openGraph(context: Context) = directory?.let { File(it, "model.tflite").inputStream() }
        ?: context.assets.open(asset)
    fun openDescriptor(context: Context) = directory?.let { File(it, "descriptor.bin").inputStream() }
        ?: context.assets.open(descriptorAsset)
    fun openFixtures(context: Context) = directory?.let { File(it, "fixtures.json").inputStream() }
        ?: context.assets.open(fixturesAsset)

    companion object {
    val B6 = KataGoModel("b6c96", R.string.model_b6_title, R.string.model_b6_description,
        "g170-b6c96-s175395328-d26788732", "g170-b6c96-s175395328-d26788732",
        "6611aa4aa2b6d51273337830ccd80884d8a1d3b46bb0a201e26cc3d798b35d72",
        "2bcc7c4daac6e33e9b9f5f8a427e1536b50aa705cfdb7fe21bffacbcdbfc7242")
    val B10 = KataGoModel("b10c128", R.string.model_b10_title, R.string.model_b10_description,
        // The official archive has a g170e filename but a g170 descriptor name.
        "g170e-b10c128-s1141046784-d204142634", "g170-b10c128-s1141046784-d204142634",
        "a4655e42d204ecb4ce3c181ff51401eabf9f165e21f31191a6096ba512165326",
        "49b75eacdfd8587fe153e6889070d509504750a45fda16105e67bf8c456148d4")

        val bundled = listOf(B6, B10)
        @Volatile private var imported: List<KataGoModel> = emptyList()
        val entries get() = bundled + imported
        fun refresh(context: Context) { imported = ModelPackages.installed(context) }
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: B6
    }
}

enum class EngineBackend(@param:StringRes val labelRes: Int) {
    AUTO(R.string.backend_auto), CPU(R.string.backend_cpu), GPU(R.string.backend_gpu), NPU(R.string.backend_npu);

    fun accelerator(): Accelerator = when (this) {
        AUTO -> error("AUTO must be resolved by opening an available runtime")
        CPU -> Accelerator.CPU
        GPU -> Accelerator.GPU
        NPU -> Accelerator.NPU
    }

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.name == id } ?: AUTO
    }
}

data class EngineConfig(val model: KataGoModel = KataGoModel.B6, val backend: EngineBackend = EngineBackend.AUTO) {
    val accelerator get() = backend.accelerator()
    // Persist results using the actual backend, never an unresolved AUTO key.
    val cacheKey get() = "${if (model.directory == null) model.sha256 else "${model.id}-adapter1"}-${backend.name}"
    val label get() = "${model.id} · ${backend.name}"
}
