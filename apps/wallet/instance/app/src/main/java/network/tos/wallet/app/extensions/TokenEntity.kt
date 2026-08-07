package network.tos.wallet.app.extensions

import network.tos.wallet.api.entity.value.Blockchain
import network.tos.wallet.api.entity.TokenEntity
import network.tos.wallet.data.core.currency.WalletCurrency
import network.tos.wallet.data.core.currency.WalletCurrency.Chain

val TokenEntity.asCurrency: WalletCurrency
    get() = WalletCurrency(
        code = symbol,
        title = name,
        chain = if (blockchain == Blockchain.TRON) Chain.TRON(address, decimals) else Chain.TON(address, decimals),
        iconUrl = imageUri.toString(),
    )