package network.tos.wallet.app.ui.screen.card

import android.app.Application
import android.net.Uri
import network.tos.extensions.appVersionName
import network.tos.extensions.filterList
import network.tos.extensions.locale
import network.tos.wallet.app.manager.tonconnect.TonConnectManager
import network.tos.wallet.app.manager.tonconnect.bridge.JsonBuilder
import network.tos.wallet.app.manager.tonconnect.bridge.model.BridgeError
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.base.InjectedTonConnectScreen
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.dapps.entities.AppConnectEntity
import network.tos.wallet.data.settings.SettingsRepository
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.json.JSONObject

class CardViewModel(
    app: Application,
    private val wallet: WalletEntity,
    private val tonConnectManager: TonConnectManager,
    private val settingsRepository: SettingsRepository,
): InjectedTonConnectScreen.ViewModel(app, wallet, tonConnectManager) {

    override val url: Uri by lazy {
        val builder = Uri.parse("https://next.holders.io").buildUpon()
        builder.appendQueryParameter("lang", context.locale.language)
        builder.appendQueryParameter("currency", settingsRepository.currency.code)
        builder.appendQueryParameter("theme", "holders")
        builder.appendQueryParameter("theme-style", if (settingsRepository.theme.light) "light" else "dark")
        builder.appendQueryParameter("utm_source", "tos")
        builder.build()
    }


}