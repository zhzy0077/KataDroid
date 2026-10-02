package io.github.zhzy0077.katadroid.sgf

import io.github.zhzy0077.katadroid.engine.AnalysisPosition
import io.github.zhzy0077.katadroid.engine.GoRules
import io.github.zhzy0077.katadroid.engine.PASS
import io.github.zhzy0077.katadroid.engine.validKomi
import io.github.zhzy0077.katadroid.ui.record.BoardPoint
import io.github.zhzy0077.katadroid.ui.record.RecordNode

data class SgfGame(val nodes: List<RecordNode>, val rules: GoRules, val komi: Float,
                   val initialBlack: List<Int>, val initialWhite: List<Int>, val initialPlayer: Int) {
    companion object {
        fun from(root: SgfNode, fallbackRules: GoRules, fallbackKomi: Float): SgfGame {
            fun Map<String, List<String>>.one(key: String): String? = get(key)?.let {
                require(it.size == 1) { "$key must have exactly one value" }; it.single()
            }
            val props = root.properties
            require(props.one("GM") in listOf(null, "1")) { "This SGF does not contain a Go game" }
            require(props.one("SZ") in listOf(null, "19", "19:19")) { "Only 19x19 games are supported; this game uses ${props.one("SZ")}" }
            val ruleName = props.one("RU")?.lowercase()?.trim()
            val rules = when {
                ruleName == null || ruleName.isBlank() -> fallbackRules
                ruleName in setOf("chinese", "中国") -> GoRules.CHINESE
                ruleName in setOf("japanese", "日本") -> GoRules.JAPANESE
                ruleName in setOf("korean", "韩国") -> GoRules.KOREAN
                else -> error("Unsupported SGF rules: ${props.one("RU")}")
            }
            val komi = props.one("KM")?.let { it.toFloatOrNull() ?: error("Invalid komi: $it") }
                ?: if (ruleName == null) fallbackKomi else rules.defaultKomi
            require(validKomi(komi)) { "Komi must be between -400 and 400" }
            val clear = setupPoints(props["AE"].orEmpty()).toSet()
            val black = setupPoints(props["AB"].orEmpty()).filterNot { it in clear }
            val white = setupPoints(props["AW"].orEmpty()).filterNot { it in clear }
            require(black.none { it in white }) { "Black and white setup stones overlap" }
            val handicap = props.one("HA")?.let { it.toIntOrNull() ?: error("Invalid handicap") } ?: 0
            require(handicap in 0..361) { "Invalid handicap" }
            var firstMove: SgfNode? = root
            while (firstMove != null && "B" !in firstMove.properties && "W" !in firstMove.properties) firstMove = firstMove.children.firstOrNull()
            val player = props.one("PL")?.let(::playerColor) ?: if (handicap >= 2 || firstMove?.properties?.containsKey("W") == true) 2 else 1
            val all = mutableListOf<RecordNode>()
            val rootHasMove = "B" in props || "W" in props
            val rootProps = props - setOf("B", "W")
            all += RecordNode("sgf-0", null, 0, null, 0, "main", color = 0, properties = rootProps)
            data class Pending(val sgf: SgfNode, val parent: RecordNode, val lane: Int)
            val pending = ArrayDeque<Pending>()
            var lastLane = 0
            fun enqueueChildren(sgf: SgfNode, parent: RecordNode) {
                sgf.children.mapIndexed { i, child -> Pending(child, parent, if (i == 0) parent.lane else ++lastLane) }
                    .asReversed().forEach(pending::addLast)
            }
            if (rootHasMove) pending.addLast(Pending(SgfNode(props.filterKeys { it in setOf("B", "W") }, root.children), all.first(), 0))
            else enqueueChildren(root, all.first())
            while (pending.isNotEmpty()) {
                val (sgf, parent, lane) = pending.removeLast()
                val properties = sgf.properties
                require(properties.keys.none { it in setOf("AB", "AW", "AE", "SZ", "KM", "RU", "HA") }) {
                    "Setup stones, rules and komi are supported only at the root; the original game is preserved"
                }
                require(!("B" in properties && "W" in properties)) { "A node cannot contain both a black and a white move" }
                val color = when { "B" in properties -> 1; "W" in properties -> 2; else -> 0 }
                properties.one("PL")?.let(::playerColor)
                val point = if (color == 0) null else BoardPoint.fromIndex(sgfPoint(checkNotNull(properties.one(if (color == 1) "B" else "W")), true))
                val node = RecordNode("sgf-${all.size}", parent.id, parent.move + if (color == 0) 0 else 1,
                    point, lane, if (lane == 0) "main" else "variation-$lane", color, parent.depth + 1, properties)
                require(node.move <= 2000) { "At most 2000 moves per variation are supported" }
                all += node
                enqueueChildren(sgf, node)
            }
            // Validate root move combinations too, before publishing any document.
            require(!("B" in props && "W" in props)) { "A node cannot contain both a black and a white move" }
            require(!rootHasMove || (black.isEmpty() && white.isEmpty())) { "The root cannot contain both a move and setup stones" }
            return SgfGame(all, rules, komi, black, white, player)
        }
    }
}

fun playerColor(value: String): Int = when (value.uppercase()) {
    "B" -> 1; "W" -> 2; else -> throw IllegalArgumentException("Invalid player to move: $value")
}

fun positionForPath(path: List<RecordNode>, rules: GoRules, komi: Float,
                    black: List<Int>, white: List<Int>, initial: Int): AnalysisPosition {
    var next = initial
    val moves = mutableListOf<Int>()
    val colors = mutableListOf<Int>()
    path.forEach { node ->
        if (node.color != 0) { moves += node.point?.index ?: PASS; colors += node.color; next = 3 - node.color }
        node.properties["PL"]?.singleOrNull()?.let { next = playerColor(it) }
    }
    return AnalysisPosition(moves, komi, rules, black, white, initial, colors, next)
}
