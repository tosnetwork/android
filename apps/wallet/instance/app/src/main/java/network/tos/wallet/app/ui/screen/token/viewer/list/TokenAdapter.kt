package network.tos.wallet.app.ui.screen.token.viewer.list

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.token.viewer.list.holder.AboutEthenaHolder
import network.tos.wallet.app.ui.screen.token.viewer.list.holder.ActionsHolder
import network.tos.wallet.app.ui.screen.token.viewer.list.holder.BalanceHolder
import network.tos.wallet.app.ui.screen.token.viewer.list.holder.BatteryBannerHolder
import network.tos.wallet.app.ui.screen.token.viewer.list.holder.ChartHolder
import network.tos.wallet.app.ui.screen.token.viewer.list.holder.EthenaBalanceHolder
import network.tos.wallet.app.ui.screen.token.viewer.list.holder.EthenaMethodHolder
import network.tos.wallet.app.ui.screen.token.viewer.list.holder.SpaceHolder
import network.tos.wallet.app.ui.screen.token.viewer.list.holder.W5BannerHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem
import network.tos.wallet.data.settings.ChartPeriod

class TokenAdapter(
    private val chartPeriodCallback: (ChartPeriod) -> Unit,
): BaseListAdapter() {
    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when(viewType) {
            Item.TYPE_BALANCE -> BalanceHolder(parent)
            Item.TYPE_ACTIONS -> ActionsHolder(parent)
            Item.TYPE_CHART -> ChartHolder(parent, chartPeriodCallback)
            Item.TYPE_W5_BANNER -> W5BannerHolder(parent)
            Item.TYPE_BATTERY_BANNER -> BatteryBannerHolder(parent)
            Item.TYPE_ABOUT_ETHENA -> AboutEthenaHolder(parent)
            Item.TYPE_SPACE -> SpaceHolder(parent)
            Item.TYPE_ETHENA_BALANCE -> EthenaBalanceHolder(parent)
            Item.TYPE_ETHENA_METHOD -> EthenaMethodHolder(parent)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }
}