package io.github.zhzy0077.katadroid.ui.record

import io.github.zhzy0077.katadroid.R
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.zhzy0077.katadroid.engine.AnalysisPosition
import io.github.zhzy0077.katadroid.engine.BoardSnapshot
import io.github.zhzy0077.katadroid.engine.GoRules
import io.github.zhzy0077.katadroid.engine.PASS
import io.github.zhzy0077.katadroid.engine.validKomi
import io.github.zhzy0077.katadroid.sgf.SgfCodec
import io.github.zhzy0077.katadroid.sgf.SgfGame
import io.github.zhzy0077.katadroid.sgf.SgfNode
import io.github.zhzy0077.katadroid.sgf.pointSgf
import io.github.zhzy0077.katadroid.sgf.positionForPath
import org.json.JSONArray
import org.json.JSONObject

@Stable
class RecordUiState(initialRules: GoRules = GoRules.CHINESE, initialKomi: Float = 7.5f) {
    var isDemo by mutableStateOf(true)
        private set
    var recordName by mutableStateOf("")
        private set
    var rules by mutableStateOf(initialRules)
        private set
    var komi by mutableStateOf(initialKomi)
        private set
    private var initialBlack = emptyList<Int>()
    private var initialWhite = emptyList<Int>()
    private var initialPlayer = 1
    private val recordNodes = mutableStateListOf<RecordNode>().apply { addAll(DemoRecord.nodes) }
    private val positions = mutableMapOf<String, AnalysisPosition>()
    var selectedId by mutableStateOf(DemoRecord.INITIAL_NODE)
        private set
    var routeLeafId by mutableStateOf(DemoRecord.leafFrom(DemoRecord.INITIAL_NODE))
        private set
    var tab by mutableIntStateOf(0)
    var preview by mutableStateOf<Candidate?>(null)
        private set
    var previewStep by mutableIntStateOf(0)
        private set
    var autoPlayArmed by mutableStateOf(true)
        private set
    var moveError by mutableStateOf<Int?>(null)
        private set

    val nodes get() = recordNodes.toList()
    val byId get() = recordNodes.associateBy { it.id }
    val node get() = byId.getValue(selectedId)
    val basePosition get() = positionFor(selectedId)
    val position get() = preview?.let { basePosition.after(it.moves.take(previewStep)) } ?: basePosition
    /** The entire selected continuation, including moves after the current selection. */
    val route get() = path(routeLeafId)
    val atStart get() = if (preview != null) previewStep == 0 else node.parentId == null
    val atEnd get() = if (preview != null) previewStep == preview!!.moves.size else selectedId == routeLeafId

    fun path(id: String): List<RecordNode> {
        val index = byId
        return buildList {
            var next: RecordNode? = index.getValue(id)
            while (next != null) { add(next); next = next.parentId?.let(index::getValue) }
        }.asReversed()
    }

    fun positionFor(id: String): AnalysisPosition = positions.getOrPut(id) {
        positionForPath(path(id), rules, komi, initialBlack, initialWhite, initialPlayer)
    }

    private fun leafFrom(id: String): String {
        val children = recordNodes.filter { it.parentId != null }.groupBy { it.parentId }
        var current = id
        while (true) current = children[current]?.firstOrNull()?.id ?: return current
    }

    fun chooseNode(id: String) {
        require(id in byId)
        pauseAutomatic(); preview = null; moveError = null
        selectedId = id
        routeLeafId = leafFrom(id)
    }

    /** Seeking within the chart must retain the selected branch at earlier forks. */
    fun seekToNode(id: String) {
        if (route.none { it.id == id }) return
        pauseAutomatic(); preview = null; moveError = null
        selectedId = id
    }

    /** All manual and automatic moves pass through the same official legality check. */
    fun play(point: Int): Boolean {
        if (preview != null) return false
        val board = BoardSnapshot.from(basePosition)
        if (!board.isLegal(point)) {
            moveError = if (board.finished) R.string.game_finished else R.string.illegal_move
            return false
        }
        if (basePosition.moves.size >= 2000 || recordNodes.size >= SgfCodec.MAX_NODES) {
            moveError = R.string.record_limit; pauseAutomatic(); return false
        }
        val children = recordNodes.filter { it.parentId == selectedId }
        val color = if (board.blackToMove) 1 else 2
        val existing = children.firstOrNull { it.color == color && (it.point?.index ?: PASS) == point }
        if (existing != null) {
            selectedId = existing.id; routeLeafId = leafFrom(existing.id)
        } else {
            if (selectedId == leafFrom(recordNodes.first().id)) {
                recordNodes[0] = recordNodes.first().copy(properties = recordNodes.first().properties - "RE")
            }
            val next = RecordNode("local-${recordNodes.size}", selectedId, node.move + 1, BoardPoint.fromIndex(point),
                if (children.isEmpty()) node.lane else recordNodes.maxOf { it.lane } + 1,
                if (children.isEmpty()) node.branch else "variation", color, node.depth + 1)
            recordNodes += next
            selectedId = next.id; routeLeafId = next.id
        }
        autoPlayArmed = true; moveError = null
        return true
    }

