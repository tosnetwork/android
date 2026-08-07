package network.tos.wallet.app.ui.screen.browser.more.list

import android.view.ViewGroup
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

class Adapter: BaseListAdapter() {
    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return Holder(parent)
    }
}