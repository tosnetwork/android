package network.tos.wallet.app.ui.screen.events.main.filters.holder

import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.wallet.app.ui.screen.events.main.filters.FilterItem
import network.tos.wallet.app.R
import network.tos.uikit.color.buttonPrimaryBackgroundColor
import network.tos.uikit.color.buttonPrimaryForegroundColor
import network.tos.uikit.color.buttonSecondaryBackgroundColor
import network.tos.uikit.color.buttonSecondaryForegroundColor
import network.tos.uikit.color.buttonTertiaryBackgroundColor
import network.tos.uikit.color.stateList
import network.tos.uikit.list.BaseListHolder

abstract class Holder<I: FilterItem>(
    parent: ViewGroup,
): BaseListHolder<I>(parent, R.layout.view_filter_chip) {

    val titleView = itemView.findViewById<AppCompatTextView>(R.id.title)

    fun setSelected(selected: Boolean) {
        val textColor = if (selected) context.buttonPrimaryForegroundColor else context.buttonSecondaryForegroundColor
        val color = if (selected) context.buttonTertiaryBackgroundColor else context.buttonSecondaryBackgroundColor
        itemView.backgroundTintList = color.stateList
        titleView.setTextColor(textColor)
    }
}