package io.github.zhzy0077.katadroid

import io.github.zhzy0077.katadroid.ui.record.BoardPoint
import io.github.zhzy0077.katadroid.ui.record.DemoRecord
import org.junit.Assert.*
import org.junit.Test

class DemoRecordTest {
    @Test fun exampleContainsOnlyTheFirstFiftyMovesOfMatchGameFour() {
        val line = DemoRecord.path(DemoRecord.INITIAL_NODE)
        assertEquals(51, line.size)
        assertEquals(line, DemoRecord.nodes)
        assertEquals(50, line.last().move)
        assertEquals(BoardPoint.parse("Q16"), line[1].point)
        assertEquals(BoardPoint.parse("D4"), line[2].point)
        assertEquals(BoardPoint.parse("P11"), line.last().point)
        assertEquals(2, line.last().color)
        assertEquals(DemoRecord.INITIAL_NODE, DemoRecord.leafFrom(line.first().id))
        val metadata = line.first().properties
        assertEquals(listOf("AlphaGo"), metadata["PB"])
        assertEquals(listOf("Lee Sedol"), metadata["PW"])
        assertEquals(listOf("2016-03-13"), metadata["DT"])
        assertEquals(listOf("4"), metadata["RO"])
        assertFalse(metadata.containsKey("RE")) // An opening excerpt is not a finished game.
        assertTrue(line.all { "C" !in it.properties }) // No copied commentary or static evaluations.
    }
}
