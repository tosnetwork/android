package network.tos.wallet.app.ui.screen.swap.omniston.state

import network.tos.icu.Coins
import network.tos.wallet.app.helper.TwinInput
import network.tos.wallet.api.SwapAssetParam
import network.tos.wallet.data.core.currency.WalletCurrency

data class SwapRequest(
    val type: TwinInput.Type,
    val amount: Coins,
    val from: WalletCurrency,
    val to: WalletCurrency,
) {

    val isEmpty: Boolean
        get() = !amount.isPositive

    val fromParam: SwapAssetParam by lazy {
        val amount = if (type == TwinInput.Type.Send) amount.toNano(from.decimals) else null
        SwapAssetParam(from.address, amount)
    }

    val toParam: SwapAssetParam by lazy {
        val amount = if (type == TwinInput.Type.Receive) amount.toNano(to.decimals) else null
        SwapAssetParam(to.address, amount)
    }
}