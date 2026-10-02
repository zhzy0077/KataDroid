package io.github.zhzy0077.katadroid

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zhzy0077.katadroid.engine.AnalysisPosition
import io.github.zhzy0077.katadroid.engine.KataGoSession
import com.google.ai.edge.litert.Accelerator
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Dedicated NPU integration, without launching an Activity. */
class NpuTimedAnalysisTest {
    @Test fun timedSnapshotsKeepTheTreeAndDoNotLimitSubsequentVisitBudgetSearches() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("NPU integration requires the packaged native runtime", NpuProbe.hasRuntimeLibraries(context))
        KataGoSession.open(context, Accelerator.NPU).use { session ->
            assertEquals("NPU", session.backend)
            val position = AnalysisPosition(emptyList())
            session.setPosition(position)
            var previous = 0
            repeat(6) {
                val start = SystemClock.elapsedRealtime()
                val result = checkNotNull(session.analyzeForTime(250, AtomicBoolean(false)))
                val elapsed = SystemClock.elapsedRealtime() - start
                assertEquals(position, result.position)
                assertTrue(result.visits > previous)
                assertTrue("Timed search returned too early: $elapsed ms", elapsed >= 200)
                assertTrue("Timed search exceeded integration timeout: $elapsed ms", elapsed < 3000)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "\nTimed NPU snapshot: ${result.visits} visits, +${result.visits - previous}, $elapsed ms\n")
                })
                previous = result.visits
            }
            assertNull(session.analyzeForTime(250, AtomicBoolean(true)))
            val budget = previous + 2000
            val bounded = checkNotNull(session.analyze(budget, AtomicBoolean(false)))
            assertTrue("Time cap leaked into visit-budget search", bounded.visits >= budget)
            session.setPosition(position)
            val capped = checkNotNull(session.analyzeForTime(250, AtomicBoolean(false), 32))
            assertEquals("Timed refresh must retain the automatic move budget", 32, capped.visits)
        }
    }
}
