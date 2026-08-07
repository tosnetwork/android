package network.tos.wallet.app.ui.screen.collectibles.main.list

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.collectibles.main.list.holder.NftHolder
import network.tos.wallet.app.ui.screen.collectibles.main.list.holder.SkeletonHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

class Adapter: BaseListAdapter() {

    init {
        applySkeleton()
    }

    fun applySkeleton() {
        submitList(listOf(
            Item.Skeleton(),
            Item.Skeleton(),
            Item.Skeleton(),
            Item.Skeleton(),
            Item.Skeleton(),
            Item.Skeleton(),
            Item.Skeleton(),
            Item.Skeleton(),
            Item.Skeleton(),
            Item.Skeleton(),
            Item.Skeleton(),
            Item.Skeleton(),
            Item.Skeleton()
        ))
    }

    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when(viewType) {
            Item.TYPE_NFT -> NftHolder(parent)
            Item.TYPE_SKELETON -> SkeletonHolder(parent)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }
}