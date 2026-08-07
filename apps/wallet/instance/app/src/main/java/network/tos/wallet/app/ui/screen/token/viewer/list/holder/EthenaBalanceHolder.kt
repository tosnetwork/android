package network.tos.wallet.app.ui.screen.token.viewer.list.holder

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.icu.CurrencyFormatter.withCustomSymbol
import network.tos.wallet.app.extensions.buildRateString
import network.tos.wallet.app.ui.screen.staking.viewer.StakeViewerScreen
import network.tos.wallet.app.ui.screen.token.viewer.TokenScreen
import network.tos.wallet.app.ui.screen.token.viewer.list.Item
import network.tos.wallet.app.R
import network.tos.uikit.color.accentOrangeColor
import network.tos.uikit.color.textSecondaryColor
import network.tos.wallet.api.entity.TokenEntity
import network.tos.wallet.data.core.HIDDEN_BALANCE
import network.tos.wallet.localization.Localization
import uikit.extensions.drawable
import uikit.navigation.Navigation.Companion.navigation
import uikit.widget.AsyncImageView

class EthenaBalanceHolder(parent: ViewGroup) :
    Holder<Item.EthenaBalance>(parent, R.layout.view_ethena_balance) {

    private val iconView = findViewById<AsyncImageView>(R.id.icon)
    private val titleView = findViewById<AppCompatTextView>(R.id.title)
    private val apyView = findViewById<AppCompatTextView>(R.id.apy)
    private val balanceView = findViewById<AppCompatTextView>(R.id.balance)
    private val balanceFiatView = findViewById<AppCompatTextView>(R.id.balance_fiat)

    override fun onBind(item: Item.EthenaBalance) {
        itemView.background = item.position.drawable(context)

        titleView.text = if (item.staked) {
            item.title ?: getString(Localization.staked_usde)
        } else {
            TokenEntity.USDE.symbol
        }

        balanceView.text = if (item.hiddenBalance) HIDDEN_BALANCE else item.balanceFormat
        balanceFiatView.text = if (item.hiddenBalance) HIDDEN_BALANCE else item.fiatFormat

        if (item.staked) {
            iconView.visibility = View.VISIBLE
            item.iconRes?.let { iconView.setLocalRes(it) }
            apyView.text = if (item.showApy) {
                item.apyText
            } else {
                getString(Localization.ethena)
            }
        } else {
            iconView.visibility = View.GONE
            if (item.fiatRate != null && item.rateDiff24h != null) {
                setRate(item.fiatRate, item.rateDiff24h, item.verified)
            }
        }

        itemView.setOnClickListener {
            if (item.staked) {
                item.methodType?.let {
                    context.navigation?.add(
                        StakeViewerScreen.newInstance(
                            wallet = item.wallet,
                            ethenaType = it
                        )
                    )
                }
            } else {
                val token = TokenEntity.USDE
                context.navigation?.add(
                    TokenScreen.newInstance(
                        item.wallet,
                        address = token.address,
                        name = token.name,
                        symbol = token.symbol,
                        rawUsde = true,
                    )
                )
            }
        }
    }

    private fun setRate(rate: CharSequence, rateDiff24h: String, verified: Boolean) {
        apyView.visibility = View.VISIBLE
        if (verified) {
            apyView.text = context.buildRateString(rate, rateDiff24h).withCustomSymbol(context)
            apyView.setTextColor(context.textSecondaryColor)
        } else {
            apyView.setText(Localization.unverified_token)
            apyView.setTextColor(context.accentOrangeColor)
        }
    }

}