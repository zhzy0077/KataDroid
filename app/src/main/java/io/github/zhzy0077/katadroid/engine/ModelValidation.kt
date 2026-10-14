package io.github.zhzy0077.katadroid.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** CPU reference fixtures share NHWC engine features, regardless of graph layout. */
internal object ModelValidation {
    fun floats(array: JSONArray, size: Int): FloatArray {
        require(array.length() == size) { "Invalid reference tensor size" }
        return FloatArray(size) { array.getDouble(it).toFloat().also { value ->
            require(value.isFinite()) { "Non-finite reference tensor" }
        } }
    }

    fun fixtures(context: Context, spec: KataGoModel): JSONObject =
        JSONObject(spec.openFixtures(context).bufferedReader().use { it.readText() }).also {
            require(it.getString("tfliteSha256") == spec.sha256) { "Model/reference identity mismatch" }
        }

    fun expected(case: JSONObject, contract: ModelContract): FloatArray {
        if (case.has("nativeRaw")) return floats(case.getJSONArray("nativeRaw"), ModelContract.RAW_SIZE)
        require(contract == ModelContract.ONNX_V8) { "Missing versioned reference outputs" }
        val outputs = case.getJSONObject("outputs")
        return contract.pack(contract.outputSizes.mapValues { (name, sizes) ->
            floats(outputs.getJSONArray(name), sizes.single())
        }, FloatArray(ModelContract.RAW_SIZE))
    }

    fun verify(runtime: LiteRtNetwork, fixtures: JSONObject, cancelled: () -> Unit = {}) {
        val cases = fixtures.getJSONArray("cases")
        require(cases.length() in 3..32) { "A model requires 3 to 32 reference positions" }
        val names = mutableSetOf<String>()
        for (i in 0 until cases.length()) {
            cancelled()
            val case = cases.getJSONObject(i)
            require(names.add(case.getString("name"))) { "Duplicate reference position" }
            val inputs = case.getJSONObject("inputs")
            val actual = runtime.evaluate(floats(inputs.getJSONArray("InputSpatial"), 22 * 361),
                floats(inputs.getJSONArray("InputGlobal"), 19))
            val expected = expected(case, runtime.modelSpec.contract)
            for (j in actual.indices) {
                val tolerance = 0.001f + 0.0001f * abs(expected[j])
                check(actual[j].isFinite() && abs(actual[j] - expected[j]) <= tolerance) {
                    "CPU reference failed: ${case.getString("name")}[$j]"
                }
            }
        }
    }
}
