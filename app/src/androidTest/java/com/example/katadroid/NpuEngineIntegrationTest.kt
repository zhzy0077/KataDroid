package com.example.katadroid

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.katadroid.engine.AnalysisPosition
import com.example.katadroid.engine.BoardSnapshot
import com.example.katadroid.engine.KataGoSession
import com.example.katadroid.ui.record.RecordUiState
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

/** No Activity: only the NPU inference, JNI worker and search lifecycle. */
class NpuEngineIntegrationTest {
    @Test
    fun officialNpuBackendSupportsLiveSearchCancellationAndRestart() = runBlocking<Unit> {
        assumeTrue("NPU integration requires the packaged native runtime", NpuProbe.hasRuntimeLibraries(InstrumentationRegistry.getInstrumentation().targetContext))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun report(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$message\n") })
        report("KATADROID_PID=${Process.myPid()}")
        EngineIntegrationTest().verifyOfficialPositions(Accelerator.NPU)
        report("NPU: three official feature/value/score/policy/ownership positions passed")

        KataGoSession.open(context, Accelerator.NPU).use { session ->
            assertEquals("NPU", session.backend)
            val position = RecordUiState().position
            session.setPosition(position)
            val start = SystemClock.elapsedRealtime()
            val first = checkNotNull(session.analyze(32, AtomicBoolean(false)))
            val result = checkNotNull(session.analyze(500, AtomicBoolean(false)))
            assertEquals(position, result.position)
            assertTrue(result.visits >= 500 && result.visits > first.visits)
            assertTrue(result.candidates.isNotEmpty())
            result.candidates.forEach { candidate ->
                assertTrue(BoardSnapshot.from(position).isLegal(candidate.move))
                BoardSnapshot.from(position.after(candidate.pv))
            }
            report("NPU: ${result.visits} actual visits in ${SystemClock.elapsedRealtime() - start} ms (single functional run); legal candidates and PVs passed")
            session.setPosition(AnalysisPosition(emptyList()))
            val stop = AtomicBoolean(false)
            val running = async(Dispatchers.IO) { session.analyze(50000, stop) }
            delay(100)
            stop.set(true)
            withTimeout(3000) { assertNull(running.await()) }
        }
        // A new QNN runtime must work after cancellation and native NN teardown.
        KataGoSession.open(context, Accelerator.NPU).use { session ->
            session.setPosition(AnalysisPosition(emptyList()))
            val result = checkNotNull(session.analyze(32, AtomicBoolean(false)))
            assertTrue(result.visits >= 32)
            assertEquals("NPU", result.backend)
        }
        report("NPU: cancellation, release and reopening passed")
        Log.i("KataDroidEngine", "PASS NPU official positions, live search, cancellation and restart")
    }
}
