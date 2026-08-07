package network.tos.wallet.app.ui.screen.battery.refill.list.holder

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import network.tos.wallet.app.ui.screen.backup.main.BackupScreen
import network.tos.wallet.app.ui.screen.battery.recharge.BatteryRechargeScreen
import network.tos.wallet.app.ui.screen.battery.refill.list.Item
import network.tos.wallet.app.R
import network.tos.wallet.localization.Localization
import uikit.extensions.drawable

class GiftHolder(
    parent: ViewGroup,
): Holder<Item.Gift>(parent, R.layout.view_cell_recharge_method) {

    private val giftImageView = itemView.findViewById<AppCompatImageView>(R.id.gift_icon)
    private val titleView = itemView.findViewById<AppCompatTextView>(R.id.title)
    private val subtitleView = itemView.findViewById<AppCompatTextView>(R.id.subtitle)

    override fun onBind(item: Item.Gift) {
        itemView.background = item.position.drawable(context)
        itemView.setOnClickListener { navigation?.add(BatteryRechargeScreen.newInstance(item.wallet, isGift = true)) }
        giftImageView.visibility = View.VISIBLE
        titleView.text = context.getString(Localization.battery_refill_gift)
        subtitleView.text = context.getString(Localization.battery_refill_gift_subtitle)
        subtitleView.visibility = View.VISIBLE
    }
}