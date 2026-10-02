package io.github.zhzy0077.katadroid

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zhzy0077.katadroid.engine.*
import com.google.ai.edge.litert.Accelerator
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** NPU only, without launching an Activity or changing the display. */
class NpuModelBenchmarkTest {
    @Test fun b10AccuracyBothModelBenchmarksAndCancellationPassOnHtp() = runBlocking<Unit> {
        assumeTrue("NPU integration requires the packaged native runtime", NpuProbe.hasRuntimeLibraries(InstrumentationRegistry.getInstrumentation().targetContext))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$message\n") })
        report("KATADROID_PID=${Process.myPid()}")
        val raw = KataGoProbe.run(context, Accelerator.NPU, KataGoModel.B10)
        report(raw.display())
        EngineIntegrationTest().verifyOfficialPositions(Accelerator.NPU, KataGoModel.B10)
        report("NPU_SESSION_CLOSED reference")
        for (model in KataGoModel.entries) {
            Log.i("KataDroidEngine", "NPU benchmark opening ${model.id}")
            val before = SystemClock.elapsedRealtimeNanos()
            KataGoSession.open(context, Accelerator.NPU, model).use { session ->
                Log.i("KataDroidEngine", "NPU benchmark opened ${model.id}")
                val init = SystemClock.elapsedRealtimeNanos() - before
                val result = checkNotNull(SearchBenchmark.run(session, EngineConfig(model, EngineBackend.NPU), 500, init, AtomicBoolean(false)) { phase, visits, label ->
                    Log.i("KataDroidEngine", "NPU benchmark ${model.id} $phase $visits $label")
                })
                assertEquals(1500, result.visits)
                assertTrue(result.visitsPerSecond.isFinite() && result.visitsPerSecond > 0)
                report("NPU_BENCHMARK ${result.json()}")
                if (model == KataGoModel.B10) {
                    session.resetForBenchmark(AnalysisPosition(emptyList()))
                    val cancel = AtomicBoolean(false)
                    val running = async(Dispatchers.IO) { session.analyze(50000, cancel) }
                    delay(100)
                    cancel.set(true)
                    withTimeout(3000) { assertNull(running.await()) }
                }
            }
            report("NPU_SESSION_CLOSED benchmark ${model.id}")
        }
        // Switch back after cancelling the bigger model, including complete teardown.
        KataGoSession.open(context, Accelerator.NPU, KataGoModel.B6).use { session ->
            session.setPosition(AnalysisPosition(emptyList()))
            assertEquals(32, session.analyze(32, AtomicBoolean(false))?.visits)
        }
        report("NPU_SESSION_CLOSED restart")
        report("PASS NPU b10 accuracy, both model searches, cancellation and model switch")
        Log.i("KataDroidEngine", "PASS NPU b10 accuracy, both model searches, cancellation and model switch")
    }
}
