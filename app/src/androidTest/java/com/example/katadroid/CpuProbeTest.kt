package com.example.katadroid

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regular inference checks for Android Studio Virtual Devices, including x86_64. */
class CpuProbeTest {
    @Test
    fun cpuConvolutionMatchesReference() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = NpuProbe.run(instrumentation.targetContext, Accelerator.CPU)
        assertTrue(result.maxError <= 0.01f)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n${result.display()}\n") })
    }

    @Test
    fun cpuKataGoMatchesThreeOfficialPositions() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = KataGoProbe.run(instrumentation.targetContext, Accelerator.CPU)
        assertEquals(3, result.cases.size)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n${result.display()}\n") })
    }
}
