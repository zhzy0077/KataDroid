package com.example.katadroid

import com.example.katadroid.engine.BoardSnapshot
import com.example.katadroid.engine.GoRules
import com.example.katadroid.engine.PASS
import com.example.katadroid.sgf.SgfCodec
import com.example.katadroid.sgf.SgfGame
import com.example.katadroid.sgf.sgfPoint
import com.example.katadroid.ui.record.RecordUiState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SgfRecordTest {
    @Test
    fun importedSetupAndBranchesSurviveEditingExportAndDiskRestore() {
        val source = "(;FF[4]SZ[19]RU[Korean]KM[-2.25]HA[2]AB[dd][pp]PL[W]PB[甲]PW[乙]GN[让子棋]C[根注释];W[jj];C[中途注释];B[dq](;W[qd]C[主线])(;W[]C[变化]))"
        val game = SgfGame.from(SgfCodec.parse(source).single(), GoRules.CHINESE, 7.5f)
        RecordUiState.validate(game)
        val record = RecordUiState().apply { load(game, "source.sgf") }
        assertFalse(record.autoPlayArmed)
        assertEquals("让子棋", record.recordName)
        assertEquals(listOf(2, 1, 2), record.position.moveColors)
        val board = BoardSnapshot.from(record.position)
        assertEquals(1, board.color(sgfPoint("dd")))
        assertEquals(1, board.color(sgfPoint("pp")))
        assertEquals(2, board.color(sgfPoint("jj")))
        assertTrue(board.blackToMove)
        record.previous()
        assertTrue(record.play(sgfPoint("qq"))) // Add a third white variation.
        val exported = record.exportSgf()
        val root = SgfCodec.decode(exported.toByteArray()).single()
        assertEquals("甲", root.properties["PB"]?.single())
        assertEquals("根注释", root.properties["C"]?.single())
        assertEquals("UTF-8", root.properties["CA"]?.single())
        val importedAgain = SgfGame.from(root, GoRules.CHINESE, 7.5f)
        RecordUiState.validate(importedAgain)
        assertEquals(record.nodes.size, importedAgain.nodes.size)
        assertEquals(3, importedAgain.nodes.count { it.move == 3 })
        val restored = RecordUiState.restore(record.serialize())
        assertEquals(exported, restored.exportSgf())
        assertEquals(record.position, restored.position)
        assertFalse(restored.autoPlayArmed)
        assertEquals(GoRules.KOREAN, restored.rules)
        assertEquals(-2.25f, restored.komi, 0f)
    }

    @Test
    fun illegalBranchesAndCorruptParentCyclesCannotReplaceARecord() {
        val record = RecordUiState()
        val before = record.exportSgf()
        val game = SgfGame.from(SgfCodec.parse("(;SZ[19];B[dd](;W[pp])(;W[dd]))").single(), GoRules.CHINESE, 7.5f)
        assertThrows(IllegalStateException::class.java) { RecordUiState.validate(game); record.load(game, "invalid.sgf") }
        assertEquals(before, record.exportSgf())
        val corrupt = JSONObject(record.serialize())
        corrupt.getJSONArray("nodes").getJSONObject(1).put("parent", "main-1")
        assertThrows(RuntimeException::class.java) { RecordUiState.restore(corrupt.toString()) }
        val duplicate = JSONObject(record.serialize())
        duplicate.getJSONArray("nodes").getJSONObject(1).put("id", "main-0")
        assertThrows(RuntimeException::class.java) { RecordUiState.restore(duplicate.toString()) }
        assertEquals(before, record.exportSgf())
        // The shipped sample uses negative lanes and must also persist intact.
        assertEquals(record.position, RecordUiState.restore(record.serialize()).position)
    }

    @Test
    fun whiteFirstAndNonAlternatingSgfMovesUseDeclaredColorsAndPasses() {
        val game = SgfGame.from(SgfCodec.parse("(;SZ[19]PL[W];W[dd];W[pp];PL[W]C[白继续];W[])").single(), GoRules.CHINESE, 0f)
        RecordUiState.validate(game)
        val record = RecordUiState().apply { load(game, "white-first.sgf") }
        assertEquals(listOf(2, 2, 2), record.position.moveColors)
        assertEquals(PASS, record.position.moves.last())
        assertTrue(BoardSnapshot.from(record.position).blackToMove)
        record.nodes.forEach { node ->
            val board = BoardSnapshot.from(record.positionFor(node.id))
            assertTrue(board.isLegal(sgfPoint("qq")))
            if (node.color == 0) assertFalse(board.blackToMove)
        }
        assertTrue(record.exportSgf().contains(";W[dd];W[pp]"))
    }
}
