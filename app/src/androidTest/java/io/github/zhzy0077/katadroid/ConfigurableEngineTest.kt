package io.github.zhzy0077.katadroid

import androidx.test.platform.app.InstrumentationRegistry
import io.github.zhzy0077.katadroid.engine.*
import com.google.ai.edge.litert.Accelerator
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class ConfigurableEngineTest {
    @Test
    fun selectedRulesKomiAndExactVisitLimitsReachTheOfficialEngine() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val empty = AnalysisPosition(emptyList(), komi = 2.25f)
        val chinese = NativeKataGo.featuresPosition(empty.json().toString())
        val japanese = NativeKataGo.featuresPosition(empty.copy(rules = GoRules.JAPANESE).json().toString())
        val korean = NativeKataGo.featuresPosition(empty.copy(rules = GoRules.KOREAN).json().toString())
        assertFalse(chinese.contentEquals(japanese))
        assertArrayEquals(japanese, korean, 0f) // KataGo's standard Korean/Japanese rules are identical.
        KataGoSession.open(context, Accelerator.CPU).use { session ->
            GoRules.entries.forEach { rules ->
                val position = empty.copy(rules = rules)
                session.setPosition(position)
                for (cap in listOf(1, 64, 333)) {
                    val result = checkNotNull(session.analyze(cap, AtomicBoolean(false)))
                    assertEquals(cap, result.visits)
                    assertTrue(BoardSnapshot.from(position).isLegal(checkNotNull(result.bestMove)))
                    result.candidates.forEach { BoardSnapshot.from(position.after(it.pv)) }
                }
                for (komi in listOf(-400f, -2.25f, 0f, 6.75f, 400f)) {
                    val finished = AnalysisPosition(listOf(PASS, PASS), komi, rules)
                    assertTrue(BoardSnapshot.from(finished).finished)
                    session.setPosition(finished)
                    val score = checkNotNull(session.analyze(1, AtomicBoolean(false)))
                    assertTrue(score.gameFinished)
                    assertEquals(0, score.visits)
                    assertEquals(-komi, score.blackLead, 0f)
                    assertNull(score.bestMove)
                }
            }
            // Reducing the budget on the same position resets the old search tree.
            session.setPosition(empty)
            assertEquals(1000, session.analyze(1000, AtomicBoolean(false))?.visits)
            session.setPosition(empty)
            assertEquals(1, session.analyze(1, AtomicBoolean(false))?.visits)
        }
    }

    @Test
    fun cacheIdentityIncludesRulesSetupColorsAndPlayerToMove() {
        val empty = AnalysisPosition(emptyList())
        val positions = listOf(empty, empty.copy(rules = GoRules.KOREAN), empty.copy(rules = GoRules.JAPANESE),
            empty.copy(komi = -2.25f), empty.copy(initialBlack = listOf(60)), empty.copy(initialWhite = listOf(60)),
            empty.copy(initialPlayer = 2, nextPlayer = 2), empty.copy(nextPlayer = 2),
            AnalysisPosition(listOf(60)), AnalysisPosition(listOf(60), moveColors = listOf(2), nextPlayer = 1))
        assertEquals(positions.size, positions.map { it.key }.toSet().size)
        positions.forEach { assertEquals(it, AnalysisPosition.parse(it.json())) }
    }
}
