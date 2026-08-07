package network.tos.wallet.app.ui.screen.backup.main.list.holder

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.backup.main.list.Item
import network.tos.wallet.app.R

class ManualAccentHolder(
    parent: ViewGroup,
    private val onClick: (Item) -> Unit
): Holder<Item.ManualAccentBackup>(parent, R.layout.view_backup_manual_accent) {

    override fun onBind(item: Item.ManualAccentBackup) {
        itemView.setOnClickListener { onClick(item) }
    }

}