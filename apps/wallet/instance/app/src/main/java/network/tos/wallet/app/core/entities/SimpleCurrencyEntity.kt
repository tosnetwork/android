package network.tos.wallet.app.core.entities

import network.tos.icu.Coins
import network.tos.wallet.api.entity.BalanceEntity
import network.tos.wallet.api.entity.TokenEntity
import network.tos.wallet.data.core.currency.WalletCurrency
import network.tos.wallet.data.token.entities.AccountTokenEntity

data class SimpleCurrencyEntity(
    val code: String,
    val address: String,
    val decimals: Int,
) {

    constructor(currency: WalletCurrency) : this(
        currency.code,
        currency.code,
        currency.decimals
    )

    constructor(token: TokenEntity) : this(
        token.symbol,
        token.address,
        token.decimals
    )

    constructor(token: BalanceEntity) : this(token.token)

    constructor(token: AccountTokenEntity) : this(token.balance)

    fun coins(value: Double) = Coins.of(value, decimals)
}