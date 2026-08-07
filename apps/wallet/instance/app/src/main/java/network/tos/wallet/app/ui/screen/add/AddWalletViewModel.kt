package network.tos.wallet.app.ui.screen.add

import android.app.Application
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.screen.add.list.Item
import network.tos.wallet.api.API
import network.tos.wallet.localization.Localization
import kotlinx.coroutines.flow.map

class AddWalletViewModel(
    app: Application,
    private val withNew: Boolean,
    private val api: API,
): BaseWalletVM(app) {

    val uiItems = api.configFlow.map {
        val uiItems = mutableListOf<Item>()
        if (withNew) {
            uiItems.add(Item.header(Localization.add_wallet, Localization.add_wallet_description))
            uiItems.add(Item.new)
        } else {
            uiItems.add(Item.header(Localization.import_wallet, Localization.import_wallet_subtitle))
        }
        uiItems.add(Item.import)
        uiItems.toList()
    }
}
