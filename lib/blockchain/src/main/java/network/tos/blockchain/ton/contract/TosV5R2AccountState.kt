package network.tos.blockchain.ton.contract

import org.ton.block.StateInit
import network.tos.blockchain.ton.extensions.loadAddress
import org.ton.block.AddrStd
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.CellType

/** Raw codec only. The wallet service must obtain bytes from a verified proof capability. */
object TosV5R2AccountState {
    fun data(boc: ByteArray, expectedAddress: AddrStd, expectedCode: Cell): Cell {
        require(boc.size in 1..67_108_864 && expectedAddress.workchainId == 0 && expectedAddress.anycast.value == null)
        val root = BagOfCells(boc).roots.single()
        require(root.type == CellType.ORDINARY && root.levelMask.level == 0) { "Ordinary account required" }
        val slice = root.beginParse()
        require(slice.loadBit()) { "Account is absent" }
        require(slice.loadAddress() == expectedAddress) { "Account address mismatch" }
        // TOS block.tlb: two VarUInteger7 fields followed by StorageExtraInfo,
        // replacing the older TON codec's third public_cells VarUInteger7.
        repeat(2) {
            val count = slice.loadUInt(3).toInt()
            require(count < 7) { "Invalid storage counter" }
            slice.loadBits(count * 8)
        }
        when (slice.loadUInt(3).toInt()) {
            0 -> Unit
            1 -> slice.loadBits(256)
            else -> error("Unknown storage extra info")
        }
        slice.loadUInt(32) // last_paid
        if (slice.loadBit()) {
            val count = slice.loadUInt(4).toInt(); slice.loadBits(count * 8)
        }
        slice.loadUInt(64) // last_trans_lt
        val balanceBytes = slice.loadUInt(4).toInt(); slice.loadBits(balanceBytes * 8)
        if (slice.loadBit()) slice.loadRef() // ExtraCurrencyCollection HashmapE
        require(slice.loadBit()) { "Account is not active" }
        val state = StateInit.loadTlb(slice)
        require(slice.remainingBits == 0 && slice.refs.size == slice.refsPosition) { "Trailing account data" }
        require(state.splitDepth.value == null && state.special.value == null && !state.library.iterator().hasNext()) {
            "Unsupported account StateInit flags or libraries"
        }
        val code = checkNotNull(state.code.value?.value)
        val data = checkNotNull(state.data.value?.value)
        require(code.type == CellType.ORDINARY && code.levelMask.level == 0 && code.hash() == expectedCode.hash()) { "Account code mismatch" }
        require(data.type == CellType.ORDINARY && data.levelMask.level == 0) { "Ordinary account data required" }
        return data
    }
    /** Everything except the monotonically consumed leaf counter must match enrollment. */
    fun vaultCounter(data: Cell, expected: Cell): Long {
        require(data.type == CellType.ORDINARY && data.levelMask.level == 0) { "Ordinary vault data required" }
        val actual = data.beginParse(); val enrolled = expected.beginParse()
        require(actual.remainingBits == enrolled.remainingBits && actual.refs.size == enrolled.refs.size) { "Vault data shape" }
        require(actual.loadUInt(8).toInt() == 3) { "Vault state version" }
        enrolled.loadUInt(8)
        val next = actual.loadUInt(32).toLong(); enrolled.loadUInt(32)
        require(next in 0..1_048_576) { "Invalid vault leaf counter" }
        require(actual.loadBits(actual.remainingBits) == enrolled.loadBits(enrolled.remainingBits)) { "Vault immutable configuration mismatch" }
        while (actual.refsPosition < actual.refs.size) {
            require(actual.loadRef().hash() == enrolled.loadRef().hash()) { "Vault immutable reference mismatch" }
        }
        return next
    }
}
