package network.tos.wallet.app.ui.screen.battery.refill.list.holder

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.wallet.app.ui.screen.battery.recharge.BatteryRechargeScreen
import network.tos.wallet.app.ui.screen.battery.refill.list.Item
import network.tos.wallet.app.R
import network.tos.wallet.localization.Localization
import uikit.extensions.drawable
import uikit.widget.AsyncImageView

class RechargeMethodHolder(
    parent: ViewGroup,
): Holder<Item.RechargeMethod>(parent, R.layout.view_cell_recharge_method) {

    private val asyncImageView = itemView.findViewById<AsyncImageView>(R.id.icon)
    private val titleView = itemView.findViewById<AppCompatTextView>(R.id.title)

    override fun onBind(item: Item.RechargeMethod) {
        itemView.background = item.position.drawable(context)
        itemView.setOnClickListener { navigation?.add(BatteryRechargeScreen.newInstance(item.wallet, item.token)) }
        asyncImageView.setImageURI(item.imageUri, this)
        asyncImageView.visibility = View.VISIBLE
        titleView.text = context.getString(Localization.battery_refill_crypto, item.symbol)
    }
}