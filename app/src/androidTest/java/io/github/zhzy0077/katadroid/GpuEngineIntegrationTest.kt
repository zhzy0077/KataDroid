package io.github.zhzy0077.katadroid

import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import io.github.zhzy0077.katadroid.engine.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class GpuEngineIntegrationTest {
    @Test fun reportBundledBackendInferenceTimings() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (model in KataGoModel.bundled) {
            for (backend in listOf(Accelerator.CPU, Accelerator.GPU, Accelerator.NPU)) {
                if (backend == Accelerator.NPU && !NpuProbe.hasRuntimeLibraries(context)) continue
                val result = KataGoProbe.run(context, backend, model)
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "\n${result.display()}\n")
                })
            }
        }
    }

    @Test fun fp32GpuChecksReferencesAndExecutesNativeCallbacksOnItsOwningThread() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(File(context.applicationInfo.nativeLibraryDir, "libLiteRtClGlAccelerator.so").isFile)
        for (model in KataGoModel.bundled) {
            assertEquals("GPU", KataGoProbe.run(context, Accelerator.GPU, model).backend)
            EngineIntegrationTest().verifyOfficialPositions(Accelerator.GPU, model)
        }
        repeat(2) {
            KataGoSession.open(context, Accelerator.GPU).use { session ->
                val position = AnalysisPosition(emptyList())
                session.setPosition(position)
                val analysis = checkNotNull(session.analyze(8, AtomicBoolean(false)))
                assertEquals("GPU", analysis.backend)
                assertTrue(analysis.candidates.isNotEmpty())
                assertNull(session.analyze(32, AtomicBoolean(true)))
            }
        }
    }
}
