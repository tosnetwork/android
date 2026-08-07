package network.tos.wallet.app.ui.screen.backup.main.list.holder

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.backup.main.list.Item
import network.tos.wallet.app.R

class ManualHolder(
    parent: ViewGroup,
    private val onClick: (Item) -> Unit
): Holder<Item.ManualBackup>(parent, R.layout.view_backup_manual) {

    override fun onBind(item: Item.ManualBackup) {
        itemView.setOnClickListener { onClick(item) }
    }

}