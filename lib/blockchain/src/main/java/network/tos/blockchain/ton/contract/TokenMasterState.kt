package network.tos.blockchain.ton.contract

import java.math.BigInteger
import network.tos.blockchain.ton.extensions.loadMaybeAddress
import org.ton.cell.Cell
import org.ton.cell.CellType

/** Raw standard master storage. Caller must authenticate actual account/code. */
class TokenMasterState private constructor(val totalSupply: BigInteger, val content: Cell) {
    companion object {
        fun decode(data: Cell, expectedWalletCode: Cell): TokenMasterState {
            require(data.type == CellType.ORDINARY && data.levelMask.level == 0)
            val slice = data.beginParse()
            val bytes = slice.loadUInt(4).toInt()
            val supply = if (bytes == 0) BigInteger.ZERO else BigInteger(1, slice.loadBits(bytes * 8).toByteArray())
            slice.loadMaybeAddress() // Mint admin may be addr_none after renunciation.
            val content = slice.loadRef()
            require(content.type == CellType.ORDINARY && content.levelMask.level == 0)
            require(slice.loadRef().hash() == expectedWalletCode.hash()) { "Master wallet code mismatch" }
            require(slice.remainingBits == 0 && slice.refsPosition == slice.refs.size) { "Trailing Token master storage" }
            return TokenMasterState(supply, content)
        }
    }
}
