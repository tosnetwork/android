package network.tos.wallet.app.ui.screen.battery.settings.list.holder

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.battery.settings.list.Item
import network.tos.wallet.app.R

class SettingsHeaderHolder(
    parent: ViewGroup
): Holder<Item.SettingsHeader>(parent, R.layout.view_battery_settings_header) {

    override fun onBind(item: Item.SettingsHeader) {}
}
