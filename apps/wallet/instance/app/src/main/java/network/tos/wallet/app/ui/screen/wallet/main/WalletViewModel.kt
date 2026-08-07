package network.tos.wallet.app.ui.screen.wallet.main

import android.app.Application
import androidx.lifecycle.viewModelScope
import network.tos.icu.Coins
import network.tos.network.NetworkMonitor
import network.tos.wallet.app.Environment
import network.tos.wallet.app.RemoteConfig
import network.tos.wallet.app.core.DevSettings
import network.tos.wallet.app.core.entities.AssetsEntity.Companion.sort
import network.tos.wallet.app.extensions.hasPushPermission
import network.tos.wallet.app.helper.DateHelper
import network.tos.wallet.app.manager.apk.APKManager
import network.tos.wallet.app.manager.assets.AssetsManager
import network.tos.wallet.app.manager.tx.TransactionManager
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.screen.wallet.main.list.Item
import network.tos.wallet.app.ui.screen.wallet.main.list.Item.Status
import network.tos.wallet.api.API
import network.tos.wallet.api.entity.NotificationEntity
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.Wallet
import network.tos.wallet.data.backup.BackupRepository
import network.tos.wallet.data.battery.BatteryRepository
import network.tos.wallet.data.collectibles.CollectiblesRepository
import network.tos.wallet.data.collectibles.entities.DnsExpiringEntity
import network.tos.wallet.data.core.ScreenCacheSource
import network.tos.wallet.data.core.currency.WalletCurrency
import network.tos.wallet.data.plugins.PluginsRepository
import network.tos.wallet.data.rates.RatesRepository
import network.tos.wallet.data.rates.entity.RatesEntity
import network.tos.wallet.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uikit.extensions.collectFlow
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes

