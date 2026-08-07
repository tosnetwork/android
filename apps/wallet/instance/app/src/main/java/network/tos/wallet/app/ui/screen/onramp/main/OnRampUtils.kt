package network.tos.wallet.app.ui.screen.onramp.main

import android.content.Context
import android.text.SpannableStringBuilder
import androidx.core.text.color
import network.tos.uikit.color.textSecondaryColor
import network.tos.wallet.data.core.currency.WalletCurrency
import network.tos.wallet.localization.Localization

object OnRampUtils {

    fun createProviderTitle(context: Context, title: String): CharSequence {
        return SpannableStringBuilder(context.getString(Localization.provider))
            .append(" ")
            .color(context.textSecondaryColor) {
                append(title)
            }
    }

    fun fixSymbol(value: String): String {
        if (value.equals("USD₮", ignoreCase = true)) {
            return "USDT"
        }
        return value
    }
}