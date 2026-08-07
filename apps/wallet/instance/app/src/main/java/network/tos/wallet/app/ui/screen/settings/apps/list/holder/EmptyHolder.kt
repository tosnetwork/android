package network.tos.wallet.app.ui.screen.settings.apps.list.holder

import android.view.ViewGroup
import network.tos.wallet.app.ui.screen.settings.apps.list.Item
import network.tos.wallet.app.R

class EmptyHolder(parent: ViewGroup): Holder<Item.Empty>(parent, R.layout.view_settings_app_empty) {
    override fun onBind(item: Item.Empty) {

    }
}