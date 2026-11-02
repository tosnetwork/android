package network.tos.blockchain.ton.contract

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import org.ton.cell.Cell
import org.ton.cell.CellSlice
import org.ton.cell.CellType

/** Bounded TEP-64 content codec. Read this from the authenticated master state.
 * An offchain URI is a pointer, not authenticated JSON or a confirmed precision.
 */
class TokenContent private constructor(val name: String?, val symbol: String?, val decimals: Int?, val uri: String?) {
    companion object {
        private fun ordinary(cell: Cell) = require(cell.type == CellType.ORDINARY && cell.levelMask.level == 0)
        private fun end(slice: CellSlice) = require(slice.remainingBits == 0 && slice.refsPosition == slice.refs.size)
        private fun text(slice: CellSlice, maximumBytes: Int): String {
            val out = ByteArrayOutputStream()
            var current = slice
            repeat(256) {
                require(current.remainingBits % 8 == 0 && current.refs.size - current.refsPosition in 0..1)
                val count = current.remainingBits / 8
                require(count <= maximumBytes - out.size())
                if (count > 0) out.write(current.loadBits(count * 8).toByteArray())
                if (current.refsPosition == current.refs.size) {
                    return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray())).toString()
                }
                val next = current.loadRef(); ordinary(next); current = next.beginParse()
            }
            error("Token metadata snake is too deep")
        }
        private fun lookup(root: Cell?, field: String): Cell? {
            var cell = root ?: return null
            val key = MessageDigest.getInstance("SHA-256").digest(field.toByteArray(Charsets.UTF_8))
            fun bit(offset: Int) = (key[offset / 8].toInt() ushr (7 - offset % 8)) and 1 != 0
            var offset = 0
            repeat(257) {
                ordinary(cell)
                val s = cell.beginParse(); val remaining = 256 - offset
                val same: Boolean?
                val length: Int
                if (!s.loadBit()) {
                    same = null
                    var n = 0
                    while (s.loadBit()) { n++; require(n <= remaining) }
                    length = n
                } else {
                    val repeated = s.loadBit()
                    same = if (repeated) s.loadBit() else null
                    val width = if (remaining == 0) 0 else 32 - Integer.numberOfLeadingZeros(remaining)
                    length = if (width == 0) 0 else s.loadUInt(width).toInt()
                    require(length <= remaining)
                }
                var matches = true
                repeat(length) { index -> if ((same ?: s.loadBit()) != bit(offset + index)) matches = false }
                if (!matches) return null
                offset += length
                if (offset == 256) {
                    require(s.remainingBits == 0 && s.refs.size - s.refsPosition == 1)
                    return s.loadRef().also { ordinary(it) }
                }
                require(s.remainingBits == 0 && s.refs.size - s.refsPosition == 2)
                val left = s.loadRef(); val right = s.loadRef()
                cell = if (bit(offset++)) right else left
            }
            error("Token metadata dictionary is too deep")
        }
        fun decode(content: Cell): TokenContent {
            ordinary(content)
            val s = content.beginParse()
            return when (s.loadUInt(8).toInt()) {
                1 -> TokenContent(null, null, null, text(s, 2048).also { require(it.isNotEmpty()) })
                0 -> {
                    val root = if (s.loadBit()) s.loadRef() else null
                    end(s)
                    fun field(name: String, limit: Int): String? = lookup(root, name)?.let {
                        val value = it.beginParse(); require(value.loadUInt(8).toInt() == 0)
                        text(value, limit).takeIf { string -> string.isNotEmpty() }
                    }
                    val uri = field("uri", 2048)
                    val declared = field("decimals", 3)
                    val decimals = if (declared == null) { if (uri == null) 9 else null } else {
                        require(declared.matches(Regex("[0-9]{1,3}")))
                        declared.toInt().also { require(it <= 255) }
                    }
                    TokenContent(field("name", 128), field("symbol", 32), decimals, uri)
                }
                else -> error("Unsupported Token metadata content")
            }
        }
    }
}
