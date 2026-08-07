package network.tos.wallet.app.ui.screen.staking.viewer.list.holder

import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import network.tos.icu.CurrencyFormatter.withCustomSymbol
import network.tos.wallet.app.ui.screen.staking.viewer.list.Item
import network.tos.wallet.app.R
import network.tos.wallet.data.core.HIDDEN_BALANCE
import uikit.widget.AsyncImageView

class BalanceHolder(
    parent: ViewGroup,
): Holder<Item.Balance>(parent, R.layout.view_staking_balance) {

    private val balanceView = findViewById<AppCompatTextView>(R.id.balance)
    private val fiatView = findViewById<AppCompatTextView>(R.id.fiat)
    private val iconView = findViewById<AsyncImageView>(R.id.icon)
    private val currencyIconView = findViewById<AppCompatImageView>(R.id.currency_icon)

    override fun onBind(item: Item.Balance) {
        balanceView.text = if (item.hiddenBalance) HIDDEN_BALANCE else item.balanceFormat.withCustomSymbol(context)
        fiatView.text = if (item.hiddenBalance) HIDDEN_BALANCE else item.fiatFormat.withCustomSymbol(context)
        item.iconRes?.let { iconView.setLocalRes(it) }
        currencyIconView.setImageResource(item.currencyIcon)
    }

}