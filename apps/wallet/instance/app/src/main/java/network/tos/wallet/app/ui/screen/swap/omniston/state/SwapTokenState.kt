package network.tos.wallet.app.ui.screen.swap.omniston.state

import network.tos.icu.Coins
import network.tos.icu.CurrencyFormatter
import network.tos.wallet.app.core.entities.AssetsEntity
import network.tos.wallet.api.entity.BalanceEntity

data class SwapTokenState(
    val fromToken: AssetsEntity.Token? = null,
    val remaining: Coins = Coins.ZERO,
) {

    val tokenBalance: BalanceEntity?
        get() = fromToken?.token?.balance

    val balance: Coins
        get() = tokenBalance?.value ?: Coins.ZERO

    val insufficientBalance: Boolean
        get() = remaining.isNegative

    val isTon: Boolean
        get() = fromToken?.token?.isTon == true

    val remainingFormat: CharSequence? by lazy {
        CurrencyFormatter.format("", remaining)
    }
}