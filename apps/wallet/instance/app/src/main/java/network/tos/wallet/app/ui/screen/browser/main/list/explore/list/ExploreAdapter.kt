package network.tos.wallet.app.ui.screen.browser.main.list.explore.list

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import network.tos.wallet.app.ui.screen.browser.main.list.explore.list.holder.ExploreAdsHolder
import network.tos.wallet.app.ui.screen.browser.main.list.explore.list.holder.ExploreAppExploreHolder
import network.tos.wallet.app.ui.screen.browser.main.list.explore.list.holder.ExploreBannersExploreHolder
import network.tos.wallet.app.ui.screen.browser.main.list.explore.list.holder.ExploreTitleExploreHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

class ExploreAdapter(
    private val onMoreClick: (String) -> Unit
): BaseListAdapter() {

    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when(viewType) {
            ExploreItem.TYPE_TITLE -> ExploreTitleExploreHolder(parent, onMoreClick)
            ExploreItem.TYPE_APP -> ExploreAppExploreHolder(parent)
            ExploreItem.TYPE_BANNERS -> ExploreBannersExploreHolder(parent)
            ExploreItem.TYPE_ADS -> ExploreAdsHolder(parent)
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.isNestedScrollingEnabled = true
    }

}