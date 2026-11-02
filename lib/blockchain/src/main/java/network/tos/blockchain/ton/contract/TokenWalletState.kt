package network.tos.blockchain.ton.contract

import java.math.BigInteger
import network.tos.blockchain.ton.extensions.loadAddress
import network.tos.blockchain.ton.extensions.storeAddress
import org.ton.block.AddrStd
import org.ton.cell.Cell
import org.ton.cell.CellType
import org.ton.cell.buildCell

/** Standard Token wallet storage codec, not a proof or a signing capability. */
class TokenWalletState private constructor(val balance: BigInteger, val owner: AddrStd, val master: AddrStd) {
    companion object {
        private fun ordinary(cell: Cell) {
            require(cell.type == CellType.ORDINARY && cell.levelMask.level == 0)
        }
        private fun basechain(address: AddrStd) {
            require(address.workchainId == 0 && address.anycast.value == null)
        }

        fun address(owner: AddrStd, master: AddrStd, walletCode: Cell): AddrStd {
            basechain(owner); basechain(master); ordinary(walletCode)
            val data = buildCell { storeUInt(0, 4); storeAddress(owner); storeAddress(master); storeRef(walletCode) }
            return AddrStd(0, TosPqWallet.stateInit(walletCode, data).hash())
        }

        /** Call only with data obtained from an authenticated account whose code
         * matches a locally supported Token implementation. The index is a hint. */
        fun decode(data: Cell, wallet: AddrStd, owner: AddrStd, master: AddrStd, walletCode: Cell): TokenWalletState {
            ordinary(data); ordinary(walletCode); basechain(wallet); basechain(owner); basechain(master)
            require(wallet == address(owner, master, walletCode)) { "Token wallet address mismatch" }
            val slice = data.beginParse()
            val bytes = slice.loadUInt(4).toInt()
            val balance = if (bytes == 0) BigInteger.ZERO else BigInteger(1, slice.loadBits(bytes * 8).toByteArray())
            require(slice.loadAddress() == owner && slice.loadAddress() == master) { "Token ownership mismatch" }
            require(slice.loadRef().hash() == walletCode.hash()) { "Token wallet code reference mismatch" }
            require(slice.remainingBits == 0 && slice.refsPosition == slice.refs.size) { "Trailing Token wallet storage" }
            return TokenWalletState(balance, owner, master)
        }
    }
}
