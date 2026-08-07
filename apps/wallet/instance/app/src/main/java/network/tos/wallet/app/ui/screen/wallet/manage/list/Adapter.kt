package network.tos.wallet.app.ui.screen.wallet.manage.list

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import network.tos.wallet.app.ui.screen.wallet.manage.list.holder.FooterHolder
import network.tos.wallet.app.ui.screen.wallet.manage.list.holder.Holder
import network.tos.wallet.app.ui.screen.wallet.manage.list.holder.SpaceHolder
import network.tos.wallet.app.ui.screen.wallet.manage.list.holder.TitleHolder
import network.tos.wallet.app.ui.screen.wallet.manage.list.holder.TokenHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem
import network.tos.uikit.list.DiffCallback

class Adapter(
    private val doOnPinChange: (tokenAddress: String, pin: Boolean) -> Unit,
    private val doOnHiddeChange: (tokenAddress: String, hidden: Boolean) -> Unit,
    private val doOnDrag: (holder: TokenHolder) -> Unit,
): RecyclerView.Adapter<Holder<*>>() {

    private var list = listOf<Item>()

    private fun getItem(position: Int) = list[position]

    override fun getItemViewType(position: Int) = list[position].type

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newList: List<Item>) {
        this.list = newList
        notifyDataSetChanged()
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        val newList = list.toMutableList()
        val item = newList.removeAt(fromPosition)
        newList.add(toPosition, item)
        list = newList
        notifyItemMoved(fromPosition, toPosition)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder<*> {
        return when(viewType) {
            Item.TYPE_TITLE -> TitleHolder(parent)
            Item.TYPE_TOKEN -> TokenHolder(parent, doOnPinChange, doOnHiddeChange, doOnDrag)
            Item.TYPE_SPACE -> SpaceHolder(parent)
            Item.TYPE_SAFE_MODE -> FooterHolder(parent)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun getItemCount() = list.size

    override fun onBindViewHolder(holder: Holder<*>, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.setHasFixedSize(true)
        recyclerView.isNestedScrollingEnabled = true
        recyclerView.itemAnimator = null
        recyclerView.layoutAnimation = null
    }
}