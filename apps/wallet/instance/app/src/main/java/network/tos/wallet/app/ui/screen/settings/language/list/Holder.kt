package network.tos.wallet.app.ui.screen.settings.language.list

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import network.tos.wallet.app.extensions.capitalized
import network.tos.uikit.icon.UIKitIcon
import network.tos.uikit.list.BaseListHolder
import network.tos.wallet.localization.Language
import network.tos.wallet.localization.Localization
import uikit.widget.item.ItemIconView

class Holder(
    parent: ViewGroup,
    private val onClick: (item: Item) -> Unit
): BaseListHolder<Item>(ItemIconView(parent.context)) {

    private val itemIconView = itemView as ItemIconView

    init {
        itemIconView.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onBind(item: Item) {
        itemIconView.setOnClickListener { onClick(item) }
        itemIconView.position = item.position
        if (item.code == Language.DEFAULT) {
            itemIconView.text = getString(Localization.system)
            itemIconView.description = ""
        } else {
            itemIconView.text = item.name.capitalized
            itemIconView.description = item.nameLocalized
        }

        if (item.selected) {
            itemIconView.iconRes = UIKitIcon.ic_donemark_thin_28
        } else {
            itemIconView.iconRes = 0
        }
    }
}