class WalletViewModel(
    app: Application,
    private val wallet: WalletEntity,
    private val accountRepository: AccountRepository,
    private val settingsRepository: SettingsRepository,
    private val api: API,
    private val networkMonitor: NetworkMonitor,
    private val screenCacheSource: ScreenCacheSource,
    private val backupRepository: BackupRepository,
    private val ratesRepository: RatesRepository,
    private val batteryRepository: BatteryRepository,
    private val transactionManager: TransactionManager,
    private val assetsManager: AssetsManager,
    private val apkManager: APKManager,
    private val remoteConfig: RemoteConfig,
    private val environment: Environment,
    private val collectiblesRepository: CollectiblesRepository,
    private val pluginsRepository: PluginsRepository,
) : BaseWalletVM(app) {

    val installId: String
        get() = settingsRepository.installId

    private var autoRefreshJob: Job? = null
    private val alertNotificationsFlow = MutableStateFlow<List<NotificationEntity>>(emptyList())

    private val _uiLabelFlow = MutableStateFlow<Wallet.Label?>(null)
    val uiLabelFlow = _uiLabelFlow.asStateFlow()

    private val _lastLtFlow = MutableStateFlow(0L)
    private val _statusFlow = MutableStateFlow<Status?>(null)
    val statusFlow = _statusFlow.asStateFlow().filterNotNull()

    private val _stateMainFlow = MutableStateFlow<State.Main?>(null)
    private val stateMainFlow = _stateMainFlow.asStateFlow().filterNotNull()

    private val _domainRenewFlow = MutableStateFlow<List<DnsExpiringEntity>>(emptyList())
    private val domainRenewFlow = _domainRenewFlow.asStateFlow().filterNotNull()

    private val updateWalletSettings = combine(
        settingsRepository.tokenPrefsChangedFlow,
        settingsRepository.walletPrefsChangedFlow,
        settingsRepository.safeModeStateFlow,
    ) { _, _, _ -> }

    private val _stateSettingsFlow = combine(
        api.configFlow,
        settingsRepository.hiddenBalancesFlow,
        statusFlow,
    ) { _, hiddenBalance, status ->
        State.Settings(hiddenBalance, api.config, status)
    }.distinctUntilChanged()

    private val _uiItemsFlow = MutableStateFlow<List<Item>?>(null)
    val uiItemsFlow = _uiItemsFlow.asStateFlow().filterNotNull()

    val hasBackupFlow = backupRepository.stream.map { backups ->
        if (!wallet.hasPrivateKey) {
            true
        } else {
            backups.indexOfFirst { it.walletId == wallet.id } > -1
        }
    }.map { !it }

    private val _streamFlow = combine(
        updateWalletSettings,
        batteryRepository.balanceUpdatedFlow,
        transactionManager.tronUpdatedFlow,
        _lastLtFlow
    ) { _, _, _, lastLt -> lastLt }

    init {
        viewModelScope.launch {
            val cached = screenCacheSource.getWalletScreen(wallet) ?: listOf(Item.Skeleton(true))
            _uiItemsFlow.value = cached
        }

        requestDnsExpiring()

        collectFlow(transactionManager.eventsFlow(wallet)) { event ->
            if (event.pending) {
                setStatus(Status.SendingTransaction)
            } else {
                setStatus(Status.TransactionConfirmed)
                delay(2000)
                setStatus(Status.Default)
                _lastLtFlow.value = event.lt
                _domainRenewFlow.value =
                    collectiblesRepository.getDnsSoonExpiring(wallet.accountId, wallet.testnet)
            }
        }

        collectFlow(networkMonitor.isOnlineFlow) { online ->
            if (!online) {
                setStatus(Status.NoInternet)
                delay(3000)
                setStatus(Status.LastUpdated)
            }
        }

        combine(
            settingsRepository.currencyFlow,
            backupRepository.stream,
            networkMonitor.isOnlineFlow,
            _streamFlow,
            apkManager.statusFlow,
        ) { currency, backups, currentIsOnline, currentLt, apkStatus ->
            val lastLt = _stateMainFlow.value?.lt ?: 0
            val lastIsOnline = _stateMainFlow.value?.isOnline

            val isRequestUpdate =
                _stateMainFlow.value == null || lastLt != currentLt || lastIsOnline != currentIsOnline

            if (isRequestUpdate) {
                setStatus(Status.Updating)
            }

            _uiLabelFlow.value = wallet.label

            val hasBackup = if (!wallet.hasPrivateKey) {
                true
            } else {
                backups.indexOfFirst { it.walletId == wallet.id } > -1
            }

            val walletCurrency = getCurrency(wallet, currency)

            val localAssets = getAssets(walletCurrency, false)
            if (localAssets != null) {
                val batteryBalance = getBatteryBalance(wallet)
                val plugins = emptyList<io.tonapi.models.WalletPlugin>()
                val state = State.Main(
                    wallet = wallet,
                    assets = localAssets,
                    hasBackup = hasBackup,
                    battery = State.Battery(
                        balance = batteryBalance,
                        beta = api.config.batteryBeta,
                        disabled = (api.config.flags.disableBattery && batteryBalance.value == BigDecimal.ZERO || !wallet.hasPrivateKey),
                        viewed = settingsRepository.batteryViewed,
                    ),
                    lt = currentLt,
                    isOnline = currentIsOnline,
                    apkStatus = apkStatus,
                    tronUsdtEnabled = settingsRepository.getTronUsdtEnabled(wallet.id),
                    plugins = plugins
                )
                assetsManager.setCachedTotalBalance(
                    wallet,
                    walletCurrency,
                    true,
                    state.totalBalanceFiat
                )
                _stateMainFlow.value = state
            }

            if (isRequestUpdate) {
                val remoteAssets = getAssets(walletCurrency, true)
                val batteryBalance = getBatteryBalance(wallet, true)
                val plugins = emptyList<io.tonapi.models.WalletPlugin>()
                if (remoteAssets != null) {
                    val state = State.Main(
                        wallet,
                        remoteAssets,
                        hasBackup = hasBackup,
                        battery = State.Battery(
                            balance = batteryBalance,
                            beta = api.config.batteryBeta,
                            disabled = (api.config.flags.disableBattery && batteryBalance.value == BigDecimal.ZERO || !wallet.hasPrivateKey),
                            viewed = settingsRepository.batteryViewed,
                        ),
                        lt = currentLt,
                        isOnline = currentIsOnline,
                        apkStatus = apkStatus,
                        tronUsdtEnabled = settingsRepository.getTronUsdtEnabled(wallet.id),
                        plugins = plugins,
                    )
                    _stateMainFlow.value = state
                    assetsManager.setCachedTotalBalance(
                        wallet,
                        walletCurrency,
                        true,
                        state.totalBalanceFiat
                    )
                    settingsRepository.setWalletLastUpdated(wallet.id)
                    setStatus(Status.Default)
                }
            }
        }.launchIn(viewModelScope)

        combine(
            stateMainFlow,
            alertNotificationsFlow,
            _stateSettingsFlow,
            updateWalletSettings,
            domainRenewFlow,
        ) { state, alerts, settings, _, renewDomains ->
            val status = settings.status /* if (settings.status == Status.NoInternet) {
                settings.status
            } else if (settings.status != Status.SendingTransaction && settings.status != Status.TransactionConfirmed) {
                state.status
            } else {
                settings.status
            }*/

            val isSetupHidden = settingsRepository.isSetupHidden(state.wallet.id)
            val uiSetup: State.Setup? = if (isSetupHidden) null else {
                val walletPushEnabled = settingsRepository.getPushWallet(state.wallet.id)
                val hasInitializedWallet = accountRepository.getInitializedWallets().isNotEmpty()
                State.Setup(
                    pushEnabled = !environment.isGooglePlayServicesAvailable || (context.hasPushPermission() && walletPushEnabled),
                    biometryEnabled = if (wallet.hasPrivateKey) settingsRepository.biometric else true,
                    hasBackup = if (wallet.hasPrivateKey) state.hasBackup else true,
                    showTelegramChannel = false,
                    safeModeBlock = !api.config.flags.safeModeEnabled && hasInitializedWallet && settingsRepository.showSafeModeSetup,
                    onboardingStoriesEnabled = wallet.hasPrivateKey && !wallet.testnet && remoteConfig.isOnboardingStoriesEnabled,
                )
            }

            val lastUpdated = settingsRepository.getWalletLastUpdated(state.wallet.id)

            val uiItems = state.uiItems(
                context = context,
                wallet = state.wallet,
                hiddenBalance = settings.hiddenBalance,
                status = status,
                config = settings.config,
                alerts = alerts,
                dAppNotifications = State.DAppNotifications(emptyList()),
                setup = uiSetup,
                lastUpdatedFormat = DateHelper.formattedDate(
                    lastUpdated,
                    settingsRepository.getLocale()
                ),
                prefixYourAddress = 3 > settingsRepository.addressCopyCount,
                renewDomains = renewDomains
            )
            if (uiItems.isNotEmpty()) {
                _uiItemsFlow.value = uiItems
                setCached(state.wallet, uiItems)
            }
        }.launchIn(viewModelScope)

        autoRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                checkAutoRefresh()
                delay(2.minutes)
            }
        }
    }

    fun refresh() {
        requestDnsExpiring()
        _statusFlow.value = Status.Updating
        _lastLtFlow.value += 1
    }

    private fun requestDnsExpiring() {
        _domainRenewFlow.value = emptyList()
    }

    private suspend fun checkAutoRefresh() {
        if (hasPendingTransaction()) {
            withContext(Dispatchers.Main) {
                refresh()
            }
        }
    }

    private fun hasPendingTransaction(): Boolean {
        return _statusFlow.value == Status.SendingTransaction
    }

    fun nextWallet() {
        viewModelScope.launch {
            val wallets = accountRepository.getWallets()
            val index = wallets.indexOf(wallet)
            val nextIndex = if (index == wallets.size - 1) 0 else index + 1
            accountRepository.setSelectedWallet(wallets[nextIndex].id)
        }
    }

    fun prevWallet() {
        viewModelScope.launch {
            val wallets = accountRepository.getWallets()
            val index = wallets.indexOf(wallet)
            val prevIndex = if (index == 0) wallets.size - 1 else index - 1
            accountRepository.setSelectedWallet(wallets[prevIndex].id)
        }
    }

    private fun setStatus(status: Status) {
        _statusFlow.tryEmit(status)
    }

    private suspend fun getBatteryBalance(
        wallet: WalletEntity,
        ignoreCache: Boolean = false
    ): Coins = Coins.ZERO

    private suspend fun getAssets(
        currency: WalletCurrency,
        refresh: Boolean
    ): State.Assets? = withContext(Dispatchers.IO) {
        assetsManager.getAssets(wallet, currency, refresh)?.let {
            State.Assets(
                currency = currency,
                list = it.sort(wallet, settingsRepository),
                fromCache = !refresh,
                rates = RatesEntity.empty(currency)
            )
        }
    }

    private fun setCached(wallet: WalletEntity, items: List<Item>) {
        screenCacheSource.set(CACHE_NAME, wallet.id, items)
    }

    override fun onCleared() {
        super.onCleared()
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    companion object {
        private const val CACHE_NAME = "wallet"

        private fun getCurrency(
            wallet: WalletEntity,
            currency: WalletCurrency
        ): WalletCurrency {
            return if (wallet.testnet) WalletCurrency.TON else currency
        }

        fun ScreenCacheSource.getWalletScreen(wallet: WalletEntity): List<Item>? {
            try {
                val items: List<Item> = get(CACHE_NAME, wallet.id) { parcel ->
                    Item.createFromParcel(parcel)
                }.map {
                    if (it is Item.Balance) {
                        it.copy(status = Status.Updating)
                    } else {
                        it
                    }
                }.filter { it !is Item.ApkStatus }
                if (items.isEmpty()) {
                    return null
                }
                return items
            } catch (e: Throwable) {
                return null
            }
        }
    }
}
