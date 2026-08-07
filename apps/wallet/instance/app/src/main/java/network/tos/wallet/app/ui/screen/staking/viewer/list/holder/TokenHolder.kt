package network.tos.wallet.app.ui.screen.staking.viewer.list.holder

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.icu.CurrencyFormatter.withCustomSymbol
import network.tos.wallet.app.extensions.buildRateString
import network.tos.wallet.app.ui.screen.staking.viewer.list.Item
import network.tos.wallet.app.ui.screen.token.viewer.TokenScreen
import network.tos.wallet.app.R
import network.tos.uikit.color.accentOrangeColor
import network.tos.uikit.color.textSecondaryColor
import network.tos.uikit.list.ListCell
import network.tos.wallet.data.core.HIDDEN_BALANCE
import network.tos.wallet.localization.Localization
import uikit.extensions.drawable
import uikit.navigation.Navigation
import uikit.widget.AsyncImageView

class TokenHolder(parent: ViewGroup): Holder<Item.Token>(parent, R.layout.view_cell_jetton) {

    private val iconView = findViewById<AsyncImageView>(R.id.icon)
    private val titleView = findViewById<AppCompatTextView>(R.id.title)
    private val rateView = findViewById<AppCompatTextView>(R.id.rate)
    private val balanceView = findViewById<AppCompatTextView>(R.id.balance)
    private val balanceFiatView = findViewById<AppCompatTextView>(R.id.balance_currency)

    init {
        itemView.background = ListCell.Position.SINGLE.drawable(context)
    }

    override fun onBind(item: Item.Token) {
        itemView.setOnClickListener {
            if (item.balance.isPositive) {
                Navigation.from(context)?.add(
                    TokenScreen.newInstance(
                        item.wallet,
                        item.address,
                        item.name,
                        item.symbol
                    )
                )
            }
        }

        if (item.blacklist) {
            titleView.text = getString(Localization.fake)
            iconView.clear(null)
        } else {
            titleView.text = item.symbol
            iconView.setImageURI(item.iconUri, this)
        }

        balanceView.text = if (item.hiddenBalance) {
            HIDDEN_BALANCE
        } else {
            item.balanceFormat.withCustomSymbol(context)
        }

        if (item.testnet) {
            rateView.visibility = View.GONE
            balanceFiatView.visibility = View.GONE
        } else {
            balanceFiatView.visibility = View.VISIBLE
            if (item.hiddenBalance) {
                balanceFiatView.text = HIDDEN_BALANCE
            } else {
                balanceFiatView.text = item.fiatFormat.withCustomSymbol(context)
            }
            setRate(item.rate, item.rateDiff24h, item.verified)
        }
    }

    private fun setRate(rate: CharSequence, rateDiff24h: String, verified: Boolean) {
        rateView.visibility = View.VISIBLE
        if (verified) {
            rateView.text = context.buildRateString(rate, rateDiff24h).withCustomSymbol(context)
            rateView.setTextColor(context.textSecondaryColor)
        } else {
            rateView.setText(Localization.unverified_token)
            rateView.setTextColor(context.accentOrangeColor)
        }
    }

}