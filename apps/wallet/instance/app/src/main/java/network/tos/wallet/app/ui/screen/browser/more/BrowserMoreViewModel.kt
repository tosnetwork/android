package network.tos.wallet.app.ui.screen.browser.more

import android.app.Application
import androidx.lifecycle.viewModelScope
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.screen.browser.more.list.Item
import network.tos.uikit.list.ListCell
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.browser.BrowserRepository
import network.tos.wallet.data.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class BrowserMoreViewModel(
    application: Application,
    private val wallet: WalletEntity,
    private val id: String,
    private val browserRepository: BrowserRepository,
    private val settingsRepository: SettingsRepository
): BaseWalletVM(application) {

    private val flow = browserRepository.dataFlow(
        country = settingsRepository.country,
        testnet = wallet.testnet,
        locale = settingsRepository.getLocale()
    ).map { it.categories }.map { categories ->
        categories.first { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null).filterNotNull()

    val titleFlow = flow.map { it.title }

    val uiItemsFlow = flow.map { it.apps }.map { apps ->
        val uiItems = mutableListOf<Item>()
        for ((index, app) in apps.withIndex()) {
            val position = ListCell.getPosition(apps.size, index)
            uiItems.add(Item(
                wallet = wallet,
                app = app,
                position = position,
                country = settingsRepository.country
            ))
        }
        uiItems.toList()
    }
}
