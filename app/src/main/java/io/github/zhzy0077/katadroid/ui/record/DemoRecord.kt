package io.github.zhzy0077.katadroid.ui.record

import io.github.zhzy0077.katadroid.engine.GoRules
import io.github.zhzy0077.katadroid.sgf.SgfCodec
import io.github.zhzy0077.katadroid.sgf.SgfGame

data class BoardPoint(val column: Int, val row: Int) {
    val label: String get() = "${COLUMNS[column]}${19 - row}"
    val index: Int get() = row * 19 + column

    companion object {
        const val COLUMNS = "ABCDEFGHJKLMNOPQRST"
        fun fromIndex(index: Int): BoardPoint? = if (index in 0..360) BoardPoint(index % 19, index / 19) else null
        fun parse(coordinate: String) = BoardPoint(
            COLUMNS.indexOf(coordinate.first()), 19 - coordinate.drop(1).toInt(),
        ).also { require(it.column in 0..18 && it.row in 0..18) }
    }
}

data class RecordNode(
    val id: String,
    val parentId: String?,
    val move: Int,
    val point: BoardPoint?,
    val lane: Int,
    val branch: String,
    val color: Int = if (move == 0) 0 else if (move % 2 == 1) 1 else 2,
    val depth: Int = move,
    val properties: Map<String, List<String>> = emptyMap(),
) {
    val black: Boolean get() = color == 1
}

data class Candidate(val label: String, val moves: List<Int>)

/** First 50 main-line moves of AlphaGo–Lee Sedol, game 4 (2016-03-13).
 * Original commentary and variations are omitted; evaluations come from real search.
 */
object DemoRecord {
    const val INITIAL_NODE = "main-50"
    private const val SGF = """
        (;GM[1]FF[4]CA[UTF-8]SZ[19]RU[Chinese]KM[7.5]PB[AlphaGo]PW[Lee Sedol]WR[9p]DT[2016-03-13]RO[4]
        ;B[pd];W[dp];B[cd];W[qp];B[op];W[oq];B[nq];W[pq];B[cn];W[fq]
        ;B[mp];W[po];B[iq];W[ec];B[hd];W[cg];B[ed];W[cj];B[dc];W[bp]
        ;B[nc];W[qi];B[ep];W[eo];B[dk];W[fp];B[ck];W[dj];B[ej];W[ei]
        ;B[fi];W[eh];B[fh];W[bj];B[fk];W[fg];B[gg];W[ff];B[gf];W[mc]
        ;B[md];W[lc];B[nb];W[id];B[hc];W[jg];B[pj];W[pi];B[oj];W[oi]
        )
    """
    val nodes = SgfGame.from(
        SgfCodec.parse(SGF).single(),
        GoRules.CHINESE, 7.5f,
    ).nodes.mapIndexed { index, node ->
        node.copy(id = "main-$index", parentId = if (index == 0) null else "main-${index - 1}")
    }
    val byId = nodes.associateBy { it.id }
    val children = nodes.filter { it.parentId != null }.groupBy { it.parentId!! }
    val mainLine = nodes

    fun path(id: String): List<RecordNode> = buildList {
        var next: RecordNode? = byId.getValue(id)
        while (next != null) {
            add(next)
            next = next.parentId?.let(byId::getValue)
        }
    }.asReversed()

    fun leafFrom(id: String): String {
        var current = id
        while (true) current = children[current]?.firstOrNull()?.id ?: return current
    }
}
