package network.tos.wallet.app.ui.screen.send.transaction

import network.tos.icu.Coins
import network.tos.wallet.app.core.Amount
import network.tos.wallet.app.core.history.HistoryHelper
import network.tos.wallet.app.core.history.list.item.HistoryItem
import network.tos.wallet.app.ui.screen.send.main.helper.InsufficientBalanceType
import network.tos.wallet.app.ui.screen.send.main.state.SendFee
import network.tos.wallet.data.account.entities.WalletEntity

sealed class SendTransactionState {
    data object Loading: SendTransactionState()
    data object Failed: SendTransactionState()
    data object FailedEmulation: SendTransactionState()

    data class InsufficientBalance(
        val wallet: WalletEntity,
        val balance: Amount,
        val required: Amount,
        val withRechargeBattery: Boolean,
        val singleWallet: Boolean,
        val type: InsufficientBalanceType
    ): SendTransactionState()

    data class Details(
        val emulated: HistoryHelper.Details,
        val totalFormat: CharSequence,
        val isDangerous: Boolean,
        val nftCount: Int,
        val failed: Boolean,
        val fee: SendFee,
    ): SendTransactionState() {

        val uiItems: List<HistoryItem>
            get() = emulated.items
    }
}