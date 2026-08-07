package network.tos.wallet.app.ui.screen.settings.currency.list

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import network.tos.uikit.icon.UIKitIcon
import network.tos.uikit.list.BaseListHolder
import uikit.widget.item.ItemIconView

class Holder(
    parent: ViewGroup,
    private val onClick: (currency: String) -> Unit
): BaseListHolder<Item>(ItemIconView(parent.context)) {

    private val itemIconView = itemView as ItemIconView

    init {
        itemIconView.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onBind(item: Item) {
        itemIconView.setOnClickListener { onClick(item.currency) }
        itemIconView.position = item.position
        itemIconView.text = item.currency
        itemIconView.description = item.name

        if (item.selected) {
            itemIconView.iconRes = UIKitIcon.ic_donemark_thin_28
        } else {
            itemIconView.iconRes = 0
        }
    }

}