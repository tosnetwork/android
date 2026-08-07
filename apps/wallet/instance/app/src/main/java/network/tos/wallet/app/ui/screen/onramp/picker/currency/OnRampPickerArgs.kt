package network.tos.wallet.app.ui.screen.onramp.picker.currency

import android.os.Bundle
import network.tos.extensions.getParcelableCompat
import network.tos.wallet.data.core.currency.WalletCurrency
import uikit.base.BaseArgs

class OnRampPickerArgs(
    val currency: WalletCurrency,
    val send: Boolean
): BaseArgs() {

    companion object {
        private const val ARG_CURRENCY = "currency"
        private const val ARG_SEND = "send"
    }

    constructor(bundle: Bundle) : this(
        currency = bundle.getParcelableCompat(ARG_CURRENCY)!!,
        send = bundle.getBoolean(ARG_SEND, false)
    )

    override fun toBundle() = Bundle().apply {
        putParcelable(ARG_CURRENCY, currency)
        putBoolean(ARG_SEND, send)
    }
}