package network.tos.wallet.app.ui.screen.battery.recharge.list.holder

import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.wallet.app.ui.screen.battery.recharge.list.Item
import network.tos.wallet.app.R
import network.tos.wallet.localization.Localization
import uikit.extensions.drawable
import uikit.widget.RadioView

class CustomAmountHolder(
    parent: ViewGroup,
    private val onCustomAmountSelect: () -> Unit,
): Holder<Item.CustomAmount>(parent, R.layout.view_cell_recharge_pack) {

    private val titleView = itemView.findViewById<AppCompatTextView>(R.id.title)
    private val subtitleView = itemView.findViewById<AppCompatTextView>(R.id.subtitle)
    private val radioView = itemView.findViewById<RadioView>(R.id.radio)

    override fun onBind(item: Item.CustomAmount) {
        itemView.background = item.position.drawable(context)
        itemView.setOnClickListener { onCustomAmountSelect() }
        titleView.text = getString(Localization.battery_other_title)
        subtitleView.text = getString(Localization.battery_other_subtitle)
        radioView.checked = item.selected
        radioView.setOnClickListener { onCustomAmountSelect() }
    }
}