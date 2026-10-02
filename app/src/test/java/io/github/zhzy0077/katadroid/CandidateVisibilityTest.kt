package io.github.zhzy0077.katadroid

import io.github.zhzy0077.katadroid.ui.AppPreferences
import org.junit.Assert.*
import org.junit.Test

class CandidateVisibilityTest {
    @Test fun automaticPlayersHideHintsWithoutDisablingAnalysis() {
        for (black in listOf(false, true)) for (white in listOf(false, true)) {
            val settings = AppPreferences(autoBlack = black, autoWhite = white)
            assertEquals(!black && !white, settings.showCandidateMoves)
            assertTrue(settings.engineEnabled)
            assertFalse(settings.copy(candidates = false).showCandidateMoves)
            assertEquals(!black && !white, settings.copy(engineEnabled = false).showCandidateMoves)
        }
    }
}
