package network.tos.signer.screen.sign.list

import android.view.ViewGroup
import network.tos.signer.screen.sign.list.holder.SignSendHolder
import network.tos.signer.screen.sign.list.holder.SignUnknownHolder

class SignAdapter: network.tos.uikit.list.BaseListAdapter() {
    override fun createHolder(parent: ViewGroup, viewType: Int): network.tos.uikit.list.BaseListHolder<out network.tos.uikit.list.BaseListItem> {
        return when(viewType) {
            SignItem.UNKNOWN -> SignUnknownHolder(parent)
            SignItem.SEND -> SignSendHolder(parent)
            else -> throw IllegalArgumentException("Unknown view type $viewType")
        }
    }
}