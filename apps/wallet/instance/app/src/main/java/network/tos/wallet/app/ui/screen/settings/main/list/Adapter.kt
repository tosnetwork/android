package network.tos.wallet.app.ui.screen.settings.main.list

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.settings.main.list.holder.AccountHolder
import network.tos.wallet.app.ui.screen.settings.main.list.holder.IconHolder
import network.tos.wallet.app.ui.screen.settings.main.list.holder.LogoHolder
import network.tos.wallet.app.ui.screen.settings.main.list.holder.SpaceHolder
import network.tos.wallet.app.ui.screen.settings.main.list.holder.TextHolder
import network.tos.wallet.app.ui.screen.settings.main.list.holder.TronHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

class Adapter(
    private val onClick: ((Item) -> Unit)
): BaseListAdapter() {

    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when(viewType) {
            Item.TYPE_ACCOUNT -> AccountHolder(parent, onClick)
            Item.TYPE_SPACE -> SpaceHolder(parent, onClick)
            Item.TYPE_TEXT -> TextHolder(parent, onClick)
            Item.TYPE_ICON -> IconHolder(parent, onClick)
            Item.TYPE_LOGO -> LogoHolder(parent, onClick)
            Item.TYPE_TRON -> TronHolder(parent, onClick)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

}