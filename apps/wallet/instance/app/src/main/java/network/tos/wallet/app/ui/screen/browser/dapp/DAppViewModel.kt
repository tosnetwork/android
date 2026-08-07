package network.tos.wallet.app.ui.screen.browser.dapp

import android.app.Application
import android.net.Uri
import network.tos.wallet.app.extensions.getAppFixIcon
import network.tos.wallet.app.extensions.isDarkMode
import network.tos.wallet.app.manager.tonconnect.TonConnectManager
import network.tos.wallet.app.ui.base.InjectedTonConnectScreen
import network.tos.wallet.app.worker.DAppPushToggleWorker
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.browser.BrowserRepository
import network.tos.wallet.data.dapps.DAppsRepository
import network.tos.wallet.data.dapps.entities.AppEntity
import network.tos.wallet.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DAppViewModel(
    app: Application,
    private val wallet: WalletEntity,
    private val tonConnectManager: TonConnectManager,
    override val url: Uri,
    private val dAppsRepository: DAppsRepository,
    private val settingsRepository: SettingsRepository,
    private val browserRepository: BrowserRepository
): InjectedTonConnectScreen.ViewModel(app, wallet, tonConnectManager) {

    val isDarkTheme: Boolean
        get() = settingsRepository.theme.resId == uikit.R.style.Theme_App_Dark || context.isDarkMode

    val installId: String
        get() = settingsRepository.installId

    val country: String
        get() = settingsRepository.country

    fun mute() {
        DAppPushToggleWorker.run(
            context = context,
            wallet = wallet,
            appUrl = url,
            enable = false
        )
    }

    suspend fun getApp(url: Uri): AppEntity = withContext(Dispatchers.IO) {
        dAppsRepository.getAppFixIcon(url, wallet, browserRepository, settingsRepository)
    }
}