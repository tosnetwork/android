package network.tos.wallet.app.ui.screen.wallet.main.list

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.wallet.main.list.holder.ActionsHolder
import network.tos.wallet.app.ui.screen.wallet.main.list.holder.AlertHolder
import network.tos.wallet.app.ui.screen.wallet.main.list.holder.ApkHolder
import network.tos.wallet.app.ui.screen.wallet.main.list.holder.BalanceHolder
import network.tos.wallet.app.ui.screen.wallet.main.list.holder.ManageHolder
import network.tos.wallet.app.ui.screen.wallet.main.list.holder.PushHolder
import network.tos.wallet.app.ui.screen.wallet.main.list.holder.ReNewDomainsHolder
import network.tos.wallet.app.ui.screen.wallet.main.list.holder.SetupLinkHolder
import network.tos.wallet.app.ui.screen.wallet.main.list.holder.SetupSwitchHolder
import network.tos.wallet.app.ui.screen.wallet.main.list.holder.SetupTitleHolder
import network.tos.wallet.app.ui.screen.wallet.main.list.holder.SkeletonHolder
import network.tos.wallet.app.ui.screen.wallet.main.list.holder.SpaceHolder
import network.tos.wallet.app.ui.screen.wallet.main.list.holder.StakedHolder
import network.tos.wallet.app.ui.screen.wallet.main.list.holder.TitleHolder
import network.tos.wallet.app.ui.screen.wallet.main.list.holder.TokenHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem
import network.tos.wallet.data.settings.SettingsRepository

class WalletAdapter: BaseListAdapter() {

    override fun createHolder(
        parent: ViewGroup,
        viewType: Int
    ): BaseListHolder<out BaseListItem> {
        return when(viewType) {
            Item.TYPE_BALANCE -> BalanceHolder(parent)
            Item.TYPE_ACTIONS -> ActionsHolder(parent)
            Item.TYPE_TOKEN -> TokenHolder(parent)
            Item.TYPE_SPACE -> SpaceHolder(parent)
            Item.TYPE_SKELETON -> SkeletonHolder(parent)
            Item.TYPE_PUSH -> PushHolder(parent)
            Item.TYPE_TITLE -> TitleHolder(parent)
            Item.TYPE_MANAGE -> ManageHolder(parent)
            Item.TYPE_ALERT -> AlertHolder(parent)
            Item.TYPE_SETUP_TITLE -> SetupTitleHolder(parent)
            Item.TYPE_SETUP_SWITCH -> SetupSwitchHolder(parent)
            Item.TYPE_SETUP_LINK -> SetupLinkHolder(parent)
            Item.TYPE_STAKED -> StakedHolder(parent)
            Item.TYPE_APK_STATUS -> ApkHolder(parent)
            Item.TYPE_RENEW_DOMAINS -> ReNewDomainsHolder(parent)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

}