package network.tos.wallet.app.ui.screen.browser.main

import android.app.Application
import android.graphics.Color
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import network.tos.extensions.mapList
import network.tos.wallet.app.Environment
import network.tos.wallet.app.koin.remoteConfig
import network.tos.wallet.app.manager.tonconnect.TonConnectManager
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.screen.browser.main.list.connected.ConnectedItem
import network.tos.wallet.app.ui.screen.browser.main.list.explore.list.ExploreItem
import network.tos.wallet.app.BuildConfig
import network.tos.wallet.api.API
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.browser.BrowserRepository
import network.tos.wallet.data.browser.entities.BrowserAppEntity
import network.tos.wallet.data.browser.entities.BrowserDataEntity
import network.tos.wallet.data.dapps.entities.AppEntity
import network.tos.wallet.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BrowserMainViewModel(
    app: Application,
    private val wallet: WalletEntity,
    private val settings: SettingsRepository,
    private val api: API,
    private val tonConnectManager: TonConnectManager,
    private val browserRepository: BrowserRepository,
    private val settingsRepository: SettingsRepository,
    private val environment: Environment
): BaseWalletVM(app) {

    val installId: String
        get() = settings.installId

    val uiConnectedItemsFlow = tonConnectManager.walletAppsFlow(wallet).mapList {
        ConnectedItem(wallet, it)
    }

    private val _uiExploreItemsFlow = MutableStateFlow<List<ExploreItem>>(emptyList())
    val uiExploreItemsFlow = _uiExploreItemsFlow.asStateFlow()

    init {
        val isDappsDisable = context.remoteConfig?.isDappsDisable == true

        if (!isDappsDisable) {
            viewModelScope.launch(Dispatchers.IO) {
                val code = environment.country
                val locale = settingsRepository.getLocale()
                _uiExploreItemsFlow.value = emptyList()
                browserRepository.load(code, wallet.testnet, locale)?.let { setData(it) }
                browserRepository.loadRemote(code, wallet.testnet, locale)?.let { setData(it) }
            }
        }
    }

    fun showDisconnect(app: AppEntity) {
        viewModelScope.launch {
            tonConnectManager.showLogoutAppBar(wallet, context, app.url)
        }
    }

    private fun getDebugApps(): List<BrowserAppEntity> {
        val apps = mutableListOf<BrowserAppEntity>()
        apps.add(BrowserAppEntity(
            name = "Mariabit",
            description = "fdsfsd",
            icon = Uri.EMPTY,
            poster = null,
            url = "https://mariabit.github.io/".toUri(),
            textColor = Color.WHITE,
        ))

        return apps.toList()
    }

    private fun setData(data: BrowserDataEntity) {
        val items = mutableListOf<ExploreItem>()
        if (data.apps.isNotEmpty()) {
            items.add(ExploreItem.Banners(data.apps, api.config.featuredPlayInterval, wallet, environment.country))
        }

        var adsItem: ExploreItem.Ads? = null
        for (category in data.categories) {
            if (category.id == "featured") {
                continue
            } else if (category.id == "ads" && category.apps.isNotEmpty()) {
                val ads = category.apps.first()
                if (ads.button != null) {
                    adsItem = ExploreItem.Ads(category.apps.first(), wallet)
                }
                continue
            }

            val isDigitalNomads = category.id == "digital_nomads"
            if (!isDigitalNomads) {
                items.add(ExploreItem.Title(category.title, category.id))
            }

            val apps = mutableListOf<BrowserAppEntity>()
            if (category.apps.size > 4) {
                for (chunk in category.apps.chunked(4)) {
                    if (chunk.size >= 3) {
                        apps.addAll(chunk)
                    }
                }
            } else {
                apps.addAll(category.apps)
            }

            for (app in apps.take(8)) {
                items.add(ExploreItem.App(
                    app = app,
                    wallet = wallet,
                    singleLine = !isDigitalNomads,
                    country = environment.country
                ))
            }
        }

        adsItem?.let {
            items.add(1, it)
        }

        if (BuildConfig.DEBUG) {
            val debugItems = mutableListOf<ExploreItem>()
            debugItems.add(ExploreItem.Title("Testing"))
            for (app in getDebugApps()) {
                debugItems.add(ExploreItem.App(
                    app = app,
                    wallet = wallet,
                    singleLine = false,
                    country = environment.country
                ))
            }
            items.addAll(5, debugItems)
        }

        _uiExploreItemsFlow.value = items.toList()
    }
}