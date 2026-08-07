package network.tos.wallet.app.ui.screen.swap.omniston.state

import network.tos.icu.Coins
import network.tos.icu.CurrencyFormatter
import network.tos.wallet.app.core.entities.AssetsEntity
import network.tos.wallet.app.helper.TwinInput
import network.tos.wallet.api.entity.BalanceEntity
import network.tos.wallet.data.core.currency.WalletCurrency

data class SwapInputsState(
    val input: TwinInput.State = TwinInput.State(),
    val fromToken: AssetsEntity.Token? = null
) {

    val tokenBalance: BalanceEntity?
        get() = fromToken?.token?.balance

    val remaining: Coins by lazy {
        // (fromToken?.balance ?: Coins.ZERO) - fromAmount
        Coins.ZERO
    }

    val isFromTON: Boolean
        get() = fromToken?.token?.isTon ?: false

    val isMaxTON: Boolean by lazy {
        val token = fromToken ?: return@lazy false
        // token.token.isTon && fromAmount == token.token.balance.value
        false
    }

    val insufficientBalance: Boolean
        get() = remaining.isNegative



    val fromFormat: CharSequence by lazy {
        // CurrencyFormatter.format(from.code, fromAmount)
        "ss"
    }

    val isEmpty: Boolean
        get() = false // insufficientBalance || !fromAmount.isPositive
}