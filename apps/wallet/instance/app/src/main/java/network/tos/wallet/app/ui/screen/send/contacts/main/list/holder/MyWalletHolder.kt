package network.tos.wallet.app.ui.screen.send.contacts.main.list.holder

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.send.contacts.main.list.Item
import network.tos.uikit.color.iconPrimaryColor
import network.tos.uikit.icon.UIKitIcon
import uikit.extensions.drawable

class MyWalletHolder(
    parent: ViewGroup,
    private val onClick: (Item) -> Unit
): ContactHolder<Item.MyWallet>(parent) {

    init {
        iconView.setImageResource(UIKitIcon.ic_chevron_right_12)
    }

    override fun onBind(item: Item.MyWallet) {
        itemView.setOnClickListener { onClick(item) }
        itemView.background = item.position.drawable(context)

        emojiView.setEmoji(item.emoji, itemView.context.iconPrimaryColor)
        nameView.text = item.name
    }

}