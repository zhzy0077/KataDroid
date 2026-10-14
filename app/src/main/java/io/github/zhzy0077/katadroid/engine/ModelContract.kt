package io.github.zhzy0077.katadroid.engine

/** Versioned portable graph ABI. Search always sends NHWC features and receives RAW heads. */
enum class ModelContract(val id: String, val modelVersion: Int, val spatialInput: String,
                         val globalInput: String, val nchw: Boolean,
                         val outputSizes: Map<String, Set<Int>>) {
    ONNX_V8("katago-onnx-v8", 8, "InputSpatial", "InputGlobal", false, linkedMapOf(
        "OutputPolicy" to setOf(361), "OutputPolicyPass" to setOf(1),
        "OutputValue" to setOf(3), "OutputScoreValue" to setOf(4), "OutputOwnership" to setOf(361))),
    PYTORCH_V17("katago-pytorch-v17", 17, "args_0", "args_1", true, linkedMapOf(
        "output_0" to setOf(6 * 362, 8 * 362), "output_1" to setOf(3),
        "output_2" to setOf(10), "output_3" to setOf(8), "output_4" to setOf(361)));

    fun shapes(): Map<String, Set<List<Int>>> = when (this) {
        ONNX_V8 -> mapOf(spatialInput to setOf(listOf(1, 19, 19, 22)),
            globalInput to setOf(listOf(1, 1, 1, 19)),
            "OutputPolicy" to setOf(listOf(1, 19, 19, 1)), "OutputPolicyPass" to setOf(listOf(1, 1, 1, 1)),
            "OutputValue" to setOf(listOf(1, 1, 1, 3)), "OutputScoreValue" to setOf(listOf(1, 1, 1, 4)),
            "OutputOwnership" to setOf(listOf(1, 19, 19, 1)))
        PYTORCH_V17 -> mapOf(spatialInput to setOf(listOf(1, 22, 19, 19)),
            globalInput to setOf(listOf(1, 19)), "output_0" to setOf(listOf(1, 6, 362), listOf(1, 8, 362)),
            "output_1" to setOf(listOf(1, 3)), "output_2" to setOf(listOf(1, 10)),
            "output_3" to setOf(listOf(1, 8)), "output_4" to setOf(listOf(1, 1, 19, 19)))
    }

    fun spatial(nhwc: FloatArray, destination: FloatArray): FloatArray {
        require(nhwc.size == 22 * 361 && destination.size == nhwc.size)
        if (!nchw) return nhwc
        for (point in 0 until 361) for (channel in 0 until 22)
            destination[channel * 361 + point] = nhwc[point * 22 + channel]
        return destination
    }

    fun pack(outputs: Map<String, FloatArray>, destination: FloatArray): FloatArray {
        require(destination.size == RAW_SIZE)
        for ((name, sizes) in outputSizes) {
            val value = outputs.getValue(name)
            check(value.size in sizes && value.all { it.isFinite() }) { "$name has invalid output shape or values" }
        }
        destination.fill(0f)
        when (this) {
            ONNX_V8 -> {
                val policy = outputs.getValue("OutputPolicy")
                policy.copyInto(destination, POLICY)
                destination[POLICY + 361] = outputs.getValue("OutputPolicyPass")[0]
                destination.copyInto(destination, OPTIMISTIC_POLICY, POLICY, POLICY + 362)
                outputs.getValue("OutputValue").copyInto(destination, VALUE)
                outputs.getValue("OutputScoreValue").copyInto(destination, SCORE)
                outputs.getValue("OutputOwnership").copyInto(destination, OWNERSHIP)
            }
            PYTORCH_V17 -> {
                // Official export_model_pytorch.py selects training policy channels 0
                // and 5; score channels are misc[0:4] plus moremisc[0:2]. Other
                // training heads are not search outputs. NNEvaluator performs all
                // nonlinear scaling, perspective conversion and legal masking.
                val policy = outputs.getValue("output_0")
                policy.copyInto(destination, POLICY, 0, 362)
                policy.copyInto(destination, OPTIMISTIC_POLICY, 5 * 362, 6 * 362)
                outputs.getValue("output_1").copyInto(destination, VALUE)
                outputs.getValue("output_2").copyInto(destination, SCORE, 0, 4)
                outputs.getValue("output_3").copyInto(destination, SCORE + 4, 0, 2)
                outputs.getValue("output_4").copyInto(destination, OWNERSHIP)
            }
        }
        return destination
    }

    companion object {
        const val POLICY = 0
        const val OPTIMISTIC_POLICY = 362
        const val VALUE = 724
        const val SCORE = 727
        const val OWNERSHIP = 733
        const val RAW_SIZE = 1094
        fun fromId(id: String): ModelContract = entries.singleOrNull { it.id == id }
            ?: error("Unsupported model contract: $id")
    }
}
