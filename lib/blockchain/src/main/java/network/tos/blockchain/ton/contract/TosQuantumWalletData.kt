package network.tos.blockchain.ton.contract

import org.ton.cell.Cell
import org.ton.cell.CellType

/** Strict wallet data codec. Call only after authenticated account/code binding;
 * parsing a cell is not evidence of chain state or signing authority.
 */
class TosQuantumWalletData private constructor(
    val seqno: Long, val walletId: Long, val retired: Int, val epoch: ULong,
    val primaryNonce: ULong, val rescueNonce: ULong
) {
    companion object {
        fun parse(data: Cell, birth: TosQuantumGenesis, expectedModule: Cell = birth.moduleInit,
                  expectedMetadata: Cell = birth.metadata): TosQuantumWalletData {
            require(data.type == CellType.ORDINARY && data.levelMask.level == 0) { "Ordinary wallet data required" }
            val s = data.beginParse()
            require(s.remainingBits == 322 && s.refs.size - s.refsPosition == 1) { "Wallet data shape" }
            require(!s.loadBit()) { "Classic authorization enabled" }
            val seqno = s.loadUInt(32).toLong()
            val walletId = s.loadUInt(32).toLong()
            require(s.loadBits(256).toByteArray().all { it == 0.toByte() }) { "Classic key must be inert" }
            require(!s.loadBit()) { "Legacy extensions unsupported" }
            val original = birth.walletData.beginParse()
            original.loadBits(33)
            require(walletId == original.loadUInt(32).toLong()) { "Wallet ID changed" }
            val authCell = s.loadRef()
            require(authCell.type == CellType.ORDINARY && authCell.levelMask.level == 0) { "Ordinary AUTH required" }
            val auth = authCell.beginParse()
            require(auth.remainingBits == 218 && auth.refs.size - auth.refsPosition == 2) { "Wallet AUTH shape" }
            require(auth.loadUInt(8).toInt() == 4 && auth.loadUInt(2).toInt() == 2) { "Wallet requires R2 PQ mode" }
            val retired = auth.loadUInt(16).toInt()
            val epoch = auth.loadUInt(64).toString().toULong()
            val primary = auth.loadUInt(64).toString().toULong()
            val rescue = auth.loadUInt(64).toString().toULong()
            require(auth.loadRef().hash() == expectedModule.hash()) { "Installed module mismatch" }
            require(auth.loadRef().hash() == expectedMetadata.hash()) { "Installed fee descriptor mismatch" }
            return TosQuantumWalletData(seqno, walletId, retired, epoch, primary, rescue)
        }
    }
}
