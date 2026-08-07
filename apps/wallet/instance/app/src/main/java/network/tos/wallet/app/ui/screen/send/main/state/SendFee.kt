package network.tos.wallet.app.ui.screen.send.main.state

import network.tos.icu.Coins
import network.tos.icu.CurrencyFormatter
import network.tos.wallet.app.core.Fee
import network.tos.wallet.app.extensions.symbol
import network.tos.wallet.data.core.currency.WalletCurrency
import org.ton.block.AddrStd

sealed class SendFee {

    interface TokenFee {
        val amount: Fee
        val fiatAmount: Coins
        val fiatCurrency: WalletCurrency
    }

    interface Extra {
        val extra: Long
    }

    interface RelayerFee {
        val excessesAddress: AddrStd
    }

    data class Ton(
        override val amount: Fee,
        override val fiatAmount: Coins,
        override val fiatCurrency: WalletCurrency,
        val error: Throwable? = null
    ) : SendFee(), TokenFee

    data class Gasless(
        override val amount: Fee,
        override val fiatAmount: Coins,
        override val fiatCurrency: WalletCurrency,
        override val excessesAddress: AddrStd
    ) : SendFee(), TokenFee, RelayerFee

    data class Battery(
        val charges: Int,
        val chargesBalance: Int,
        override val extra: Long,
        override val excessesAddress: AddrStd
    ) : SendFee(), RelayerFee, Extra

}