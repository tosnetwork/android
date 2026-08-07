package network.tos.wallet.app.manager.tx

import network.tos.blockchain.ton.extensions.cellFromBase64
import network.tos.wallet.data.account.entities.WalletEntity
import org.ton.cell.Cell

data class SendingTransaction(
    val wallet: WalletEntity,
    val boc: Cell,
    val timestamp: Long = System.currentTimeMillis()
) {

    val hash: String = boc.hash().toHex()

    constructor(wallet: WalletEntity, boc: String) : this(
        wallet = wallet,
        boc = boc.cellFromBase64()
    )
}