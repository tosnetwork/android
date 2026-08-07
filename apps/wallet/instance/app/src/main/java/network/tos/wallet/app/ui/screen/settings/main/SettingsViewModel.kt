package network.tos.wallet.app.ui.screen.settings.main

import android.app.Application
import androidx.lifecycle.viewModelScope
import network.tos.blockchain.ton.contract.BaseWalletContract
import network.tos.blockchain.ton.contract.WalletVersion
import network.tos.blockchain.ton.extensions.toAccountId
import network.tos.wallet.app.Environment
import network.tos.wallet.app.core.AnalyticsHelper
import network.tos.wallet.app.core.FirebaseHelper
import network.tos.wallet.app.core.entities.AssetsEntity
import network.tos.wallet.app.core.entities.AssetsExtendedEntity
import network.tos.wallet.app.extensions.capitalized
import network.tos.wallet.app.extensions.isSafeModeEnabled
import network.tos.wallet.app.manager.push.PushManager
import network.tos.wallet.app.manager.tonconnect.TonConnectManager
import network.tos.wallet.app.manager.widget.WidgetManager
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.screen.settings.main.list.Item
import network.tos.wallet.app.worker.PushToggleWorker
import network.tos.uikit.list.ListCell
import network.tos.wallet.api.API
import network.tos.wallet.api.entity.TokenEntity
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.Wallet
import network.tos.wallet.data.backup.BackupRepository
import network.tos.wallet.data.battery.BatteryRepository
import network.tos.wallet.data.core.SearchEngine
import network.tos.wallet.data.core.currency.WalletCurrency
import network.tos.wallet.data.passcode.PasscodeManager
import network.tos.wallet.data.rn.RNLegacy
import network.tos.wallet.data.settings.SettingsRepository
import network.tos.wallet.data.token.TokenRepository
import network.tos.wallet.data.plugins.PluginsRepository
import network.tos.wallet.localization.Language
import network.tos.wallet.localization.Localization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    application: Application,
    private val wallet: WalletEntity,
    private val accountRepository: AccountRepository,
    private val settingsRepository: SettingsRepository,
    private val api: API,
    private val backupRepository: BackupRepository,
    private val tonConnectManager: TonConnectManager,
    private val passcodeManager: PasscodeManager,
    private val rnLegacy: RNLegacy,
    private val environment: Environment,
    private val tokenRepository: TokenRepository,
    private val batteryRepository: BatteryRepository,
    private val pluginsRepository: PluginsRepository,
    private val analytics: AnalyticsHelper
) : BaseWalletVM(application) {

    private val safeMode: Boolean = settingsRepository.isSafeModeEnabled(api)

    private val _uiItemsFlow = MutableStateFlow<List<Item>>(emptyList())
    val uiItemsFlow = _uiItemsFlow.asStateFlow().filter { it.isNotEmpty() }

    val installId: String
        get() = settingsRepository.installId

    private val walletInfoFlow = combine(
        backupRepository.stream,
        accountRepository.selectedWalletFlow
    ) { backups, wallet ->
        val hasBackup = backups.indexOfFirst { it.walletId == wallet.id } > -1
        Pair(hasBackup, wallet)
    }

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
        }.filter { !it.isTon }.sortedBy { it.index }
    }

    init {
        combine(
            settingsRepository.walletPrefsChangedFlow,
            settingsRepository.currencyFlow,
            settingsRepository.languageFlow,
            settingsRepository.searchEngineFlow,
            walletInfoFlow,
        ) { _, currency, language, searchEngine, walletInfo ->
            val (hasBackup, wallet) = walletInfo
            buildUiItems(wallet, currency, language, searchEngine, hasBackup)
        }.launchIn(viewModelScope)
    }

    fun setSearchEngine(searchEngine: SearchEngine?) {
        val engine = searchEngine ?: SearchEngine.GOOGLE
        settingsRepository.searchEngine = engine
        FirebaseHelper.searchEngine(engine.title)
    }

    fun setRpcEndpoint(value: String): String {
        val endpoint = api.setCustomTosRpcEndpoint(value)
        updateRpcItem(endpoint)
        return endpoint
    }

    fun resetRpcEndpoint() {
        api.resetCustomTosRpcEndpoint()
        updateRpcItem(api.tosRpcEndpoint(wallet.testnet))
    }

    private fun updateRpcItem(endpoint: String) {
        _uiItemsFlow.value = _uiItemsFlow.value.map { item ->
            if (item is Item.RpcNode) Item.RpcNode(endpoint, item.position) else item
        }
    }

    fun signOut(callback: () -> Unit) {
        analytics.simpleTrackEvent("delete_wallet")
        viewModelScope.launch(Dispatchers.IO) {
            tonConnectManager.clear(wallet)
            PushToggleWorker.run(context, wallet, PushManager.State.Delete)
            delay(2000)
            withContext(Dispatchers.Main) {
                callback()
            }
            accountRepository.delete(wallet)
        }
    }

    fun createV4R2Wallet() {
        viewModelScope.launch(Dispatchers.IO) {
            val passcode = withContext(Dispatchers.Main) {
                passcodeManager.legacyGetPasscode(context)
            } ?: return@launch

            val newLabel = Wallet.Label(
                accountName = wallet.label.accountName + " V4R2",
                emoji = wallet.label.emoji,
                color = wallet.label.color
            )
            val walletId = AccountRepository.newWalletId()
            val version = WalletVersion.V4R2
            val mnemonic = accountRepository.getMnemonic(wallet.id)?.toList() ?: return@launch
            val walletIds = listOf(walletId)
            val versions = listOf(version)

            rnLegacy.addMnemonics(passcode, walletIds, mnemonic)
            accountRepository.importWallet(
                walletIds, Wallet.NewLabel(
                    names = listOf(newLabel.name),
                    emoji = newLabel.emoji,
                    color = newLabel.color,
                ), mnemonic, versions, wallet.testnet, listOf(false)
            )
            backupRepository.addBackup(walletId)
            accountRepository.setSelectedWallet(walletId)
            finish()
        }
    }

    fun toggleTron() {
        tokensFlow.take(1).collectFlow { tokens ->
            val index = tokens.indexOfFirst { it.isTrc20Usdt }
            val sortAddresses = tokens.filter {
                !it.isTrc20Usdt
            }.map { it.address }.toMutableList()

            if (sortAddresses.isEmpty() && index != -1) {
                sortAddresses.add(TokenEntity.TRON_USDT.address)
            } else if (sortAddresses.size > index && index != -1) {
                sortAddresses.add(index, TokenEntity.TRON_USDT.address)
            } else {
                sortAddresses.add(1, TokenEntity.TRON_USDT.address)
            }

            val tronPrefs = settingsRepository.getTokenPrefs(wallet.id, TokenEntity.TRC20_USDT)
            val isHidden = !tronPrefs.isHidden
            settingsRepository.setTokenHidden(wallet.id, TokenEntity.TRC20_USDT, isHidden)

            FirebaseHelper.trc20Enabled(!isHidden)

            if (!isHidden) {
                settingsRepository.setTokenPinned(wallet.id, TokenEntity.TRC20_USDT , true)
                settingsRepository.setTokensSort(wallet.id, sortAddresses)
            }
        }
    }

    private suspend fun hasW5(): Boolean {
        if (wallet.version == WalletVersion.V5R1) {
            return true
        } else if (wallet.type == Wallet.Type.Watch || wallet.type == Wallet.Type.Lockup || wallet.type == Wallet.Type.Ledger) {
            return true
        }
        val w5Contact = BaseWalletContract.create(wallet.publicKey, "v5r1", wallet.testnet)
        val accountId = w5Contact.address.toAccountId()
        return accountRepository.getWalletByAccountId(accountId, wallet.testnet) != null
    }

    private suspend fun hasV4R2(): Boolean {
        if (wallet.version != WalletVersion.V5R1 && wallet.version != WalletVersion.V5BETA) {
            return true
        }
        if (wallet.type == Wallet.Type.Watch || wallet.type == Wallet.Type.Lockup || wallet.type == Wallet.Type.Ledger) {
            return true
        }
        val v4R2Contact = BaseWalletContract.create(wallet.publicKey, "v4r2", wallet.testnet)
        val accountId = v4R2Contact.address.toAccountId()
        return accountRepository.getWalletByAccountId(accountId, wallet.testnet) != null
    }

    private suspend fun buildUiItems(
        displayWallet: WalletEntity,
        currency: WalletCurrency,
        language: Language,
        searchEngine: SearchEngine,
        hasBackup: Boolean
    ) {
        val hasW5 = hasW5()
        val hasV4R2 = hasV4R2()
        val uiItems = mutableListOf<Item>()
        uiItems.add(Item.Account(displayWallet))

        uiItems.add(Item.Space)
        if (wallet.hasPrivateKey) {
            uiItems.add(Item.Backup(ListCell.Position.FIRST, hasBackup))
            uiItems.add(Item.Security(ListCell.Position.LAST))
        } else {
            uiItems.add(Item.Security(ListCell.Position.SINGLE))
        }

        uiItems.add(Item.Space)

        if (wallet.hasPrivateKey && !wallet.testnet && !api.config.flags.disableTron) {
            val tronUsdtEnabled = settingsRepository.getTronUsdtEnabled(displayWallet.id)
            uiItems.add(Item.TronToggle(enabled = tronUsdtEnabled))
            uiItems.add(Item.Space)
        }

        if (environment.isGooglePlayServicesAvailable) {
            uiItems.add(Item.Notifications(ListCell.Position.FIRST))
        }

        var secondCellPosition = if (environment.isGooglePlayServicesAvailable) {
            ListCell.Position.MIDDLE
        } else {
            ListCell.Position.FIRST
        }

        if (wallet.hasPrivateKey) {
            if (!hasW5) {
                uiItems.add(Item.W5(secondCellPosition))
                secondCellPosition = ListCell.Position.MIDDLE
            }
            if (!hasV4R2) {
                uiItems.add(Item.V4R2(secondCellPosition))
                secondCellPosition = ListCell.Position.MIDDLE
            }
        }
        if (!wallet.testnet) {
            uiItems.add(Item.Currency(currency.code, secondCellPosition))
            secondCellPosition = ListCell.Position.MIDDLE
        }

        uiItems.add(Item.RpcNode(api.tosRpcEndpoint(wallet.testnet), secondCellPosition))
        secondCellPosition = ListCell.Position.MIDDLE

        if (wallet.isTonConnectSupported) {
            uiItems.add(Item.SearchEngine(searchEngine, secondCellPosition))
            uiItems.add(Item.ConnectedApps(ListCell.Position.MIDDLE))
            if (hasInstalledExtensions() && (wallet.hasPrivateKey || wallet.signer)) {
                uiItems.add(Item.InstalledExtensions(ListCell.Position.MIDDLE))
            }
        }

        uiItems.add(Item.Language(language.nameLocalized.ifEmpty {
            getString(Localization.system)
        }.capitalized, ListCell.Position.MIDDLE))

        val batteryCharges = getBatteryCharges()
        if (wallet.hasPrivateKey && (!api.config.flags.disableBattery || batteryCharges > 0)) {
            uiItems.add(Item.Battery(ListCell.Position.MIDDLE))
        }
        if (WidgetManager.isRequestPinAppWidgetSupported) {
            uiItems.add(Item.Widget(ListCell.Position.MIDDLE))
        }
        uiItems.add(Item.Theme(ListCell.Position.LAST))

        uiItems.add(Item.Space)
        if (environment.isGooglePlayServicesAvailable) {
            uiItems.add(Item.Rate(ListCell.Position.FIRST))
            uiItems.add(Item.Legal(ListCell.Position.LAST))
        } else {
            uiItems.add(Item.Legal(ListCell.Position.SINGLE))
        }

        uiItems.add(Item.Space)
        if (wallet.type == Wallet.Type.Watch) {
            uiItems.add(Item.DeleteWatchAccount(ListCell.Position.SINGLE))
        } else {
            uiItems.add(Item.Logout(ListCell.Position.SINGLE, wallet.label, !wallet.hasPrivateKey))
        }
        uiItems.add(Item.Space)
        uiItems.add(Item.Logo(environment.installerSource))

        _uiItemsFlow.value = uiItems
    }

    private suspend fun getBatteryCharges(): Int = withContext(Dispatchers.IO) {
        accountRepository.requestTonProofToken(wallet)?.let {
            batteryRepository.getCharges(it, wallet.publicKey, wallet.testnet, true)
        } ?: 0
    }

    private suspend fun hasInstalledExtensions(): Boolean = withContext(Dispatchers.IO) {
        val plugins = pluginsRepository.getPlugins(wallet.accountId, wallet.testnet)
        plugins.isNotEmpty()
    }
}
