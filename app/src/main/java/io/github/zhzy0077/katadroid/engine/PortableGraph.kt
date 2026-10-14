package io.github.zhzy0077.katadroid.engine

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Inspect the stable TFLite v3 envelope before native compilation of imported bytes.
 * Field numbers follow the upstream schema; unknown builtin kernels are checked by LiteRT.
 * The mapped reader touches table headers, not the large constant weight payloads.
 */
internal object PortableGraph {
    private val forbidden = setOf(31, 32, 118, 119, 129, 136, 137, 138, 139, 142, 143, 144, 146, 148, 149)

    fun check(file: File) {
        require(file.length() in 8..512L * 1024 * 1024) { "Invalid portable model size" }
        FileInputStream(file).channel.use { channel ->
            val bytes = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.LITTLE_ENDIAN)
            val reader = Reader(bytes)
            require((4..7).map { bytes.get(it).toInt().toChar() }.joinToString("") == "TFL3") { "Expected TFLite model" }
            val model = reader.relative(0)
            require(reader.integer(model, 0) == 3) { "Unsupported TFLite schema" }
            val graphs = reader.tables(model, 2)
            require(graphs.size == 1) { "Portable model requires one subgraph" }
            for (code in reader.tables(model, 1)) {
                val builtin = maxOf(reader.integer(code, 3), reader.byte(code, 0).toByte().toInt())
                require(builtin !in forbidden && reader.integer(code, 3) !in forbidden && reader.field(code, 1) == null) { "Custom or stateful operator" }
            }
            for (tensor in reader.tables(graphs.single(), 0))
                require(reader.byte(tensor, 5) == 0) { "Variable tensor" }
            for (buffer in reader.tables(model, 4))
                require(reader.long(buffer, 1) == 0L && reader.long(buffer, 2) == 0L) { "External tensor buffer" }
        }
    }

    private class Reader(private val data: ByteBuffer) {
        private fun bounds(offset: Long, length: Long): Int {
            require(offset >= 0 && length >= 0 && offset <= data.limit().toLong() - length) { "Invalid flatbuffer offset" }
            return offset.toInt()
        }
        private fun unsigned(offset: Int) = data.getInt(bounds(offset.toLong(), 4)).toLong() and 0xffffffffL
        fun relative(offset: Int): Int = bounds(offset.toLong() + unsigned(offset), 4)
        fun field(table: Int, index: Int): Int? {
            bounds(table.toLong(), 4)
            val vtable = bounds(table.toLong() - data.getInt(table), 4)
            val length = data.getShort(vtable).toInt() and 65535
            val objectSize = data.getShort(vtable + 2).toInt() and 65535
            require(length >= 4 && objectSize >= 4)
            bounds(vtable.toLong(), length.toLong())
            bounds(table.toLong(), objectSize.toLong())
            val slot = 4 + 2 * index
            if (slot + 2 > length) return null
            val displacement = data.getShort(vtable + slot).toInt() and 65535
            if (displacement == 0) return null
            require(displacement in 4 until objectSize) { "Invalid flatbuffer field" }
            return bounds(table.toLong() + displacement, 1)
        }
        fun integer(table: Int, index: Int) = field(table, index)?.let { data.getInt(bounds(it.toLong(), 4)) } ?: 0
        fun byte(table: Int, index: Int) = field(table, index)?.let { data.get(bounds(it.toLong(), 1)).toInt() and 255 } ?: 0
        fun long(table: Int, index: Int) = field(table, index)?.let { data.getLong(bounds(it.toLong(), 8)) } ?: 0L
        fun tables(table: Int, index: Int): List<Int> {
            val cell = field(table, index) ?: return emptyList()
            val vector = relative(cell)
            val count = unsigned(vector)
            require(count <= 100000) { "Excessive model table count" }
            bounds(vector.toLong() + 4, count * 4)
            return List(count.toInt()) { relative(vector + 4 + it * 4) }
        }
    }
}
