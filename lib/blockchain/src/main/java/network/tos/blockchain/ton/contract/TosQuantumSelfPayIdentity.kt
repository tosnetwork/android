package network.tos.blockchain.ton.contract

import network.tos.blockchain.ton.extensions.toByteArray
import org.ton.block.AddrStd
import org.ton.boc.BagOfCells
import org.ton.cell.Cell
import org.ton.cell.buildCell

/** Immutable public identity, retained across key rotation. Contains no custody secrets.
 * QSI1 || basechain address hash || ^original StateInit. Current chain data is never a birth identity.
 */
class TosQuantumSelfPayIdentity(val wallet: TosQuantumSelfPayWallet) {
    val address: AddrStd get() = wallet.address
    val cell: Cell = buildCell {
        storeUInt(0x51534931, 32); storeBytes(wallet.address.address.toByteArray()); storeRef(wallet.stateInit)
    }

    fun toBoc(): ByteArray = cell.toByteArray()

    fun requireNetwork(globalId: Int, namespace: ByteArray) {
        require(wallet.initialAuthority.globalId == globalId && wallet.initialAuthority.namespace.contentEquals(namespace)) {
            "Quantum identity belongs to another network"
        }
    }

    override fun equals(other: Any?): Boolean = other is TosQuantumSelfPayIdentity && cell.hash() == other.cell.hash()
    override fun hashCode(): Int = cell.hash().hashCode()

    companion object {
        const val MAX_BOC_BYTES = 16384

        fun fromBoc(bytes: ByteArray): TosQuantumSelfPayIdentity {
            require(bytes.size in 1..MAX_BOC_BYTES) { "Invalid Quantum identity size" }
            preflightOrdinaryBoc(bytes)
            return parse(BagOfCells(bytes).roots.single())
        }

        /** Bound declared counts and references before the general SDK allocates or indexes them. */
        fun preflightOrdinaryBoc(bytes: ByteArray, maximumBytes: Int = MAX_BOC_BYTES) {
            require(maximumBytes in 1..1_048_576 && bytes.size in 1..maximumBytes)
            var offset = 0
            fun uint(width: Int): Long {
                require(width in 1..4 && width <= bytes.size - offset)
                var result = 0L
                repeat(width) { result = (result shl 8) or (bytes[offset++].toLong() and 255) }
                return result
            }
            require(uint(4) == 0xb5ee9c72L)
            val flags = uint(1).toInt()
            val size = flags and 7
            val offsetSize = uint(1).toInt()
            require(flags and 0x38 == 0 && size in 1..4 && offsetSize in 1..4)
            val cells = uint(size)
            val roots = uint(size)
            val absent = uint(size)
            val cellBytes = uint(offsetSize)
            require(cells in 1..minOf(maximumBytes / 2, 16384).toLong() && roots == 1L && absent == 0L &&
                cellBytes <= maximumBytes && cells <= cellBytes / 2 && uint(size) == 0L)
            val count = cells.toInt()
            val index = if (flags and 0x80 != 0) List(count) { uint(offsetSize) } else emptyList()
            require(bytes.size - offset == cellBytes.toInt() + if (flags and 0x40 != 0) 4 else 0)
            val start = offset
            val end = start + cellBytes.toInt()
            val starts = mutableListOf<Long>()
            val ends = mutableListOf<Long>()
            val references = Array(count) { emptyList<Int>() }
            val reachable = BooleanArray(count)
            reachable[0] = true
            repeat(count) { cell ->
                starts.add((offset - start).toLong())
                val descriptor = uint(1).toInt()
                val bitsDescriptor = uint(1).toInt()
                require(descriptor in 0..4) { "Ordinary level-zero cells only" }
                val length = (bitsDescriptor + 1) / 2
                require(length <= end - offset)
                if (bitsDescriptor and 1 != 0) {
                    require(length > 0 && bytes[offset + length - 1].toInt() and 0x7f != 0)
                }
                offset += length
                references[cell] = List(descriptor) {
                    val reference = uint(size)
                    require(reference > cell && reference < count)
                    if (reachable[cell]) reachable[reference.toInt()] = true
                    reference.toInt()
                }
                require(offset <= end)
                ends.add((offset - start).toLong())
            }
            require(offset == end && reachable.all { it } && (index.isEmpty() || index == starts || index == ends))
            val depths = IntArray(count)
            for (cell in count - 1 downTo 0) {
                depths[cell] = references[cell].maxOfOrNull { depths[it] + 1 } ?: 0
                require(depths[cell] <= 1023)
            }
            // CRC32C is checked by the SDK after this structural preflight.
        }

        fun parse(cell: Cell): TosQuantumSelfPayIdentity {
            QuantumSelfPayAuthority.ordinary(cell)
            val s = cell.beginParse()
            require(s.remainingBits == 288 && s.refs.size == 1 && s.loadUInt(32).toLong() == 0x51534931L)
            val expected = AddrStd(0, s.loadBits(256))
            val stateInit = s.loadRef()
            QuantumSelfPayAuthority.ordinary(stateInit)
            val init = stateInit.beginParse()
            require(init.remainingBits == 5 && init.refs.size == 2 && init.loadUInt(5).toInt() == 6)
            val code = init.loadRef()
            QuantumSelfPayAuthority.ordinary(code)
            require(code.hash() == TosQuantumSelfPayCode.wallet.hash()) { "Unknown Quantum wallet code" }
            val state = QuantumSelfPayState.parse(init.loadRef())
            require(state.seqno == 0L && state.epoch == 0uL && state.retired == 0 &&
                state.primaryNonce == 0uL && state.rescueNonce == 0uL) { "Expected original deployment state" }
            val wallet = TosQuantumSelfPayWallet(state.authority, state.walletId)
            require(wallet.address == expected && wallet.stateInit.hash() == stateInit.hash()) { "Quantum birth address mismatch" }
            return TosQuantumSelfPayIdentity(wallet)
        }
    }
}
