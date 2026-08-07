package network.tos.wallet.app.ui.screen.staking.viewer.list

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.staking.viewer.list.holder.ActionsHolder
import network.tos.wallet.app.ui.screen.staking.viewer.list.holder.BalanceHolder
import network.tos.wallet.app.ui.screen.staking.viewer.list.holder.DetailsHolder
import network.tos.wallet.app.ui.screen.staking.viewer.list.holder.LinksHolder
import network.tos.wallet.app.ui.screen.staking.viewer.list.holder.SpaceHolder
import network.tos.wallet.app.ui.screen.staking.viewer.list.holder.TokenHolder
import network.tos.wallet.app.ui.screen.staking.viewer.list.holder.DescriptionHolder
import network.tos.wallet.app.ui.screen.staking.viewer.list.holder.EthenaDetailsHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem

class Adapter: BaseListAdapter() {

    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when(viewType) {
            Item.TYPE_BALANCE -> BalanceHolder(parent)
            Item.TYPE_ACTIONS -> ActionsHolder(parent)
            Item.TYPE_DETAILS -> DetailsHolder(parent)
            Item.TYPE_LINKS -> LinksHolder(parent)
            Item.TYPE_TOKEN -> TokenHolder(parent)
            Item.TYPE_SPACE -> SpaceHolder(parent)
            Item.TYPE_DESCRIPTION -> DescriptionHolder(parent)
            Item.TYPE_ETHENA_DETAILS -> EthenaDetailsHolder(parent)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }
}