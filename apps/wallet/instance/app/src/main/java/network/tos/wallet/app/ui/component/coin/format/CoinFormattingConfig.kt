package network.tos.wallet.app.ui.component.coin.format

import network.tos.icu.CurrencyFormatter

data class CoinFormattingConfig(
    val separator: String = CurrencyFormatter.monetaryDecimalSeparator,
    val decimals: Int = 9
) {

    companion object {
        const val DOT = "."
        const val COMA = ","
        const val SPACE = " "
        const val ZERO = "0"
        const val DOUBLE_ZERO = "00"
    }

    val zeroNanoPrefix = ZERO + separator

    fun isUnsupportedSeparator(value: CharSequence): Boolean {
        return value != separator && (value == COMA || value == SPACE || value == DOT)
    }
}