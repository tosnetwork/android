package network.tos.wallet.app.ui.screen.send.main

import network.tos.icu.Coins
import network.tos.wallet.app.core.Amount
import network.tos.wallet.app.core.Fee
import network.tos.wallet.app.ui.screen.send.main.helper.InsufficientBalanceType
import network.tos.wallet.app.ui.screen.send.main.state.SendFee
import network.tos.wallet.api.entity.TokenEntity
import network.tos.wallet.data.core.currency.WalletCurrency

sealed class SendEvent {
    data class Failed(val throwable: Throwable): SendEvent()
    data object Canceled: SendEvent()
    data object Success: SendEvent()
    data object Loading: SendEvent()

    data class InsufficientBalance(
        val balance: Amount,
        val required: Amount,
        val withRechargeBattery: Boolean,
        val singleWallet: Boolean,
        val type: InsufficientBalanceType
    )

    data object Confirm: SendEvent()

    data class Fee(
        val fee: SendFee = SendFee.Ton(
            amount = Fee(0L),
            fiatAmount = Coins.ZERO,
            fiatCurrency = WalletCurrency.DEFAULT
        ),
        val format: CharSequence = "",
        val convertedFormat: CharSequence = "",
        val showToggle: Boolean = false,
        val insufficientFunds: Boolean = false,
        val failed: Boolean,
    )

    data object ResetAddress: SendEvent()
}