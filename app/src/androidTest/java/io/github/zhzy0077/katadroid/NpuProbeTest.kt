package io.github.zhzy0077.katadroid

import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import com.google.ai.edge.litert.Accelerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Device-only integration test: missing NPU libraries or bad output must fail. */
class NpuProbeTest {
    @Test
    fun cpuAndNpuMatchReferenceConvolution() = runBlocking {
        assumeTrue("NPU integration requires the packaged native runtime", NpuProbe.hasRuntimeLibraries(InstrumentationRegistry.getInstrumentation().targetContext))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (accelerator in listOf(Accelerator.CPU, Accelerator.NPU)) {
            val result = NpuProbe.run(context, accelerator)
            assertTrue(result.maxError <= 0.01f)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\n${result.display()}\n")
            })
        }
    }
}
