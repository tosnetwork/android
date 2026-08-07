package network.tos.wallet.app.ui.screen.browser.main.list.connected

import android.view.ViewGroup
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem
import network.tos.wallet.data.dapps.entities.AppEntity

class ConnectedAdapter(
    private val onLongClick: (AppEntity) -> Unit
): BaseListAdapter() {
    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return ConnectedAppHolder(parent, onLongClick)
    }
}