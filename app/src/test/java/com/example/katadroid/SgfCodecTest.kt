package com.example.katadroid

import com.example.katadroid.engine.GoRules
import com.example.katadroid.engine.PASS
import com.example.katadroid.sgf.SgfCodec
import com.example.katadroid.sgf.SgfGame
import com.example.katadroid.sgf.positionForPath
import com.example.katadroid.sgf.sgfPoint
import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Test

class SgfCodecTest {
    @Test
    fun collectionVariationsAndEscapedAnnotationsRoundTrip() {
        val source = """(;FF[4]GM[1]SZ[19]CA[UTF-8]PB[黑棋]PW[白棋]C[右方 \] 与路径 c:\\go]
            ;B[pd]C[第一手](;W[dd]LB[dd:A][pd:B];B[])(;W[qp]TR[qp]))(;SZ[19];W[tt])"""
        val roots = SgfCodec.parse(source)
        assertEquals(2, roots.size)
        val root = roots.first()
        assertEquals("右方 ] 与路径 c:\\go", root.properties.getValue("C").single())
        assertEquals(2, root.children.single().children.size)
        assertEquals(root, SgfCodec.decode(SgfCodec.encode(root).toByteArray()).single())
        assertEquals(PASS, sgfPoint(roots[1].children.single().properties.getValue("W").single(), true))
        val line = SgfCodec.parse("(;C[soft\\\r\nline\r\nnext])").single()
        assertEquals("softline\nnext", line.properties.getValue("C").single())
    }

    @Test
    fun charsetComesFromRootPropertyAndSupportsUtf8BomAndChineseLegacyFiles() {
        val comment = "(;C[说明 CA[not-a-charset\\] 保留];B[aa])"
        assertTrue(SgfCodec.decode(comment.toByteArray()).single().properties.getValue("C").single().contains("说明"))
        val legacy = "(;FF[4]CA[GB18030]GN[中文棋谱]C[贴目与让子];B[pd])"
        assertEquals("中文棋谱", SgfCodec.decode(legacy.toByteArray(Charset.forName("GB18030"))).single().properties["GN"]?.single())
        val bom = "\uFEFF(;CA[UTF-8]C[注释])".toByteArray()
        assertEquals("注释", SgfCodec.decode(bom).single().properties["C"]?.single())
    }

    @Test
    fun handicapAnnotationsExplicitColorsAndPlayerChangesAreRetained() {
        val root = SgfCodec.parse("(;SZ[19]RU[Japanese]KM[-2.25]HA[2]AB[dd][pp]AW[aa:ba]AE[ba]PL[W];W[jj];C[注释]PL[B];B[dq](;W[])(;W[qd]))").single()
        val game = SgfGame.from(root, GoRules.CHINESE, 7.5f)
        assertEquals(GoRules.JAPANESE, game.rules)
        assertEquals(-2.25f, game.komi, 0f)
        assertEquals(listOf(sgfPoint("dd"), sgfPoint("pp")), game.initialBlack)
        assertEquals(listOf(sgfPoint("aa")), game.initialWhite)
        assertEquals(2, game.initialPlayer)
        assertEquals(listOf(0, 2, 0, 1, 2, 2), game.nodes.map { it.color })
        val main = positionForPath(game.nodes.take(5), game.rules, game.komi, game.initialBlack, game.initialWhite, game.initialPlayer)
        assertEquals(listOf(sgfPoint("jj"), sgfPoint("dq"), PASS), main.moves)
        assertEquals(listOf(2, 1, 2), main.moveColors)
        assertEquals(1, main.nextPlayer)
        assertEquals(3, game.nodes[3].depth)
        assertEquals(2, game.nodes[3].move)
    }

    @Test
    fun malformedAndUnsupportedDocumentsAreRejectedInsteadOfReinterpreted() {
        listOf("", "(;C[unfinished)", "(;B[aa]B[bb])", "(;B)", "(;B[aa])trailing").forEach { source ->
            assertThrows("$source should fail", IllegalArgumentException::class.java) { SgfCodec.parse(source) }
        }
        listOf("(;SZ[9])", "(;RU[Chinese-OGS])", "(;B[tt]W[])", "(;KM[NaN])", "(;AB[aa]AW[aa])", "(;SZ[19];AB[bb])").forEach { source ->
            assertThrows(RuntimeException::class.java) { SgfGame.from(SgfCodec.parse(source).single(), GoRules.CHINESE, 7.5f) }
        }
    }

    @Test
    fun longSequencesDoNotRequireRecursiveWriterOrDiscardNodes() {
        val source = "(;SZ[19]" + (1..5000).joinToString("") { ";C[node $it]" } + ")"
        val encoded = SgfCodec.encode(SgfCodec.parse(source).single())
        var node = SgfCodec.parse(encoded).single()
        var count = 0
        while (node.children.isNotEmpty()) { node = node.children.single(); count++ }
        assertEquals(5000, count)
        assertEquals("node 5000", node.properties["C"]?.single())
    }
}
