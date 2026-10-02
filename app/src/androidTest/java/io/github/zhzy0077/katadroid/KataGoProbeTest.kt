package io.github.zhzy0077.katadroid

import android.os.Bundle
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class KataGoProbeTest {
    @Test
    fun officialNetworkMatchesThreeEnginePositions() = runBlocking {
        assumeTrue("NPU integration requires the packaged native runtime", NpuProbe.hasRuntimeLibraries(InstrumentationRegistry.getInstrumentation().targetContext))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nKATADROID_PID=${Process.myPid()}\n") })
        for (accelerator in listOf(Accelerator.CPU, Accelerator.NPU)) {
            val result = KataGoProbe.run(instrumentation.targetContext, accelerator)
            assertEquals(3, result.cases.size)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n${result.display()}\n") })
        }
    }
}