    fun resumeAutomatic() { if (preview == null) { autoPlayArmed = true; moveError = null } }
    fun pauseAutomatic() { autoPlayArmed = false }
    fun automaticFailed(message: Int) { pauseAutomatic(); moveError = message }
    fun startPreview(candidate: Candidate) {
        val moves = candidate.moves.take(minOf(MAX_PREVIEW_MOVES, (2000 - basePosition.moves.size).coerceAtLeast(0)))
        if (moves.isEmpty()) return
        pauseAutomatic(); preview = candidate.copy(moves = moves); previewStep = moves.size; tab = 0; moveError = null
    }
    fun exitPreview() { if (preview != null) { preview = null } }
    fun first() {
        pauseAutomatic(); moveError = null
        if (preview != null) previewStep = 0 else selectedId = route.first().id
    }
    fun previous() {
        pauseAutomatic(); moveError = null
        if (preview != null) previewStep = (previewStep - 1).coerceAtLeast(0)
        else node.parentId?.let { selectedId = it }
    }
    fun next() { pauseAutomatic(); advance() }
    fun last() {
        pauseAutomatic(); moveError = null
        if (preview != null) previewStep = preview!!.moves.size else selectedId = routeLeafId
    }
    fun advance() {
        moveError = null
        if (atEnd) return
        if (preview != null) previewStep++
        else route.getOrNull(route.indexOfFirst { it.id == selectedId } + 1)?.let { selectedId = it.id }
    }
    fun newGame() {
        clearTransient()
        isDemo = false; recordName = ""
        initialBlack = emptyList(); initialWhite = emptyList(); initialPlayer = 1
        recordNodes.clear(); recordNodes += RecordNode("game-0", null, 0, null, 0, "main")
        selectedId = "game-0"; routeLeafId = "game-0"; autoPlayArmed = true
    }
    fun reset() {
        clearTransient()
        isDemo = true; recordName = ""
        initialBlack = emptyList(); initialWhite = emptyList(); initialPlayer = 1
        recordNodes.clear(); recordNodes.addAll(DemoRecord.nodes)
        selectedId = DemoRecord.INITIAL_NODE; routeLeafId = leafFrom(selectedId)
        pauseAutomatic()
    }
    private fun clearTransient() { positions.clear(); preview = null; moveError = null; tab = 0 }

    fun gameWithRules(rules: GoRules, komi: Float) = SgfGame(nodes, rules, komi, initialBlack, initialWhite, initialPlayer)
    fun applyRules(rules: GoRules, komi: Float) {
        require(validKomi(komi))
        this.rules = rules; this.komi = komi
        positions.clear(); preview = null
        recordNodes[0] = recordNodes.first().copy(properties = recordNodes.first().properties - "RE")
    }
    fun load(game: SgfGame, name: String) {
        clearTransient(); pauseAutomatic(); isDemo = false
        recordName = game.nodes.first().properties["GN"]?.firstOrNull()?.takeIf { it.isNotBlank() } ?: name
        rules = game.rules; komi = game.komi
        initialBlack = game.initialBlack; initialWhite = game.initialWhite; initialPlayer = game.initialPlayer
        recordNodes.clear(); recordNodes.addAll(game.nodes)
        routeLeafId = leafFrom(recordNodes.first().id); selectedId = routeLeafId
    }

    fun exportSgf(): String {
        val tree = linkedMapOf<String, SgfNode>()
        recordNodes.forEach { node ->
            val props = node.properties.toMutableMap()
            props.remove("B"); props.remove("W")
            if (node.color != 0) props[if (node.color == 1) "B" else "W"] = listOf(pointSgf(node.point?.index ?: PASS))
            if (node.parentId == null) {
                props["FF"] = listOf("4"); props["GM"] = listOf("1"); props["SZ"] = listOf("19")
                props["CA"] = listOf("UTF-8"); props["AP"] = listOf("KataDroid:1.0")
                props["RU"] = listOf(rules.id); props["KM"] = listOf(komi.toString())
                props.remove("AB"); props.remove("AW"); props.remove("AE")
                if (initialBlack.isNotEmpty()) props["AB"] = initialBlack.map(::pointSgf)
                if (initialWhite.isNotEmpty()) props["AW"] = initialWhite.map(::pointSgf)
                if (initialPlayer == 2 || "PL" in props) props["PL"] = listOf(if (initialPlayer == 1) "B" else "W")
            }
            val sgf = SgfNode(props)
            tree[node.id] = sgf
            node.parentId?.let { tree.getValue(it).children += sgf }
        }
        return SgfCodec.encode(tree.values.first())
    }

