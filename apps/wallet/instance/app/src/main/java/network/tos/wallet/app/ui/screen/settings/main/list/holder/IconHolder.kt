package network.tos.wallet.app.ui.screen.settings.main.list.holder

import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.view.ViewGroup
import network.tos.wallet.app.extensions.getTitle
import network.tos.wallet.app.ui.screen.settings.main.list.Item
import network.tos.uikit.color.accentBlueColor
import network.tos.uikit.color.iconSecondaryColor
import uikit.extensions.drawable
import uikit.widget.item.ItemIconView

class IconHolder(
    parent: ViewGroup,
    onClick: ((Item) -> Unit)
): Holder<Item.Icon>(ItemIconView(parent.context), onClick) {

    private val itemIconView = itemView as ItemIconView

    override fun onBind(item: Item.Icon) {
        itemIconView.background = item.position.drawable(context)
        itemIconView.setOnClickListener { onClick.invoke(item) }
        if (item.secondaryIcon) {
            itemIconView.setIconTintColor(context.iconSecondaryColor)
        } else {
            itemIconView.setIconTintColor(context.accentBlueColor)
        }
        if (item is Item.Logout) {
            val builder = SpannableStringBuilder(getString(item.titleRes))
            builder.append(" ")
            builder.append(item.label.getTitle(context, itemIconView.textView))
            itemIconView.text = builder
        } else {
            itemIconView.text = getString(item.titleRes)
        }
        itemIconView.iconRes = item.iconRes
        itemIconView.dot = item.dot
    }
}