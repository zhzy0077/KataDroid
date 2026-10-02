package io.github.zhzy0077.katadroid

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import io.github.zhzy0077.katadroid.engine.AnalysisCandidate
import io.github.zhzy0077.katadroid.ui.record.candidateColor
import io.github.zhzy0077.katadroid.ui.record.candidateTextColor
import org.junit.Assert.*
import org.junit.Test

class CandidateColorTest {
    @Test
    fun nearBestOpeningMovesAreAllGreenAndClearlyWorseMovesAreRed() {
        val green = Color(0xFF28784E)
        // Small differences must not force the third-best move to yellow or red.
        for (rate in listOf(51f, 50.5f, 49.5f)) assertEquals(green, candidateColor(rate, 51f))
        assertEquals(Color(0xFFE1C265), candidateColor(46f, 51f))
        assertEquals(Color(0xFFD87959), candidateColor(35f, 51f))
    }

    @Test
    fun shiftingAllWinRatesPreservesColorsIncludingInLosingPositions() {
        for (loss in 0..10) {
            assertEquals(candidateColor(50f - loss, 50f), candidateColor(90f - loss, 90f))
            assertEquals(candidateColor(50f - loss, 50f), candidateColor(10f - loss, 10f))
        }
        val greenToRed = (2..10).map { candidateColor(50f - it, 50f).let { c -> c.green / c.red } }
        assertTrue(greenToRed.zipWithNext().all { (before, after) -> after < before })
    }

    @Test
    fun whiteUsesItsOwnBestWinRateAndRecommendationLettersDoNotAffectColor() {
        val bestForWhite = AnalysisCandidate("B", 60, 100, 48f, -3f, listOf(60))
        val worseForWhite = bestForWhite.copy(label = "A", blackWinRate = 65f)
        val whiteBest = listOf(bestForWhite, worseForWhite).maxOf { it.winRateFor(2) }
        assertEquals(52f, whiteBest, 0f)
        assertEquals(Color(0xFF28784E), candidateColor(bestForWhite.winRateFor(2), whiteBest))
        assertEquals(Color(0xFFD87959), candidateColor(worseForWhite.winRateFor(2), whiteBest))
        assertEquals(candidateColor(52f, 52f), candidateColor(bestForWhite.copy(label = "C").winRateFor(2), whiteBest))
    }

    @Test
    fun labelsRemainReadableAcrossTheWholeGradient() {
        for (step in 0..1000) {
            val loss = step / 10f
            val fill = candidateColor(100f - loss, 100f)
            val text = candidateTextColor(fill)
            val contrast = (maxOf(fill.luminance(), text.luminance()) + .05f) /
                (minOf(fill.luminance(), text.luminance()) + .05f)
            assertTrue("Loss $loss points, contrast $contrast", contrast >= 4.5f)
        }
    }
}
