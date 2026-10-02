package com.example.katadroid

import android.os.Bundle
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit NPU checks: no CPU fallback and no Activity. */
class NpuNetworkProbeTest {
    private fun report(message: String) = InstrumentationRegistry.getInstrumentation()
        .sendStatus(0, Bundle().apply { putString("stream", "\n$message\n") })

    @Test fun convolution() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("NPU plugins must be packaged", NpuProbe.hasRuntimeLibraries(context))
        report("KATADROID_PID=${Process.myPid()}")
        report(NpuProbe.run(context, Accelerator.NPU).display())
    }

    @Test fun kataGoB6() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("NPU plugins must be packaged", NpuProbe.hasRuntimeLibraries(context))
        report("KATADROID_PID=${Process.myPid()}")
        val result = KataGoProbe.run(context, Accelerator.NPU)
        assertEquals(3, result.cases.size)
        report(result.display())
        report("PASS NPU b6 numerical outputs")
    }
}
