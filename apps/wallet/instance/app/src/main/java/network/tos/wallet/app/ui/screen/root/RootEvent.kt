package network.tos.wallet.app.ui.screen.root

import android.net.Uri
import network.tos.icu.Coins
import network.tos.ledger.ton.LedgerConnectData
import network.tos.wallet.app.core.entities.WalletPurchaseMethodEntity
import network.tos.wallet.app.core.history.list.item.HistoryItem
import network.tos.wallet.app.ui.screen.init.list.AccountItem
import network.tos.wallet.api.entity.StoryEntity
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.purchase.entity.PurchaseMethodEntity
import org.ton.api.pub.PublicKeyEd25519
import org.ton.block.StateInit
import org.ton.cell.Cell
import org.ton.tlb.CellRef

sealed class RootEvent {
    data class OpenTab(
        val link: Uri,
        val wallet: WalletEntity,
        val from: String,
    ): RootEvent()

    data class Swap(
        val wallet: WalletEntity,
        val uri: Uri,
        val address: String,
        val from: String,
        val to: String?
    ): RootEvent()

    data class Singer(
        val publicKey: PublicKeyEd25519,
        val name: String?,
        val qr: Boolean
    ): RootEvent()

    data class Ledger(
        val connectData: LedgerConnectData,
        val accounts: List<AccountItem>
    ): RootEvent()

    data class Transfer(
        val wallet: WalletEntity,
        val address: String,
        val amount: Coins?,
        val text: String?,
        val jettonAddress: String?,
        val bin: Cell?,
        val initStateBase64: String?,
        val validUnit: Long?,
    ): RootEvent()

    data object CloseCurrentTonConnect: RootEvent()

    data class OpenDAppByShortcut(
        val wallet: WalletEntity,
        val url: Uri,
        val source: String
    ): RootEvent()
}