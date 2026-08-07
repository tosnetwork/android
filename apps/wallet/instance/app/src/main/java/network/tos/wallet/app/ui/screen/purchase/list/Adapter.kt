package network.tos.wallet.app.ui.screen.purchase.list

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import network.tos.wallet.app.ui.screen.purchase.list.holder.MethodHolder
import network.tos.wallet.app.ui.screen.purchase.list.holder.SpaceHolder
import network.tos.wallet.app.ui.screen.purchase.list.holder.TitleHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem
import network.tos.wallet.data.purchase.entity.PurchaseCategoryEntity
import network.tos.wallet.data.purchase.entity.PurchaseMethodEntity

class Adapter(
    private val onClick: (PurchaseMethodEntity, String) -> Unit
): BaseListAdapter() {

    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when(viewType) {
            Item.TYPE_TITLE -> TitleHolder(parent)
            Item.TYPE_METHOD -> MethodHolder(parent, onClick)
            Item.TYPE_SPACE -> SpaceHolder(parent)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.isNestedScrollingEnabled = true
    }
}