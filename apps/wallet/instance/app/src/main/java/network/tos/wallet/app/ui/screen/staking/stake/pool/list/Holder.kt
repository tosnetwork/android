package network.tos.wallet.app.ui.screen.staking.stake.pool.list

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.icu.CurrencyFormatter.withCustomSymbol
import network.tos.wallet.app.R
import network.tos.uikit.color.accentGreenColor
import network.tos.uikit.color.stateList
import network.tos.uikit.list.BaseListHolder
import network.tos.wallet.data.staking.StakingPool
import network.tos.wallet.data.staking.entities.PoolEntity
import uikit.extensions.drawable
import uikit.extensions.withAlpha
import uikit.widget.AsyncImageView
import uikit.widget.RadioView

class Holder(
    parent: ViewGroup,
    private val onClick: (PoolEntity) -> Unit
): BaseListHolder<Item>(parent, R.layout.view_staking_options_pool) {

    private val iconView = findViewById<AsyncImageView>(R.id.icon)
    private val nameView = findViewById<AppCompatTextView>(R.id.name)
    private val maxApyView = findViewById<View>(R.id.max_apy)
    private val descriptionView = findViewById<AppCompatTextView>(R.id.description)
    private val radioView = findViewById<RadioView>(R.id.radio)
    private val arrowView = findViewById<View>(R.id.arrow)

    init {
        arrowView.visibility = View.GONE
        iconView.setCircular()
        radioView.setOnClickListener(null)
    }

    override fun onBind(item: Item) {
        itemView.background = item.position.drawable(context)
        itemView.setOnClickListener {
            onClick(item.pool)
        }

        iconView.setLocalRes(StakingPool.getIcon(item.pool.implementation))
        nameView.text = item.pool.name
        maxApyView.visibility = if (item.maxApy) {
            maxApyView.backgroundTintList = context.accentGreenColor.withAlpha(.16f).stateList
            View.VISIBLE
        } else {
            View.GONE
        }

        radioView.isClickable = false
        radioView.checked = item.selected
        descriptionView.text = item.getDescription(context).withCustomSymbol(context)
    }

}