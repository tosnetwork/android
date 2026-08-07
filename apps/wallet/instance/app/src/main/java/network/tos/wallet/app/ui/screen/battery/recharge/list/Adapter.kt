package network.tos.wallet.app.ui.screen.battery.recharge.list

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import network.tos.wallet.app.ui.screen.battery.recharge.entity.RechargePackType
import network.tos.wallet.app.ui.screen.battery.recharge.list.holder.AddressHolder
import network.tos.wallet.app.ui.screen.battery.recharge.list.holder.AmountHolder
import network.tos.wallet.app.ui.screen.battery.recharge.list.holder.ButtonHolder
import network.tos.wallet.app.ui.screen.battery.recharge.list.holder.CustomAmountHolder
import network.tos.wallet.app.ui.screen.battery.recharge.list.holder.InputHolder
import network.tos.wallet.app.ui.screen.battery.recharge.list.holder.PromoHolder
import network.tos.wallet.app.ui.screen.battery.recharge.list.holder.RechargePackHolder
import network.tos.wallet.app.ui.screen.battery.recharge.list.holder.SpaceHolder
import network.tos.uikit.list.BaseListAdapter
import network.tos.uikit.list.BaseListHolder
import network.tos.uikit.list.BaseListItem
import uikit.extensions.hideKeyboard

class Adapter(
    private val onAddressChange: (String) -> Unit,
    private val openAddressBook: () -> Unit,
    private val onAmountChange: (Double) -> Unit,
    private val onPackSelect: (RechargePackType) -> Unit,
    private val onCustomAmountSelect: () -> Unit,
    private val onContinue: () -> Unit,
    private val onSubmitPromo: (String) -> Unit,
): BaseListAdapter() {

    override fun createHolder(parent: ViewGroup, viewType: Int): BaseListHolder<out BaseListItem> {
        return when(viewType) {
            Item.TYPE_RECHARGE_PACK -> RechargePackHolder(parent, onPackSelect)
            Item.TYPE_CUSTOM_AMOUNT -> CustomAmountHolder(parent, onCustomAmountSelect)
            Item.TYPE_SPACE -> SpaceHolder(parent)
            Item.TYPE_AMOUNT -> AmountHolder(parent, onAmountChange)
            Item.TYPE_ADDRESS -> AddressHolder(parent, onAddressChange, openAddressBook)
            Item.TYPE_BUTTON -> ButtonHolder(parent, onContinue)
            Item.TYPE_PROMO -> PromoHolder(parent, onSubmitPromo)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onViewDetachedFromWindow(holder: BaseListHolder<out BaseListItem>) {
        super.onViewDetachedFromWindow(holder)
        if (holder is InputHolder) {
            holder.inputFieldView.hideKeyboard(false)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recyclerView.isNestedScrollingEnabled = true
    }
}