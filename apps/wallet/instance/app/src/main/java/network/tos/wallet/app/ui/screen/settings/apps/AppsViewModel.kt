package network.tos.wallet.app.ui.screen.settings.apps

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import network.tos.extensions.mapList
import network.tos.extensions.singleValue
import network.tos.wallet.app.manager.tonconnect.TonConnectManager
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.screen.settings.apps.list.Item
import network.tos.uikit.list.ListCell
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.dapps.entities.AppEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch

class AppsViewModel(
    application: Application,
    private val wallet: WalletEntity,
    private val tonConnectManager: TonConnectManager
): BaseWalletVM(application) {

    val uiItemsFlow = tonConnectManager.walletAppsFlow(wallet).map { apps ->
        val uiItems = mutableListOf<Item>()
        if (apps.isNotEmpty()) {
            uiItems.add(Item.DisconnectAll)
            for ((index, app) in apps.withIndex()) {
                val position = ListCell.getPosition(apps.size, index)
                uiItems.add(
                    Item.App(
                        app = app,
                        wallet = wallet,
                        position = position
                    )
                )
            }
        } else {
            uiItems.add(Item.Empty)
        }
        uiItems.toList()
    }

    fun disconnectApp(app: AppEntity) {
        tonConnectManager.disconnect(wallet, app.url)
    }

    fun disconnectAll() {
        tonConnectManager.walletAppsFlow(wallet).take(1).collectFlow { apps ->
            for (app in apps) {
                tonConnectManager.disconnect(wallet, app.url)
            }
        }
    }
}