package network.tos.blockchain.ton.contract

import org.ton.block.AddrStd
import org.ton.block.StateInit
import org.ton.cell.Cell
import org.ton.cell.CellType
import org.ton.cell.buildCell
import org.ton.tlb.CellRef

/** Decode the shipped native contract when a node omits it from its registry.
 * Accept a sequence only after binding ordinary state to its code and initial address. */
object NativeWalletCompatibility {
    fun matchesCode(code: Cell): Boolean = code.hash() == TosWalletV5R1Contract.CODE.hash()

    fun verifiedSeqno(address: AddrStd, code: Cell, data: Cell): Int {
        require(code.type == CellType.ORDINARY && data.type == CellType.ORDINARY &&
            code.levelMask.level == 0 && data.levelMask.level == 0) {
            "Native wallet code and data must be ordinary level-zero cells"
        }
        require(matchesCode(code)) { "Unrecognized native wallet code" }
        val slice = data.beginParse()
        require(slice.loadBit()) { "Native wallet signatures are disabled" }
        val seqno = slice.loadUInt(32).toLong()
        require(seqno <= Int.MAX_VALUE) { "Unsupported native wallet sequence number" }
        val subwallet = slice.loadUInt(32)
        val key = slice.loadBits(256)
        if (slice.loadBit()) slice.loadRef()
        require(slice.bitsPosition == slice.bits.size && slice.refsPosition == slice.refs.size) {
            "Unexpected native wallet state data"
        }
        val initial = buildCell {
            storeBit(true)
            storeUInt(0, 32)
            storeUInt(subwallet, 32)
            storeBits(key)
            storeBit(false)
        }
        val initialHash = CellRef(StateInit(TosWalletV5R1Contract.CODE, initial), StateInit).hash()
        require(address == AddrStd(address.workchainId, initialHash)) { "Native wallet initial address mismatch" }
        return seqno.toInt()
    }
}
