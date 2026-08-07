package network.tos.wallet.app.ui.screen.token.viewer.list.holder

import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import network.tos.wallet.app.ui.screen.battery.BatteryScreen
import network.tos.wallet.app.ui.screen.token.viewer.list.Item
import network.tos.wallet.app.R
import network.tos.wallet.localization.Localization
import uikit.navigation.Navigation

class BatteryBannerHolder(parent: ViewGroup): Holder<Item.BatteryBanner>(parent, R.layout.view_token_battery_banner) {

    private val buttonView = findViewById<View>(R.id.button)
    private val titleView = findViewById<AppCompatTextView>(R.id.title)

    override fun onBind(item: Item.BatteryBanner) {
        val networkName = if (item.token.isTrc20) {
            getString(Localization.trc20)
        } else {
            getString(Localization.ton)
        }
        val tokenName = item.token.symbol.plus(" $networkName")
        titleView.text = context.getString(Localization.battery_required_title, tokenName)

        buttonView.setOnClickListener {
            Navigation.from(context)?.add(BatteryScreen.newInstance(wallet = item.wallet, from = "battery_banner"))
        }
    }

}