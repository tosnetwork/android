package network.tos.wallet.app.core.entities

import network.tos.blockchain.ton.contract.WalletVersion
import org.ton.block.StateInit
import org.ton.tlb.CellRef

/** Sender deployment belongs to the external envelope, not the recipient. */
internal object TransferStateInit {
    fun forRecipient(
        version: WalletVersion,
        seqno: Int,
        sender: CellRef<StateInit>,
        requestedRecipient: CellRef<StateInit>?,
    ): CellRef<StateInit>? = when {
        version in setOf(WalletVersion.TOSV5R1, WalletVersion.V3R1, WalletVersion.V3R2,
            WalletVersion.V4R1, WalletVersion.V4R2, WalletVersion.V5R1) -> requestedRecipient
        seqno <= 0 -> requestedRecipient ?: sender
        else -> requestedRecipient
    }
}
