package io.github.zhzy0077.katadroid

import io.github.zhzy0077.katadroid.engine.ModelContract
import org.junit.Assert.*
import org.junit.Test

class ModelContractTest {
    @Test fun nhwcTransportIsPreservedAndNchwTransportInterleavesExactly() {
        val spatial = FloatArray(361 * 22) { it.toFloat() }
        val destination = FloatArray(spatial.size)
        assertSame(spatial, ModelContract.ONNX_V8.spatial(spatial, destination))
        val converted = ModelContract.PYTORCH_V17.spatial(spatial, destination)
        for (point in 0 until 361) for (channel in 0 until 22)
            assertEquals(spatial[point * 22 + channel], converted[channel * 361 + point], 0f)
    }

    @Test fun versionEightKeepsAllHeadsAndDuplicatesPolicyForOptimism() {
        val contract = ModelContract.ONNX_V8
        val outputs = contract.outputSizes.mapValues { (name, sizes) ->
            FloatArray(sizes.single()) { it.toFloat() + name.length }
        }
        val raw = contract.pack(outputs, FloatArray(ModelContract.RAW_SIZE) { 999f })
        assertArrayEquals(raw.copyOfRange(0, 362), raw.copyOfRange(362, 724), 0f)
        assertArrayEquals(outputs.getValue("OutputValue"), raw.copyOfRange(724, 727), 0f)
        assertArrayEquals(outputs.getValue("OutputScoreValue"), raw.copyOfRange(727, 731), 0f)
        assertEquals(0f, raw[731], 0f)
        assertEquals(0f, raw[732], 0f)
        assertArrayEquals(outputs.getValue("OutputOwnership"), raw.copyOfRange(733, 1094), 0f)
    }

    @Test fun versionSeventeenSelectsOfficialPolicyAndShorttermChannels() {
        for (channels in listOf(6, 8)) {
            val outputs = mapOf("output_0" to FloatArray(channels * 362) { it.toFloat() },
                "output_1" to floatArrayOf(10f, 11f, 12f), "output_2" to FloatArray(10) { 20f + it },
                "output_3" to FloatArray(8) { 30f + it }, "output_4" to FloatArray(361) { 40f + it })
            val raw = ModelContract.PYTORCH_V17.pack(outputs, FloatArray(1094))
            assertArrayEquals(FloatArray(362) { it.toFloat() }, raw.copyOfRange(0, 362), 0f)
            assertArrayEquals(FloatArray(362) { (5 * 362 + it).toFloat() }, raw.copyOfRange(362, 724), 0f)
            assertArrayEquals(floatArrayOf(10f, 11f, 12f), raw.copyOfRange(724, 727), 0f)
            assertArrayEquals(floatArrayOf(20f, 21f, 22f, 23f, 30f, 31f), raw.copyOfRange(727, 733), 0f)
            assertArrayEquals(outputs.getValue("output_4"), raw.copyOfRange(733, 1094), 0f)
        }
    }

    @Test fun badSizesNonfiniteHeadsAndUnknownContractsAreRejected() {
        val contract = ModelContract.PYTORCH_V17
        val outputs = contract.outputSizes.mapValues { FloatArray(it.value.first()) }.toMutableMap()
        outputs["output_2"] = FloatArray(9)
        assertThrows(IllegalStateException::class.java) { contract.pack(outputs, FloatArray(1094)) }
        outputs["output_2"] = FloatArray(10) { Float.NaN }
        assertThrows(IllegalStateException::class.java) { contract.pack(outputs, FloatArray(1094)) }
        assertThrows(IllegalStateException::class.java) { ModelContract.fromId("katago-pytorch-v18") }
    }
}
