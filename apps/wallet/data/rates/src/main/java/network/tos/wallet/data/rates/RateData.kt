package network.tos.wallet.data.rates

import network.tos.icu.Coins
import network.tos.wallet.data.core.currency.WalletCurrency

data class RateData(
    val from: WalletCurrency,
    val to: WalletCurrency,
    val rate: Coins
) {

    fun convert(amount: Coins) = rate.multiply(amount)

    fun convertReverse(amount: Coins) = amount.divide(rate)

    fun convert(from: WalletCurrency, to: WalletCurrency, amount: Coins): Coins {
        if (this.from == to || this.to == from) {
            return convertReverse(amount)
        }
        return convert(amount)
    }

    override fun toString(): String {
        return "RateData(1 ${from.code} = ${rate.value.toPlainString()} ${to.code})"
    }
}