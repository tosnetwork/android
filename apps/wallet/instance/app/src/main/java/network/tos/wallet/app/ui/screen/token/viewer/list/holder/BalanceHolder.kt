package network.tos.wallet.app.ui.screen.token.viewer.list.holder

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.icu.CurrencyFormatter.withCustomSymbol
import network.tos.wallet.app.ui.screen.token.viewer.list.Item
import network.tos.wallet.app.R
import network.tos.wallet.data.core.HIDDEN_BALANCE
import uikit.widget.AsyncImageView

class BalanceHolder(parent: ViewGroup): Holder<Item.Balance>(parent, R.layout.view_token_balance) {

    private val balanceView = findViewById<AppCompatTextView>(R.id.balance)
    private val fiatBalanceView = findViewById<AppCompatTextView>(R.id.fiat_balance)
    private val iconView = findViewById<AsyncImageView>(R.id.icon)
    private val networkIconView = findViewById<AsyncImageView>(R.id.network_icon)

    override fun onBind(item: Item.Balance) {
        balanceView.text = if (item.hiddenBalance) HIDDEN_BALANCE else item.balance.withCustomSymbol(context)
        fiatBalanceView.text = if (item.hiddenBalance) HIDDEN_BALANCE else item.fiat.withCustomSymbol(context)
        iconView.setImageURI(item.iconUri)
        networkIconView.setLocalRes(item.networkIconRes)
        networkIconView.visibility = if (item.showNetwork) View.VISIBLE else View.GONE

    }
}