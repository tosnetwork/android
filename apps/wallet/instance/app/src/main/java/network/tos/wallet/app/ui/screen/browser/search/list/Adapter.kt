package network.tos.wallet.app.ui.screen.browser.search.list

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.browser.search.list.holder.AppHolder
import network.tos.wallet.app.ui.screen.browser.search.list.holder.LinkHolder
import network.tos.wallet.app.ui.screen.browser.search.list.holder.SearchHolder
import network.tos.wallet.app.ui.screen.browser.search.list.holder.TitleHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

class Adapter(
    private val onClick: (title: String, url: String, iconUrl: String, sendAnalytics: Boolean) -> Unit
): BaseListAdapter() {
    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when(viewType) {
            Item.TYPE_TITLE -> TitleHolder(parent)
            Item.TYPE_SEARCH -> SearchHolder(parent, onClick)
            Item.TYPE_LINK -> LinkHolder(parent, onClick)
            Item.TYPE_APP -> AppHolder(parent, onClick)
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

}