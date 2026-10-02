package io.github.zhzy0077.katadroid.sgf

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class SgfNode(val properties: Map<String, List<String>>, val children: MutableList<SgfNode> = mutableListOf())

/** FF[4] syntax, including collections, variations, multi-values and escaping. */
object SgfCodec {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_NODES = 10000

    fun decode(bytes: ByteArray): List<SgfNode> {
        require(bytes.size <= MAX_BYTES) { "SGF files must not exceed 2 MB" }
        val ascii = bytes.toString(Charsets.ISO_8859_1)
        // Read actual root properties; text inside C[...] must not masquerade
        // as a character-set declaration.
        val header = Parser(ascii.removePrefix("\u00EF\u00BB\u00BF"))
        header.expect('('); header.expect(';')
        val declared = header.properties()["CA"]?.singleOrNull()
        fun decodeWith(charset: Charset) = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val text = if (declared != null) {
            val charset = runCatching { Charset.forName(declared) }.getOrElse { error("Unsupported SGF encoding: $declared") }
            decodeWith(charset)
        } else runCatching { decodeWith(Charsets.UTF_8) }.getOrElse { ascii }
        return parse(text.removePrefix("\uFEFF"))
    }

    fun parse(text: String): List<SgfNode> {
        require(text.length <= MAX_BYTES) { "SGF file too large" }
        return Parser(text).collection()
    }

    fun encode(root: SgfNode): String = buildString {
        // Explicit stack also handles long main lines without consuming stack frames.
        val tasks = ArrayDeque<Any>()
        tasks.addLast(root)
        while (tasks.isNotEmpty()) {
            when (val task = tasks.removeLast()) {
                is String -> append(task)
                is SgfNode -> {
                    append('(')
                    var node: SgfNode = task
                    while (true) {
                        append(';')
                        node.properties.forEach { (key, values) ->
                            require(key.matches(Regex("[A-Z]+")) && values.isNotEmpty())
                            append(key)
                            values.forEach { value ->
                                append('[')
                                append(value.replace("\\", "\\\\").replace("]", "\\]"))
                                append(']')
                            }
                        }
                        if (node.children.size != 1) break
                        node = node.children.single()
                    }
                    tasks.addLast(")")
                    node.children.asReversed().forEach(tasks::addLast)
                }
            }
        }
        append('\n')
    }

    private class Parser(val text: String) {
        var index = 0
        var nodes = 0
        fun whitespace() { while (index < text.length && text[index].isWhitespace()) index++ }
        fun fail(message: String): Nothing = throw IllegalArgumentException("$message (character ${index + 1})")
        fun expect(character: Char) { whitespace(); if (text.getOrNull(index) != character) fail("Expected $character in SGF"); index++ }
        fun properties(): Map<String, List<String>> {
            val properties = linkedMapOf<String, List<String>>()
            whitespace()
            while (text.getOrNull(index) in 'A'..'Z') {
                val start = index
                while (text.getOrNull(index) in 'A'..'Z') index++
                val key = text.substring(start, index)
                if (key in properties) fail("Duplicate SGF property $key")
                val values = mutableListOf<String>()
                whitespace()
                while (text.getOrNull(index) == '[') { values += value(); whitespace() }
                if (values.isEmpty()) fail("Missing value for property $key")
                properties[key] = values
            }
            return properties
        }
        fun collection(): List<SgfNode> {
            val roots = mutableListOf<SgfNode>()
            whitespace()
            while (index < text.length) {
                require(roots.size < 100) { "At most 100 games per file are supported" }
                roots += tree(0)
                whitespace()
            }
            require(roots.isNotEmpty()) { "SGF file is empty" }
            return roots
        }
        fun tree(depth: Int): SgfNode {
            require(depth <= 128) { "SGF variations are nested too deeply" }
            expect('(')
            var root: SgfNode? = null
            var last: SgfNode? = null
            whitespace()
            while (text.getOrNull(index) == ';') {
                index++
                if (++nodes > MAX_NODES) fail("SGF has more than $MAX_NODES nodes")
                val node = SgfNode(properties())
                if (root == null) root = node else last!!.children += node
                last = node
            }
            if (last == null) fail("SGF variation has no nodes")
            whitespace()
            while (text.getOrNull(index) == '(') { last.children += tree(depth + 1); whitespace() }
            expect(')')
            return checkNotNull(root)
        }
        fun value(): String {
            expect('[')
            return buildString {
                while (index < text.length) {
                    val character = text[index++]
                    if (character == ']') return@buildString
                    if (character == '\\') {
                        if (index == text.length) fail("Unterminated escape sequence")
                        val next = text[index++]
                        if (next == '\r' || next == '\n') {
                            if (text.getOrNull(index) == if (next == '\r') '\n' else '\r') index++
                        } else append(next)
                    } else if (character == '\r') {
                        if (text.getOrNull(index) == '\n') index++
                        append('\n')
                    } else append(character)
                }
                fail("Unterminated SGF property")
            }
        }
    }
}

fun sgfPoint(value: String, allowPass: Boolean = false): Int {
    if (allowPass && (value.isEmpty() || value == "tt")) return 361
    require(value.length == 2 && value.all { it in 'a'..'s' }) { "Invalid 19x19 coordinate: $value" }
    return (value[1] - 'a') * 19 + (value[0] - 'a')
}

fun pointSgf(point: Int): String = if (point == 361) "" else "${'a' + point % 19}${'a' + point / 19}"

fun setupPoints(values: List<String>): List<Int> = values.flatMap { value ->
    val range = value.split(':')
    if (range.size == 1) listOf(sgfPoint(value))
    else {
        require(range.size == 2) { "Invalid setup range: $value" }
        val a = sgfPoint(range[0]); val b = sgfPoint(range[1])
        require(a % 19 <= b % 19 && a / 19 <= b / 19) { "Invalid setup range: $value" }
        (a / 19..b / 19).flatMap { row -> (a % 19..b % 19).map { row * 19 + it } }
    }
}.distinct().sorted()
