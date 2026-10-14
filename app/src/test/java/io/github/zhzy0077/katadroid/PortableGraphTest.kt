package io.github.zhzy0077.katadroid

import io.github.zhzy0077.katadroid.engine.PortableGraph
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PortableGraphTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(name: String) = temporary.newFile().also { file ->
        javaClass.getResourceAsStream("/portable-graph/$name.tflite")!!.use { input ->
            file.outputStream().use(input::copyTo)
        }
    }

    @Test fun modernAndLegacyBuiltinEncodingsAreAccepted() {
        PortableGraph.check(fixture("portable"))
        PortableGraph.check(fixture("legacy"))
    }

    @Test fun customStatefulVariableExternalAndMultiGraphModelsAreRejected() {
        for (name in listOf("custom", "stateful", "variable", "external", "subgraphs"))
            assertThrows(IllegalArgumentException::class.java) { PortableGraph.check(fixture(name)) }
    }

    @Test fun optionalRealPortableGraphsPassEnvelopeValidation() {
        val path = System.getenv("KATADROID_OPTIONAL_GRAPH_DIR")
        org.junit.Assume.assumeTrue("Optional real graph directory", path != null)
        val files = java.io.File(path!!).listFiles()!!.filter { it.name.endsWith("-portable.tflite") }
        require(files.isNotEmpty())
        files.forEach(PortableGraph::check)
    }

    @Test fun malformedOffsetsAndHeadersAreRejectedBeforeNativeCompilation() {
        for (data in listOf(byteArrayOf(1), ByteArray(8),
                byteArrayOf(-1, -1, -1, 127, 'T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte()))) {
            val file = temporary.newFile().apply { writeBytes(data) }
            assertThrows(IllegalArgumentException::class.java) { PortableGraph.check(file) }
        }
    }
}
