package network.tos.wallet.app.ui.screen.backup.main.list.holder

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.backup.main.list.Item
import network.tos.uikit.icon.UIKitIcon
import uikit.widget.item.ItemIconView

class EncryptedBackupHolder(parent: ViewGroup, private val onClick: (Item) -> Unit) :
    Holder<Item.EncryptedBackup>(ItemIconView(parent.context)) {
    init {
        (itemView as ItemIconView).apply {
            iconRes = UIKitIcon.ic_key_28
            text = "Export encrypted backup"
            contentDescription = "quantum.backup.export"
        }
    }
    override fun onBind(item: Item.EncryptedBackup) { itemView.setOnClickListener { onClick(item) } }
}
