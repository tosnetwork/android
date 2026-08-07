package network.tos.wallet.app.ui.screen.staking.stake.options.list

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import network.tos.wallet.app.ui.screen.staking.stake.options.list.holder.PoolHolder
import network.tos.wallet.app.ui.screen.staking.stake.options.list.holder.SpaceHolder
import network.tos.wallet.app.ui.screen.staking.stake.options.list.holder.TitleHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem
import network.tos.wallet.data.staking.entities.PoolEntity
import network.tos.wallet.data.staking.entities.PoolInfoEntity

class Adapter(
    private val onClick: (PoolInfoEntity) -> Unit
): BaseListAdapter() {
    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when(viewType) {
            Item.TYPE_TITLE -> TitleHolder(parent)
            Item.TYPE_SPACE -> SpaceHolder(parent)
            Item.TYPE_POOL -> PoolHolder(parent, onClick)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.isNestedScrollingEnabled = true
    }
}