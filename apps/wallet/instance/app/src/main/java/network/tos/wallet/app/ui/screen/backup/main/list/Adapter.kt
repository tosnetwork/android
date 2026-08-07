package network.tos.wallet.app.ui.screen.backup.main.list

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.backup.main.list.holder.AlertHolder
import network.tos.wallet.app.ui.screen.backup.main.list.holder.BackupHolder
import network.tos.wallet.app.ui.screen.backup.main.list.holder.HeaderHolder
import network.tos.wallet.app.ui.screen.backup.main.list.holder.ManualAccentHolder
import network.tos.wallet.app.ui.screen.backup.main.list.holder.ManualHolder
import network.tos.wallet.app.ui.screen.backup.main.list.holder.RecoveryPhraseHolder
import network.tos.wallet.app.ui.screen.backup.main.list.holder.SpaceHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

class Adapter(
    private val onClick: (Item) -> Unit
): BaseListAdapter() {

    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when(viewType) {
            Item.TYPE_HEADER -> HeaderHolder(parent)
            Item.TYPE_BACKUP -> BackupHolder(parent, onClick)
            Item.TYPE_RECOVERY_PHRASE -> RecoveryPhraseHolder(parent, onClick)
            Item.TYPE_SPACE -> SpaceHolder(parent)
            Item.TYPE_MANUAL_BACKUP -> ManualHolder(parent, onClick)
            Item.TYPE_MANUAL_ACCENT_BACKUP -> ManualAccentHolder(parent, onClick)
            Item.TYPE_ALERT -> AlertHolder(parent)
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

}