package network.tos.wallet.app.ui.screen.wallet.manage

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import network.tos.wallet.app.App
import network.tos.wallet.app.core.entities.AssetsEntity
import network.tos.wallet.app.core.entities.AssetsExtendedEntity
import network.tos.wallet.app.extensions.isSafeModeEnabled
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.screen.wallet.manage.list.Item
import network.tos.uikit.list.ListCell
import network.tos.wallet.api.API
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.settings.SettingsRepository
import network.tos.wallet.data.token.TokenRepository
import network.tos.wallet.localization.Localization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import uikit.extensions.collectFlow

class TokensManageViewModel(
    app: Application,
    private val wallet: WalletEntity,
    private val settingsRepository: SettingsRepository,
    private val tokenRepository: TokenRepository,
    private val api: API,
): BaseWalletVM(app) {

    private val safeMode: Boolean = settingsRepository.isSafeModeEnabled(api)

    private val tokensFlow = settingsRepository.tokenPrefsChangedFlow.map { _ ->
        tokenRepository.mustGet(settingsRepository.currency, wallet.accountId, wallet.testnet).mapNotNull { token ->
            if (safeMode && !token.verified) {
                return@mapNotNull null
            }
            AssetsExtendedEntity(
                raw = AssetsEntity.Token(token),
                prefs = settingsRepository.getTokenPrefs(wallet.id, token.address, token.blacklist),
                accountId = wallet.accountId,
            )
        }.filter { !it.isTon }
    }

    private val _uiItemsFlow = MutableStateFlow<List<Item>>(emptyList())
    val uiItemsFlow = _uiItemsFlow.asStateFlow()

    init {
        tokensFlow.map { tokens ->
            val hiddenBalance = settingsRepository.hiddenBalances
            val pinnedTokens = tokens.filter { it.pinned }.sortedBy {
                it.index
            }
            val otherTokens = tokens.filter { !it.pinned }

            val items = mutableListOf<Item>()
            if (pinnedTokens.isNotEmpty()) {
                items.add(Item.Title(Localization.pinned))
                for ((index, token) in pinnedTokens.withIndex()) {
                    items.add(Item.Token(
                        position = ListCell.getPosition(pinnedTokens.size, index),
                        token = token,
                        hiddenBalance = hiddenBalance
                    ))
                }
            }

            if (otherTokens.isNotEmpty()) {
                items.add(Item.Space)
                items.add(Item.Title(Localization.all_assets, Localization.sorted_by_price))
                for ((index, token) in otherTokens.withIndex()) {
                    items.add(Item.Token(
                        position = ListCell.getPosition(otherTokens.size, index),
                        token = token,
                        hiddenBalance = hiddenBalance
                    ))
                }
            }

            if (safeMode) {
                items.add(Item.SafeMode(wallet))
            }

            items
        }.flowOn(Dispatchers.IO).onEach { _uiItemsFlow.value = it }.launchIn(viewModelScope)
    }

    fun changeOrder(address: String, toIndex: Int) {
        val uiItems = _uiItemsFlow.value.toMutableList()
        val fromIndex = uiItems.indexOfFirst {
            it is Item.Token && it.address == address
        }
        if (fromIndex == -1) {
            return
        }
        val item = uiItems.removeAt(fromIndex)
        uiItems.add(toIndex, item)

        val pinnedItems = uiItems.filterIsInstance<Item.Token>().filter { it.pinned }
        val newPinnedUiItems = pinnedItems.mapIndexed { index, pinnedItem ->
            pinnedItem.copy(position = ListCell.getPosition(pinnedItems.size, index))
        }.toList()
        uiItems.removeAll(pinnedItems)
        uiItems.addAll(1, newPinnedUiItems)

        _uiItemsFlow.value = uiItems

        settingsRepository.setTokensSort(wallet.id, newPinnedUiItems.map {
            it.address
        })
    }

    fun onPinChange(tokenAddress: String, pin: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.setTokenPinned(wallet.id, tokenAddress, pin)
        }
    }

    fun onHiddenChange(tokenAddress: String, hidden: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.setTokenHidden(wallet.id, tokenAddress, hidden)
        }
    }
}