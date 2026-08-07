package network.tos.wallet.app.core

import network.tos.icu.Coins
import network.tos.wallet.api.entity.TokenEntity

data class Amount(
    val value: Coins = Coins.ZERO,
    val token: TokenEntity = TokenEntity.TON
) {

    val isTon: Boolean
        get() = token.isTon

    val symbol: String
        get() = token.symbol

    val decimals: Int
        get() = token.decimals
}