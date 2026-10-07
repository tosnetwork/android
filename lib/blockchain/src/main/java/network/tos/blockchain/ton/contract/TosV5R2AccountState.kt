package network.tos.blockchain.ton.contract

import org.ton.block.StateInit
import network.tos.blockchain.ton.extensions.loadAddress
import org.ton.block.AddrStd
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.CellType
import java.math.BigInteger

/** Raw codec only. The wallet service must obtain bytes from a verified proof capability. */
object TosV5R2AccountState {
    /** Raw balance and recorded debt are observations, not spendable balance or a fee quote. */
    data class Snapshot(val data: Cell, val balance: BigInteger, val storageDebt: BigInteger, val lastPaid: Long)
    fun data(boc: ByteArray, expectedAddress: AddrStd, expectedCode: Cell): Cell =
        snapshot(boc, expectedAddress, expectedCode).data
    fun snapshot(boc: ByteArray, expectedAddress: AddrStd, expectedCode: Cell): Snapshot {
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
        fun coins(): BigInteger {
            val count = slice.loadUInt(4).toInt()
            return if (count == 0) BigInteger.ZERO else BigInteger(1, slice.loadBits(count * 8).toByteArray())
        }
        val lastPaid = slice.loadUInt(32).toLong()
        val storageDebt = if (slice.loadBit()) coins() else BigInteger.ZERO
        slice.loadUInt(64) // last_trans_lt
        val balance = coins()
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
        return Snapshot(data, balance, storageDebt, lastPaid)
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
