package network.tos.wallet.app.ui.screen.send.contacts.main.list.holder

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import network.tos.wallet.app.popup.ActionSheet
import network.tos.wallet.app.ui.screen.send.contacts.main.list.Item
import network.tos.wallet.app.R
import network.tos.uikit.icon.UIKitIcon
import network.tos.wallet.localization.Localization
import uikit.extensions.dp
import uikit.extensions.drawable

class LatestHolder(
    parent: ViewGroup,
    private val onClick: (Item) -> Unit,
    private val onAction: (item: Item, actionId: Long) -> Unit
): ContactHolder<Item.LatestContact>(parent) {

    private val actionSheet: ActionSheet by lazy {
        ActionSheet(context)
    }

    init {
        emojiView.setEmoji("\uD83D\uDD57", Color.TRANSPARENT)
        iconView.setImageResource(R.drawable.ic_ellipsis_16)
    }

    override fun onBind(item: Item.LatestContact) {
        itemView.setOnClickListener {
            onClick(item)
        }
        itemView.background = item.position.drawable(context)

        nameView.text = item.name
        iconView.setOnClickListener { showMenu(item) }
    }

    private fun showMenu(item: Item.LatestContact) {
        if (actionSheet.isEmpty) {
            actionSheet.addItem(ADD_TO_CONTACTS_ID, Localization.contacts_save_latest, UIKitIcon.ic_plus_alternate_16)
            actionSheet.addItem(HIDE_ID, Localization.hide, UIKitIcon.ic_block_16)
        }
        actionSheet.doOnItemClick = { i ->
            onAction(item, i.id)
        }
        actionSheet.show(iconView, gravity = Gravity.END)
    }
}