    fun serialize(): String = JSONObject().put("version", 2).put("demo", isDemo).put("name", recordName)
        .put("rules", rules.id).put("komi", komi).put("initialPlayer", initialPlayer)
        .put("black", JSONArray(initialBlack)).put("white", JSONArray(initialWhite))
        .put("selected", selectedId).put("leaf", routeLeafId).put("tab", tab).put("previewStep", previewStep)
        .put("previewLabel", preview?.label).put("previewMoves", JSONArray(preview?.moves.orEmpty()))
        .put("nodes", JSONArray(recordNodes.map { n -> JSONObject().put("id", n.id).put("parent", n.parentId)
            .put("move", n.move).put("point", n.point?.index ?: PASS).put("lane", n.lane).put("branch", n.branch)
            .put("color", n.color).put("depth", n.depth).put("properties", JSONObject(n.properties)) })).toString()

    companion object {
        const val MAX_PREVIEW_MOVES = 7

        private fun validateTree(nodes: List<RecordNode>) {
            require(nodes.size in 1..SgfCodec.MAX_NODES)
            val seen = mutableMapOf<String, RecordNode>()
            nodes.forEachIndexed { index, node ->
                require(node.id.isNotEmpty() && node.id !in seen) { "Duplicate game node" }
                require(node.color in 0..2 && node.lane in -SgfCodec.MAX_NODES..SgfCodec.MAX_NODES && node.move in 0..2000)
                if (index == 0) require(node.parentId == null && node.color == 0 && node.move == 0 && node.depth == 0)
                else {
                    val parent = checkNotNull(seen[node.parentId]) { "Invalid parent node" }
                    require(node.depth == parent.depth + 1 && node.move == parent.move + if (node.color == 0) 0 else 1)
                }
                seen[node.id] = node
            }
        }

        /** Validate all branches off the UI thread before replacing the open record. */
        fun validate(game: SgfGame) {
            validateTree(game.nodes)
            val index = game.nodes.associateBy { it.id }
            val parents = game.nodes.mapNotNull { it.parentId }.toSet()
            var replayed = 0
            game.nodes.filter { it.id !in parents }.forEach { leaf ->
                val path = buildList {
                    var node: RecordNode? = leaf
                    while (node != null) { add(node); node = node.parentId?.let(index::getValue) }
                }.asReversed()
                replayed += path.size
                require(replayed <= 200000) { "Too many variations to load" }
                BoardSnapshot.from(positionForPath(path, game.rules, game.komi, game.initialBlack, game.initialWhite, game.initialPlayer))
            }
        }

        fun restore(value: String): RecordUiState {
            val json = JSONObject(value)
            require(json.getInt("version") == 2)
            fun ints(key: String) = json.getJSONArray(key).let { a -> List(a.length()) { a.getInt(it) } }
            return RecordUiState(GoRules.fromId(json.getString("rules")), json.getDouble("komi").toFloat()).apply {
                isDemo = json.getBoolean("demo"); recordName = json.getString("name")
                initialBlack = ints("black"); initialWhite = ints("white"); initialPlayer = json.getInt("initialPlayer")
                recordNodes.clear()
                val array = json.getJSONArray("nodes")
                require(array.length() in 1..SgfCodec.MAX_NODES)
                repeat(array.length()) { i ->
                    val n = array.getJSONObject(i)
                    val properties = n.getJSONObject("properties")
                    val props = properties.keys().asSequence().associateWith { key ->
                        val a = properties.getJSONArray(key); List(a.length()) { a.getString(it) }
                    }
                    recordNodes += RecordNode(n.getString("id"), n.optString("parent").takeIf { it.isNotEmpty() }, n.getInt("move"),
                        BoardPoint.fromIndex(n.getInt("point")), n.getInt("lane"), n.getString("branch"), n.getInt("color"), n.getInt("depth"), props)
                }
                validateTree(recordNodes)
                selectedId = json.getString("selected"); routeLeafId = json.getString("leaf"); tab = json.getInt("tab")
                require(selectedId in byId && routeLeafId in byId && tab in 0..1)
                require(path(routeLeafId).any { it.id == selectedId })
                if (json.has("previewLabel")) {
                    val moves = ints("previewMoves").also { require(it.size in 1..MAX_PREVIEW_MOVES) }
                    preview = Candidate(json.getString("previewLabel"), moves)
                }
                previewStep = json.getInt("previewStep")
                require(previewStep in 0..(preview?.moves?.size ?: MAX_PREVIEW_MOVES))
                pauseAutomatic() // Reopening the app must not silently advance an imported/saved game.
                BoardSnapshot.from(position)
            }
        }
    }
}
