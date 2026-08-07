package network.tos.wallet.app.ui.screen.battery.refill.list

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import network.tos.wallet.app.ui.screen.battery.refill.list.holder.BatteryHolder
import network.tos.wallet.app.ui.screen.battery.refill.list.holder.GiftHolder
import network.tos.wallet.app.ui.screen.battery.refill.list.holder.IAPPackHolder
import network.tos.wallet.app.ui.screen.battery.refill.list.holder.IAPRestoreHolder
import network.tos.wallet.app.ui.screen.battery.refill.list.holder.PromoHolder
import network.tos.wallet.app.ui.screen.battery.refill.list.holder.RechargeMethodHolder
import network.tos.wallet.app.ui.screen.battery.refill.list.holder.RefundHolder
import network.tos.wallet.app.ui.screen.battery.refill.list.holder.SettingsHolder
import network.tos.wallet.app.ui.screen.battery.refill.list.holder.SpaceHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem
import uikit.extensions.hideKeyboard

class Adapter(
    private val openSettings: () -> Unit,
    private val onSubmitPromo: (String) -> Unit,
    private val onPackSelect: (String) -> Unit,
    private val onRestorePurchases: () -> Unit,
): BaseListAdapter() {

    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when(viewType) {
            Item.TYPE_BATTERY -> BatteryHolder(parent, openSettings)
            Item.TYPE_SPACE -> SpaceHolder(parent)
            Item.TYPE_RECHARGE_METHOD -> RechargeMethodHolder(parent)
            Item.TYPE_GIFT -> GiftHolder(parent)
            Item.TYPE_SETTINGS -> SettingsHolder(parent, openSettings)
            Item.TYPE_REFUND -> RefundHolder(parent)
            Item.TYPE_PROMO -> PromoHolder(parent, onSubmitPromo)
            Item.TYPE_IAP -> IAPPackHolder(parent, onPackSelect)
            Item.TYPE_RESTORE_IAP -> IAPRestoreHolder(parent, onRestorePurchases)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onViewDetachedFromWindow(holder: BaseListHolder<out BaseListItem>) {
        super.onViewDetachedFromWindow(holder)
        if (holder is PromoHolder) {
            holder.context.hideKeyboard()
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.isNestedScrollingEnabled = true
    }
}