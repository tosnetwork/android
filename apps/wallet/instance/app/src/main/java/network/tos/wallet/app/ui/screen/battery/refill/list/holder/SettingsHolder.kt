package network.tos.wallet.app.ui.screen.battery.refill.list.holder

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.wallet.app.ui.screen.battery.refill.list.Item
import network.tos.wallet.app.R
import network.tos.uikit.list.ListCell
import network.tos.wallet.data.settings.BatteryTransaction
import network.tos.wallet.localization.Localization
import uikit.extensions.drawable

class SettingsHolder(
    parent: ViewGroup,
    private val openSettings: () -> Unit
): Holder<Item.Settings>(parent, R.layout.view_battery_settings) {

    private val subtitleView = itemView.findViewById<AppCompatTextView>(R.id.subtitle)

    override fun onBind(item: Item.Settings) {
        itemView.background = ListCell.Position.SINGLE.drawable(context)
        itemView.setOnClickListener { openSettings() }
        if (item.supportedTransactions.isEmpty()) {
            subtitleView.visibility = View.GONE
        } else {
            subtitleView.visibility = View.VISIBLE
            subtitleView.text = context.getString(
                Localization.battery_will_be_paid,
                getSupportedTransactionText(item.supportedTransactions)
            )
        }
    }

    private fun getSupportedTransactionText(supportedTransactions: Array<BatteryTransaction>): String {
        return supportedTransactions.joinToString(", ") {
            context.getString(supportedTransactionMap[it]!!)
        }
    }

    companion object {

        private val supportedTransactionMap = mapOf(
            BatteryTransaction.NFT to Localization.battery_nft,
            BatteryTransaction.SWAP to Localization.battery_swap,
            BatteryTransaction.JETTON to Localization.battery_jetton,
        )
    }

}