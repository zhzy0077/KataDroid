package com.example.katadroid.ui.record

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

/**
 * Illustrative record from the design. Moves are replayed through KataGo's rules.
 * All evaluations and candidates now come from real search, not design numbers.
 */
object DemoRecord {
    const val INITIAL_NODE = "main-68"
    val nodes: List<RecordNode>
    val byId: Map<String, RecordNode>
    val children: Map<String, List<RecordNode>>
    val mainLine: List<RecordNode>

    init {
        val all = mutableListOf(RecordNode("main-0", null, 0, null, 0, "main"))
        val black = points("D16,D14,E14,F15,F16,G16,D10,D7,D6,C5,D4,E4,F3,G3,G4,H5,J5,K4,L3,Q16,R16,R15,R13,Q12,P12,O13,O14,N15,N16,L16,Q4,R5,R6,P4")
        // The design marks Q14 as the last white move.
        val white = points("C16,C15,D15,E15,E16,E17,F17,D3,C3,C4,E3,E2,F2,G2,H3,J3,J4,K3,L4,Q15,P16,P15,O16,O15,P14,Q13,R12,R11,S12,Q3,P3,O4,N4,Q14")
        val history = black.zip(white).flatMap { listOf(it.first, it.second) } +
            points("R14,S14,S13,S15,Q10,R10,P10,P11,O10,O11,N10,N11")
        history.forEachIndexed { index, point ->
            val move = index + 1
            all += RecordNode("main-$move", "main-${move - 1}", move, point, 0, "main")
        }
        mainLine = all.toList()

        fun branch(parentId: String, id: String, lane: Int, coordinates: String, name: String): List<RecordNode> {
            var parent = all.first { it.id == parentId }
            val result = mutableListOf<RecordNode>()
            points(coordinates).forEachIndexed { index, point ->
                val node = RecordNode("$id-${index + 1}", parent.id, parent.move + 1, point, lane, name)
                all += node
                result += node
                parent = node
            }
            return result
        }

        branch("main-12", "opening", -1, "Q10,R10,P10,Q9,P9", "opening")
        branch("main-28", "lower", 1, "C6,C7,B6,B7,B5,A5", "lower")
        branch("main-45", "middle", -2, "K10,K11,L10,L11,M10", "middle")
        branch("main-61", "upper", -2, "O17,P17,Q17,R17,Q18,R18,S18", "upper")
        branch("main-63", "right", -1, "R14,S14,S13,S15,T14,T15,T13,T12,Q10", "right")
        branch("right-3", "right-child", -3, "Q10,R10,P10,P11,O10", "right-child")
        branch("main-65", "left", 1, "C6,C7,B6,B7,C8,D8,E8,F8,G8", "left")
        branch("main-66", "center", 3, "K10,K11,L10,L11,M10,M11,N10,N11", "middle")
        branch("left-3", "left-child", 5, "B5,A5,A6,A7,A8,B8,C8", "left-child")
        branch("main-68", "b", 2, "Q10,R10,P10,P11,O10,O11,N10,N11", "b")
        branch("main-68", "c", 4, "C6,C7,B6,B7,B5,A5", "c")
        branch("b-3", "b-child", 6, "O10,O11,N10,N11,M10", "b-child")
        nodes = all.toList()
        byId = nodes.associateBy { it.id }
        children = nodes.filter { it.parentId != null }.groupBy { it.parentId!! }
    }

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

    private fun points(value: String) = value.split(',').map(BoardPoint::parse)

}
