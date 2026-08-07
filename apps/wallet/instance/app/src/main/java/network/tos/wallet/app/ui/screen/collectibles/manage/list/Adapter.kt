package network.tos.wallet.app.ui.screen.collectibles.manage.list

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.collectibles.manage.list.holder.AllHolder
import network.tos.wallet.app.ui.screen.collectibles.manage.list.holder.CollectionHolder
import network.tos.wallet.app.ui.screen.collectibles.manage.list.holder.FooterHolder
import network.tos.wallet.app.ui.screen.collectibles.manage.list.holder.SpaceHolder
import network.tos.wallet.app.ui.screen.collectibles.manage.list.holder.TitleHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

class Adapter(
    private val onClick: (Item.Collection) -> Unit,
    private val showAllClick: () -> Unit,
): BaseListAdapter() {

    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when(viewType) {
            Item.TYPE_TITLE -> TitleHolder(parent)
            Item.TYPE_SPACE -> SpaceHolder(parent)
            Item.TYPE_ALL -> AllHolder(parent, showAllClick)
            Item.TYPE_COLLECTION -> CollectionHolder(parent, onClick)
            Item.TYPE_SAFE_MODE -> FooterHolder(parent)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

}