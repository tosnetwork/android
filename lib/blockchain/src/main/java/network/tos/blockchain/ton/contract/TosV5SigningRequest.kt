package network.tos.blockchain.ton.contract

import network.tos.blockchain.ton.TONOpCode
import org.ton.cell.Cell
import org.ton.cell.loadRef
import org.ton.block.AddrStd
import org.ton.block.AddrNone
import org.ton.block.Coins
import org.ton.block.CommonMsgInfoRelaxed
import org.ton.block.MessageRelaxed
import org.ton.cell.CellType
import org.ton.cell.buildCell
import org.ton.tlb.constructor.AnyTlbConstructor
import org.ton.tlb.loadTlb
import org.ton.tlb.storeTlb
import java.io.ByteArrayOutputStream

/** Decode the complete supported request before presenting or signing it. */
data class TosV5Transfer(val destination: AddrStd, val coins: Coins, val comment: String?)
data class TosV5SigningRequest(val globalId: Int, val seqno: Int, val validUntil: Long,
    val messages: List<Cell>, val transfers: List<TosV5Transfer>) {
    companion object {
        fun parse(body: Cell, expectedGlobalId: Int, expectedSeqno: Int,
            now: Long = System.currentTimeMillis() / 1000, allowSendAll: Boolean = false): TosV5SigningRequest {
            val slice = body.beginParse()
            require(slice.loadUInt(32).toLong() in setOf(TONOpCode.SIGNED_EXTERNAL.code, TONOpCode.SIGNED_INTERNAL.code)) {
                "Unsupported TOS V5 request opcode"
            }
            val globalId = slice.loadInt(32).toInt()
            require(globalId == expectedGlobalId) { "Signing request network mismatch" }
            require(slice.loadUInt(32).toLong() == 0L) { "Unsupported subwallet" }
            val expiry = slice.loadUInt(32).toLong()
            require(expiry > now) { "Signing request has expired" }
            val seqnoValue = slice.loadUInt(32).toLong()
            require(seqnoValue == expectedSeqno.toLong() && expectedSeqno >= 0) { "Signing request seqno mismatch" }
            require(slice.loadBit()) { "Signing request has no actions" }
            var actions = slice.loadRef()
            require(!slice.loadBit()) { "Extended actions are unavailable" }
            require(slice.bitsPosition == slice.bits.size && slice.refsPosition == slice.refs.size) { "Unexpected signing data" }
            val messages = mutableListOf<Cell>()
            while (!actions.isEmpty()) {
                require(messages.size < 255) { "Too many wallet actions" }
                val action = actions.beginParse()
                val previous = action.loadRef()
                require(action.loadUInt(32).toLong() == TONOpCode.OUT_ACTION_SEND_MSG_TAG.code) { "Unsupported wallet action" }
                val mode = action.loadUInt(8).toInt()
                require(mode == 3 || (allowSendAll && mode == 130)) { "Unsupported signing send mode" }
                messages.add(action.loadRef())
                require(action.bitsPosition == action.bits.size && action.refsPosition == action.refs.size) { "Malformed wallet action" }
                actions = previous
            }
            require(messages.isNotEmpty()) { "Signing request has no messages" }
            val ordered = messages.reversed()
            return TosV5SigningRequest(globalId, expectedSeqno, expiry, ordered, ordered.map(::decodeTransfer))
        }

        /** Only message semantics completely represented by the native confirmation UI. */
        private fun decodeTransfer(cell: Cell): TosV5Transfer {
            require(cell.type == CellType.ORDINARY) { "Exotic messages are unavailable" }
            val message = cell.parse { loadTlb(MessageRelaxed.tlbCodec(AnyTlbConstructor)) }
            require(buildCell { storeTlb(MessageRelaxed.tlbCodec(AnyTlbConstructor), message) }.hash() == cell.hash()) {
                "Noncanonical or trailing message data is unsupported"
            }
            val info = message.info as? CommonMsgInfoRelaxed.IntMsgInfoRelaxed
                ?: throw IllegalArgumentException("Only native internal transfers are supported")
            require(info.ihrDisabled && !info.bounced && info.src == AddrNone && info.ihrFee == Coins() &&
                info.fwdFee == Coins() && info.createdLt == 0uL && info.createdAt == 0u) {
                "Unsupported native message metadata"
            }
            require(message.init.value == null) { "Recipient deployment is not supported by this signer" }
            require(!info.value.other.dict.iterator().hasNext()) { "Extra currencies are not supported by this signer" }
            val destination = info.dest as? AddrStd ?: error("Unsupported destination address")
            require(destination.anycast.value == null) { "Anycast transfers are unavailable" }
            val payload = message.body.x ?: requireNotNull(message.body.y).value
            return TosV5Transfer(destination, info.value.coins, decodeComment(payload))
        }

        private fun decodeComment(payload: Cell): String? {
            if (payload.isEmpty()) return null
            require(payload.type == CellType.ORDINARY) { "Exotic payload is unavailable" }
            var slice = payload.beginParse()
            require(slice.loadUInt(32).toLong() == 0L) { "Only empty or plain comment payloads are supported" }
            val bytes = ByteArrayOutputStream()
            var cells = 0
            while (true) {
                require(++cells <= 33) { "Comment chain is too long" }
                val bits = slice.bits.size - slice.bitsPosition
                require(bits % 8 == 0 && slice.refs.size - slice.refsPosition <= 1) { "Malformed comment payload" }
                repeat(bits / 8) { bytes.write(slice.loadUInt(8).toInt()) }
                require(bytes.size() <= 4096) { "Comment is too long" }
                if (slice.refsPosition == slice.refs.size) break
                val next = slice.loadRef()
                require(next.type == CellType.ORDINARY) { "Exotic comment is unavailable" }
                slice = next.beginParse()
            }
            return bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
        }
    }
}

object SigningNetworkId {
    fun parse(value: String?, requireExplicit: Boolean): Int {
        if (requireExplicit) return value?.toIntOrNull() ?: error("Explicit signed int32 network ID is required")
        return when (value?.lowercase()) {
            null, "mainnet", "-239" -> -239
            "testnet", "-3" -> -3
            else -> value.toIntOrNull() ?: error("Unknown signing network")
        }
    }
}
