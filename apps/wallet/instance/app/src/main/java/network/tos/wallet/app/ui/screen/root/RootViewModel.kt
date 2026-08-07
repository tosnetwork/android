package network.tos.wallet.app.ui.screen.root

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import network.tos.extensions.CrashReporter
import network.tos.blockchain.ton.extensions.equalsAddress
import network.tos.blockchain.ton.extensions.toAccountId
import network.tos.extensions.MutableEffectFlow
import network.tos.extensions.bestMessage
import network.tos.extensions.currentTimeSeconds
import network.tos.extensions.getStringValue
import network.tos.extensions.setLocales
import network.tos.extensions.toUriOrNull
import network.tos.icu.Coins
import network.tos.ledger.ton.LedgerConnectData
import network.tos.wallet.app.App
import network.tos.wallet.app.Environment
import network.tos.wallet.app.api.getCurrencyCodeByCountry
import network.tos.wallet.app.billing.BillingManager
import network.tos.wallet.app.client.safemode.SafeModeClient
import network.tos.wallet.app.core.AnalyticsHelper
import network.tos.wallet.app.core.DevSettings
import network.tos.wallet.app.core.entities.WalletPurchaseMethodEntity
import network.tos.wallet.app.core.history.ActionOptions
import network.tos.wallet.app.core.history.HistoryHelper
import network.tos.wallet.app.core.history.list.item.HistoryItem
import network.tos.wallet.app.deeplink.DeepLink
import network.tos.wallet.app.deeplink.DeepLinkFeaturePolicy
import network.tos.wallet.app.deeplink.DeepLinkRoute
import network.tos.wallet.app.extensions.getAppFixIcon
import network.tos.wallet.app.extensions.hasRefer
import network.tos.wallet.app.extensions.hasUtmSource
import network.tos.wallet.app.extensions.isSafeModeEnabled
import network.tos.wallet.app.extensions.safeExternalOpenUri
import network.tos.wallet.app.helper.BrowserHelper
import network.tos.wallet.app.helper.ReferrerClientHelper
import network.tos.wallet.app.helper.ShortcutHelper
import network.tos.wallet.app.manager.apk.APKManager
import network.tos.wallet.app.manager.push.FirebasePush
import network.tos.wallet.app.manager.push.PushManager
import network.tos.wallet.app.manager.tonconnect.TonConnectManager
import network.tos.wallet.app.manager.tonconnect.bridge.model.BridgeError
import network.tos.wallet.app.manager.tonconnect.bridge.model.SignDataRequestPayload
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.component.UpdateAvailableDialog
import network.tos.wallet.app.ui.screen.add.AddWalletScreen
import network.tos.wallet.app.ui.screen.backup.main.BackupScreen
import network.tos.wallet.app.ui.screen.battery.BatteryScreen
import network.tos.wallet.app.ui.screen.browser.confirm.DAppConfirmScreen
import network.tos.wallet.app.ui.screen.browser.dapp.DAppScreen
import network.tos.wallet.app.ui.screen.browser.safe.DAppSafeScreen
import network.tos.wallet.app.ui.screen.camera.CameraScreen
import network.tos.wallet.app.ui.screen.dns.renew.DNSRenewScreen
import network.tos.wallet.app.ui.screen.init.list.AccountItem
import network.tos.wallet.app.ui.screen.name.edit.EditNameScreen
import network.tos.wallet.app.ui.screen.onramp.main.OnRampScreen
import network.tos.wallet.app.ui.screen.qr.QRScreen
import network.tos.wallet.app.ui.screen.send.main.SendScreen
import network.tos.wallet.app.ui.screen.send.transaction.SendTransactionScreen
import network.tos.wallet.app.ui.screen.settings.currency.CurrencyScreen
import network.tos.wallet.app.ui.screen.settings.extensions.ExtensionsScreen
import network.tos.wallet.app.ui.screen.settings.language.LanguageScreen
import network.tos.wallet.app.ui.screen.settings.main.SettingsScreen
import network.tos.wallet.app.ui.screen.settings.security.SecurityScreen
import network.tos.wallet.app.ui.screen.sign.SignDataScreen
import network.tos.wallet.app.ui.screen.staking.stake.StakingScreen
import network.tos.wallet.app.ui.screen.staking.viewer.StakeViewerScreen
import network.tos.wallet.app.ui.screen.stories.remote.RemoteStoriesScreen
import network.tos.wallet.app.ui.screen.token.viewer.TokenScreen
import network.tos.wallet.app.ui.screen.transaction.TransactionScreen
import network.tos.wallet.app.ui.screen.wallet.manage.TokensManageScreen
import network.tos.wallet.app.ui.screen.wallet.picker.PickerScreen
import network.tos.wallet.app.R
import network.tos.wallet.api.API
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.browser.BrowserRepository
import network.tos.wallet.data.core.currency.WalletCurrency
import network.tos.wallet.data.core.entity.SignRequestEntity
import network.tos.wallet.data.dapps.DAppsRepository
import network.tos.wallet.data.dapps.entities.AppConnectEntity
import network.tos.wallet.data.passcode.LockScreen
import network.tos.wallet.data.passcode.PasscodeManager
import network.tos.wallet.data.purchase.PurchaseRepository
import network.tos.wallet.data.rates.RatesRepository
import network.tos.wallet.data.settings.SettingsRepository
import network.tos.wallet.data.token.TokenRepository
import network.tos.wallet.localization.Localization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import uikit.extensions.activity
import java.util.concurrent.CancellationException
import kotlin.math.abs

class RootViewModel(
    app: Application,
    private val settingsRepository: SettingsRepository,
    private val accountRepository: AccountRepository,
    private val api: API,
    private val historyHelper: HistoryHelper,
    private val purchaseRepository: PurchaseRepository,
    private val tonConnectManager: TonConnectManager,
    private val browserRepository: BrowserRepository,
    private val pushManager: PushManager,
    private val tokenRepository: TokenRepository,
    private val environment: Environment,
    private val passcodeManager: PasscodeManager,
    private val apkManager: APKManager,
    private val referrerClientHelper: ReferrerClientHelper,
    private val dAppsRepository: DAppsRepository,
    private val safeModeClient: SafeModeClient,
    private val ratesRepository: RatesRepository,
    private val analyticsHelper: AnalyticsHelper,
    private val billingManager: BillingManager,
    savedStateHandle: SavedStateHandle,
): BaseWalletVM(app) {

    private val savedState = RootModelState(savedStateHandle)

    private val appUpdateManager: AppUpdateManager by lazy {
        AppUpdateManagerFactory.create(context)
    }

    private val selectedWalletFlow: Flow<WalletEntity> = accountRepository.selectedWalletFlow

    private val _hasWalletFlow = MutableEffectFlow<Boolean?>()
    val hasWalletFlow = _hasWalletFlow.asSharedFlow().filterNotNull()

    private val _eventFlow = MutableEffectFlow<RootEvent?>()
    val eventFlow = _eventFlow.asSharedFlow().filterNotNull()

    private val ignoreTonConnectTransaction = mutableListOf<String>()

    val installId: String
        get() = settingsRepository.installId

    val lockscreenFlow = combine(
        passcodeManager.lockscreenFlow,
        accountRepository.selectedStateFlow.filter { it !is AccountRepository.SelectedState.Initialization }.take(1)
    ) { lockscreen, state ->
        if ((lockscreen is LockScreen.State.Input || lockscreen is LockScreen.State.Biometric) && state !is AccountRepository.SelectedState.Wallet) {
            passcodeManager.reset()
            LockScreen.State.None
        } else {
            lockscreen
        }
    }

    override fun attachHolder(holder: Holder) {
        super.attachHolder(holder)
        observeTonConnectTransaction()
        observeTonConnectSignData()
    }

    private suspend fun sendFirstLaunchEvent() = withContext(Dispatchers.IO) {
        if (0 >= DevSettings.firstLaunchDate) {
            val referrer = referrerClientHelper.getInstallReferrer()
            val deeplink = DevSettings.firstLaunchDeeplink.ifBlank { null }
            analyticsHelper.firstLaunch(referrer, deeplink)
            DevSettings.firstLaunchDate = currentTimeSeconds()
        }
    }

    private fun observeTonConnectTransaction() {
        tonConnectManager.transactionRequestFlow.map { (connection, message) ->
            val tx = RootSignTransaction(connection, message, savedState.returnUri)
            savedState.returnUri = null
            tx
        }.filter {
            !ignoreTonConnectTransaction.contains(it.hash)
        }.collectFlow {
            _eventFlow.tryEmit(RootEvent.CloseCurrentTonConnect)
            viewModelScope.launch {
                ignoreTonConnectTransaction.add(it.hash)
                signTransaction(it)
            }
        }
    }

    private fun observeTonConnectSignData() {
        tonConnectManager.signDataRequestFlow.collectFlow { event ->
            val wallet = accountRepository.getWalletByAccountId(event.connection.accountId) ?: return@collectFlow
            val params = event.message.params.firstOrNull() ?: return@collectFlow
            val payload = SignDataRequestPayload.parse(params) ?: return@collectFlow
            signData(wallet, event.connection, payload, event.message.id)
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                environment.setCountryFromStore(billingManager.getCountry())
            } catch (_: Throwable) {
                Log.d("RootViewModel", "Failed to get country from billing manager")
            }
            api.setCountry(deviceCountry = environment.country, storeCountry = environment.storeCountry)
            api.initConfig()
        }

        pushManager.clearNotifications()

        settingsRepository.languageFlow.collectFlow {
            context.setLocales(settingsRepository.localeList)
            App.instance.updateThemes()
        }

        accountRepository.selectedStateFlow.filter {
            it !is AccountRepository.SelectedState.Initialization
        }.onEach { state ->
            if (state is AccountRepository.SelectedState.Empty) {
                _hasWalletFlow.tryEmit(false)
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            } else if (state is AccountRepository.SelectedState.Wallet) {
                _hasWalletFlow.tryEmit(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        WebView.setDataDirectorySuffix("wallet_${state.wallet.id.replace("-", "")}")
                    } catch (ignored: Throwable) { }
                }
            }
        }.flowOn(Dispatchers.IO).launchIn(viewModelScope)

        viewModelScope.launch(Dispatchers.IO) {
            val firebaseToken = FirebasePush.requestToken()
            settingsRepository.firebaseToken = firebaseToken
            ratesRepository.updateAll(settingsRepository.currency)
            if (firebaseToken.isNullOrBlank()) {
                Log.e("TosWalletFirebasePush", "Failed to get Firebase push token")
            } else {
                Log.d("TosWalletFirebasePush", "Firebase push token: $firebaseToken")
            }
        }

        selectedWalletFlow.collectFlow { wallet ->
            applyAnalyticsKeys(wallet)
            initShortcuts(wallet)
        }

        api.configFlow.filter { !it.empty }.take(1).collectFlow { config ->
            analyticsHelper.setConfig(context, config)
            sendFirstLaunchEvent()
        }

        combine(
            accountRepository.selectedWalletFlow.take(1),
            api.configFlow.filter { !it.empty }
        ) { _, config ->
            if (config.stories.isNotEmpty()) {
                showStories(config.stories)
            }
        }.launch()

        viewModelScope.launch(Dispatchers.IO) {
            if (environment.isGooglePlayServicesAvailable) {
                delay(2000)
                checkAppUpdate()
            }
        }

        apkManager.statusFlow.filter {
            it is APKManager.Status.UpdateAvailable
        }.collectFlow {
            delay(1000)
            showUpdateAvailable(it as APKManager.Status.UpdateAvailable)
        }
    }

    private fun showUpdateAvailable(status: APKManager.Status.UpdateAvailable) {
        try {
            UpdateAvailableDialog(context, apkManager).show {
                apkManager.download(status.apk)
            }
        } catch (e: Throwable) {
            CrashReporter.recordException(e)
        }
    }

    private suspend fun showStories(storiesIds: List<String>) = withContext(Dispatchers.IO) {
        val firstStoryId = storiesIds.firstOrNull { !settingsRepository.isStoriesViewed(it) } ?: return@withContext
        showStory(firstStoryId, "wallet")
    }

    private suspend fun showStory(id: String, from: String) = withContext(Dispatchers.IO) {
        val stories = api.getStories(id) ?: return@withContext
        openScreen(RemoteStoriesScreen.newInstance(stories, from))
    }

    private suspend fun checkAppUpdate() = withContext(Dispatchers.IO) {
        try {
            val updateInfo = appUpdateManager.appUpdateInfo.await()
            if (updateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                startUpdateFlow(updateInfo)
            }
        } catch (e: Throwable) {
            CrashReporter.recordException(e)
        }
    }

    private suspend fun startUpdateFlow(appUpdateInfo: AppUpdateInfo) = withContext(Dispatchers.Main) {
        val activity = context.activity ?: return@withContext
        appUpdateManager.startUpdateFlowForResult(
            appUpdateInfo,
            activity,
            AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE),
            0
        )
    }

    fun connectTonConnectBridge() {
        tonConnectManager.connectBridge()
    }

    fun disconnectTonConnectBridge() {
        tonConnectManager.disconnectBridge()
    }

    private suspend fun signTransaction(tx: RootSignTransaction) {
        val eventId = tx.id
        try {
            val signRequests = tx.params.map { SignRequestEntity(it, tx.connection.appUrl) }
            if (signRequests.isEmpty()) {
                throw IllegalArgumentException("Empty sign requests")
            }
            for (signRequest in signRequests) {
                signRequest(eventId, tx.connection, signRequest)
            }
        } catch (e: Throwable) {
            CrashReporter.recordException(e)
            tonConnectManager.sendBridgeError(tx.connection, BridgeError.unknown(e.bestMessage), eventId)
        }

        tx.returnUri?.let {
            context.safeExternalOpenUri(it)
        }
    }

    private suspend fun signRequest(
        eventId: Long,
        connection: AppConnectEntity,
        signRequest: SignRequestEntity
    ) {
        if (signRequest.from != null && !signRequest.from!!.toAccountId()
                .equalsAddress(connection.accountId)
        ) {
            DevSettings.tonConnectLog(
                "Invalid \"from\" address.\nReceived: ${signRequest.from?.toAccountId()}\nExpected: ${connection.accountId}",
                error = true
            )
            tonConnectManager.sendBridgeError(
                connection,
                BridgeError.badRequest("Invalid \"from\" address. Specified wallet address not connected to this app."),
                eventId
            )
            return
        }

        val now = currentTimeSeconds()
        val validUntil = signRequest.validUntil.let { parsedExp ->
            if (0 >= parsedExp) {
                now + DeepLinkRoute.Transfer.MAX_EXP
            } else {
                val maxExp = now + DeepLinkRoute.Transfer.MAX_EXP
                minOf(parsedExp, maxExp)
            }
        }

        val isExpired = run {
            val fixedExp = abs(validUntil - 15L)
            now >= fixedExp
        }

        if (isExpired) {
            tonConnectManager.sendBridgeError(
                connection,
                BridgeError.badRequest("Transaction has expired"),
                eventId
            )
            return
        }

        val wallets = accountRepository.getWalletsByAccountId(
            accountId = connection.accountId,
            testnet = connection.testnet
        ).filter {
            it.isTonConnectSupported
        }
        if (wallets.isEmpty()) {
            tonConnectManager.sendBridgeError(connection, BridgeError.unknown(""), eventId)
            return
        }
        val wallet = wallets.find { it.hasPrivateKey } ?: wallets.first()
        try {
            val boc = SendTransactionScreen.run(context, wallet, signRequest)
            tonConnectManager.sendTransactionResponseSuccess(connection, boc, eventId)
        } catch (e: Throwable) {
            DevSettings.tonConnectLog(
                "Error while signing transaction: ${e.bestMessage}",
                error = true
            )
            if (e is CancellationException) {
                tonConnectManager.showLogoutAppBar(wallet, context, connection.appUrl)
                tonConnectManager.sendBridgeError(
                    connection,
                    BridgeError.userDeclinedTransaction(),
                    eventId
                )
            } else {
                tonConnectManager.sendBridgeError(connection, BridgeError.unknown(e.bestMessage), eventId)
            }
        }
    }

    private suspend fun initShortcuts(
        currentWallet: WalletEntity
    ) = withContext(Dispatchers.IO) {
        val wallets = accountRepository.getWallets()
        val list = mutableListOf<ShortcutInfoCompat>()
        if (!currentWallet.testnet) {
            ShortcutHelper.shortcutAction(
                context,
                Localization.send,
                R.drawable.ic_send_shortcut,
                "tos://send"
            )?.let {
                list.add(it)
            }
        }
        list.addAll(walletShortcutsFromWallet(currentWallet, wallets))
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            ShortcutManagerCompat.setDynamicShortcuts(context, list.take(3))
        }
    }

    private suspend fun walletShortcutsFromWallet(
        currentWallet: WalletEntity,
        wallets: List<WalletEntity>
    ): List<ShortcutInfoCompat> {
        val list = mutableListOf<ShortcutInfoCompat>()
        if (1 >= wallets.size) {
            return list
        }
        for (wallet in wallets) {
            if (wallet == currentWallet || wallet.label.name.isBlank()) {
                continue
            }
            ShortcutHelper.shortcutWallet(context, wallet)?.let {
                list.add(it)
            }
        }
        return list
    }

    private fun applyAnalyticsKeys(wallet: WalletEntity) {
        // TOS: no external analytics/crash reporting — wallet identity (accountId,
        // installId, wallet type) is never sent off-device. Intentionally a no-op.
    }

    fun signOut() {
        viewModelScope.launch {
            accountRepository.logout()
        }
    }

    fun connectLedger(connectData: LedgerConnectData, accounts: List<AccountItem>) {
        _eventFlow.tryEmit(RootEvent.Ledger(connectData, accounts))
    }

    fun openDApp(url: Uri, source: String) {
        if (api.config.flags.disableDApps) {
            return
        }
        selectedWalletFlow.take(1).collectFlow {
            _eventFlow.tryEmit(RootEvent.OpenDAppByShortcut(it, url, source))
        }
    }

    fun processIntentExtras(bundle: Bundle): Boolean {
        val pushType = bundle.getString("type") ?: return false
        val pushId = bundle.getStringValue("push_id", "utm_id", "utm_campaign")
        hasWalletFlow.take(1).collectFlow {
            if (pushType == "console_dapp_notification") {
                processDAppPush(bundle)
            } else {
                val deeplink = bundle.getString("deeplink")?.toUriOrNull() ?: return@collectFlow
                analyticsHelper.trackPushClick(
                    pushId = pushId ?: pushType,
                    payload = deeplink.toString(),
                )
                processDeepLinkPush(deeplink, bundle)
            }
        }
        return true
    }

    private suspend fun processDAppPush(bundle: Bundle) {
        if (api.config.flags.disableDApps) {
            return
        }
        val accountId = bundle.getString("account") ?: return
        val wallet = accountRepository.getWalletByAccountId(accountId) ?: return
        val openUrl = bundle.getString("link")?.toUriOrNull() ?: bundle.getString("dapp_url")?.toUriOrNull()
        if (openUrl == null) {
            return
        }
        val app = dAppsRepository.getAppFixIcon(openUrl, wallet, browserRepository, settingsRepository)
        openScreen(
            DAppScreen.newInstance(
                wallet = wallet,
                title = openUrl.host ?: "unknown",
                url = openUrl,
                iconUrl = app.iconUrl,
                source = "push"
            )
        )
    }

    private suspend fun processDeepLinkPush(uri: Uri, bundle: Bundle) {
        val wallet = deeplinkResolveWallet(bundle) ?: return
        if (accountRepository.getSelectedWallet()?.id != wallet.id) {
            accountRepository.setSelectedWallet(wallet.id)
        }
        val deeplink = DeepLink(uri, false, null)
        processDeepLink(wallet, deeplink, null)
    }

    private suspend fun deeplinkResolveWallet(bundle: Bundle): WalletEntity? {
        try {
            val accountId = bundle.getString("account") ?: throw IllegalArgumentException("Key 'account' not found")
            return accountRepository.getWalletByAccountId(accountId) ?: throw IllegalArgumentException("Wallet not found")
        } catch (e: Throwable) {
            return accountRepository.selectedWalletFlow.firstOrNull()
        }
    }

    fun processDeepLink(
        uri: Uri,
        fromQR: Boolean,
        refSource: Uri?,
        internal: Boolean,
        fromPackageName: String?
    ): Boolean {
        savedState.returnUri = null
        val deeplink = DeepLink(uri, fromQR, refSource)
        if (deeplink.route is DeepLinkRoute.Unknown) {
            viewModelScope.launch { showInvalidLinkToast(deeplink.route) }
            return false
        }
        if (deeplink.route is DeepLinkRoute.Internal && !internal) {
            return true
        }
        accountRepository.selectedStateFlow.take(1).onEach { state ->
            if (deeplink.route is DeepLinkRoute.Signer) {
                processSignerDeepLink(deeplink.route, fromQR)
            } else if (state is AccountRepository.SelectedState.Wallet) {
                processDeepLink(state.wallet, deeplink, fromPackageName)
            }
        }.launch()
        return true
    }

    fun processTonConnectDeepLink(deeplink: DeepLink, fromPackageName: String?) {
        val route = deeplink.route as DeepLinkRoute.TonConnect

        savedState.returnUri = tonConnectManager.processDeeplink(
            context = context,
            uri = route.uri,
            fromQR = deeplink.fromQR,
            refSource = deeplink.referrer,
            fromPackageName = fromPackageName
        )
    }

    private suspend fun processDeepLink(
        wallet: WalletEntity,
        deeplink: DeepLink,
        fromPackageName: String?
    ) {
        val route = deeplink.route
        if (!DeepLinkFeaturePolicy.isAllowed(route, api.config.flags)) {
            showInvalidLinkToast(route)
            return
        }
        if (route is DeepLinkRoute.DnsRenew) {
            openScreen(DNSRenewScreen.newInstance(wallet, emptyList()))
        } else if (route is DeepLinkRoute.TonConnect) {
            if (!wallet.isTonConnectSupported && accountRepository.getWallets().count { it.isTonConnectSupported } == 0) {
                openScreen(AddWalletScreen.newInstance(true))
                return
            }
            processTonConnectDeepLink(deeplink, fromPackageName)
        } else if (route is DeepLinkRoute.Story) {
            showStory(route.id, "deep-link")
        } else if (route is DeepLinkRoute.Tabs) {
            _eventFlow.tryEmit(RootEvent.OpenTab(route.tabUri.toUri(), wallet, route.from))
        } else if (route is DeepLinkRoute.Send && !wallet.isWatchOnly) {
            openScreen(SendScreen.newInstance(wallet, type = SendScreen.Companion.Type.Default))
        } else if (route is DeepLinkRoute.Staking && !wallet.isWatchOnly) {
            openScreen(StakingScreen.newInstance(wallet, from = "deeplink"))
        } else if (route is DeepLinkRoute.StakingPool) {
            openScreen(StakeViewerScreen.newInstance(wallet, address = route.poolAddress, name = ""))
        } else if (route is DeepLinkRoute.AccountEvent) {
            if (route.address == null) {
                showTransaction(route.eventId)
            } else {
                showTransaction(route.address, route.eventId)
            }
        } else if (route is DeepLinkRoute.Transfer && !wallet.isWatchOnly) {
            processTransferDeepLink(wallet, route)
        } else if (route is DeepLinkRoute.PickWallet) {
            accountRepository.setSelectedWallet(route.walletId)
        } else if (route is DeepLinkRoute.Swap && !api.config.flags.disableSwap) {
            _eventFlow.tryEmit(
                RootEvent.Swap(
                    wallet = wallet,
                    uri = api.config.swapUri,
                    address = wallet.address,
                    from = route.from,
                    to = route.to
                )
            )
        } else if (route is DeepLinkRoute.Battery && !wallet.isWatchOnly) {
            openBattery(wallet, route)
        } else if (route is DeepLinkRoute.Purchase && !wallet.isWatchOnly) {
            openScreen(OnRampScreen.newInstance(context, wallet, "deep-link"))
        } else if (route is DeepLinkRoute.Exchange && !wallet.isWatchOnly) {
            val method = purchaseRepository.getMethod(
                id = route.methodName,
                testnet = wallet.testnet,
                locale = settingsRepository.getLocale()
            )
            if (method == null) {
                toast(Localization.payment_method_not_found)
            } else {
                BrowserHelper.openPurchase(
                    context, WalletPurchaseMethodEntity(
                        method = method,
                        wallet = wallet,
                        currency = api.getCurrencyCodeByCountry(settingsRepository),
                        config = api.config
                    )
                )
            }
        } else if (route is DeepLinkRoute.Backups && wallet.hasPrivateKey) {
            openScreen(BackupScreen.newInstance(wallet))
        } else if (route is DeepLinkRoute.Settings) {
            openScreen(SettingsScreen.newInstance(wallet, from = "deeplink"))
        } else if (route is DeepLinkRoute.DApp) {
            val dAppUri = route.url.toUriOrNull()
            if (dAppUri == null) {
                toast(Localization.invalid_link)
                return
            }

            val host = dAppUri.host
            if (host == null || !host.contains(".")) {
                toast(Localization.invalid_link)
                return
            }

            if (safeModeClient.isHasScamUris(dAppUri)) {
                openScreen(DAppSafeScreen.newInstance(wallet))
                return
            }

            val app = dAppsRepository.getAppFixIcon(dAppUri, wallet, browserRepository, settingsRepository)

            val isTrustedApp = browserRepository.isTrustedApp(
                country = settingsRepository.country,
                testnet = wallet.testnet,
                locale = settingsRepository.getLocale(),
                deeplink = dAppUri
            )

            if (!isTrustedApp && settingsRepository.isDAppOpenConfirm(wallet.id, app.host)) {
                openScreen(DAppConfirmScreen.newInstance(wallet, app, dAppUri))
            } else {
                openScreen(
                    DAppScreen.newInstance(
                        wallet = wallet,
                        title = app.name,
                        url = dAppUri,
                        iconUrl = app.iconUrl,
                        source = "deep-link",
                    )
                )
            }
        } else if (route is DeepLinkRoute.SettingsSecurity) {
            openScreen(SecurityScreen.newInstance(wallet))
        } else if (route is DeepLinkRoute.SettingsCurrency) {
            openScreen(CurrencyScreen.newInstance())
        } else if (route is DeepLinkRoute.SettingsLanguage) {
            openScreen(LanguageScreen.newInstance())
        } else if (route is DeepLinkRoute.SettingsExtensions) {
            openScreen(ExtensionsScreen.newInstance(wallet))
        } else if (route is DeepLinkRoute.SettingsNotifications) {
            openScreen(SettingsScreen.newInstance(wallet, "deeplink"))
        } else if (route is DeepLinkRoute.EditWalletLabel) {
            openScreen(EditNameScreen.newInstance(wallet))
        } else if (route is DeepLinkRoute.Camera && !wallet.isWatchOnly) {
            openScreen(CameraScreen.newInstance())
        } else if (route is DeepLinkRoute.Receive) {
            openScreen(QRScreen.newInstance(wallet))
        } else if (route is DeepLinkRoute.ManageAssets) {
            openScreen(TokensManageScreen.newInstance(wallet))
        } else if (route is DeepLinkRoute.WalletPicker) {
            openScreen(PickerScreen.newInstance(from = "deeplink"))
        } else if (route is DeepLinkRoute.Jetton) {
            openTokenViewer(wallet, route)
        } else if (route is DeepLinkRoute.Install) {
            installAPK(route)
        } else {
            showInvalidLinkToast(deeplink.route)
        }
    }

    private suspend fun showInvalidLinkToast(route: DeepLinkRoute) {
        if (!(route is DeepLinkRoute.Unknown && (route.uri.hasRefer() || route.uri.hasUtmSource()))) {
            toast(Localization.invalid_link)
        }
    }

    private suspend fun installAPK(route: DeepLinkRoute.Install) {
        if (!apkManager.install(context, route.file)) {
            toast(Localization.invalid_link)
        }
    }

    private suspend fun openBattery(wallet: WalletEntity, route: DeepLinkRoute.Battery) {
        val promoCode = route.promocode
        if (promoCode.isNullOrEmpty()) {
            openScreen(BatteryScreen.newInstance(wallet, from = "deeplink", jetton = route.jetton))
        } else {
            loading(true)
            val validCode = api.batteryVerifyPurchasePromo(wallet.testnet, promoCode)
            loading(false)
            if (validCode) {
                openScreen(
                    BatteryScreen.newInstance(
                        wallet,
                        promoCode,
                        "deeplink",
                        jetton = route.jetton
                    )
                )
            } else {
                toast(Localization.wrong_promocode)
            }
        }
    }

    private suspend fun openTokenViewer(wallet: WalletEntity, route: DeepLinkRoute.Jetton) {
        val token =
            tokenRepository.getToken(wallet.accountId, wallet.testnet, route.address) ?: return
        openScreen(TokenScreen.newInstance(wallet, token.address, token.name, token.symbol))
    }

    fun processTransferDeepLink(route: DeepLinkRoute.Transfer) {
        selectedWalletFlow.take(1).collectFlow {
            processTransferDeepLink(it, route)
        }
    }

    private suspend fun processTransferDeepLink(
        wallet: WalletEntity,
        route: DeepLinkRoute.Transfer
    ) {
        if (route.isExpired) {
            toast(Localization.expired_link)
            return
        }
        val decimals = route.jettonAddress?.let {
            tokenRepository.getToken(wallet.accountId, wallet.testnet, it)
        }?.decimals ?: WalletCurrency.TON.decimals

        val amount = route.amount?.let {
            Coins.of(it, decimals)
        }

        _eventFlow.tryEmit(
            RootEvent.Transfer(
                wallet = wallet,
                address = route.address,
                amount = amount,
                text = route.text,
                jettonAddress = route.jettonAddress,
                bin = route.bin,
                initStateBase64 = route.initStateBase64,
                validUnit = route.exp
            )
        )
    }

    fun processSignerDeepLink(route: DeepLinkRoute.Signer, fromQR: Boolean) {
        _eventFlow.tryEmit(
            RootEvent.Singer(
                publicKey = route.publicKey,
                name = route.name,
                qr = fromQR || !route.local
            )
        )
    }

    private suspend fun showTransaction(hash: String) {
        val wallet = selectedWalletFlow.firstOrNull() ?: return
        val tx = historyHelper.getEvent(
            wallet = wallet,
            eventId = hash,
            options = ActionOptions(
                safeMode = settingsRepository.isSafeModeEnabled(api),
            )
        ).filterIsInstance<HistoryItem.Event>().firstOrNull() ?: return
        openScreen(TransactionScreen.newInstance(tx))
    }

    private suspend fun showTransaction(accountId: String, hash: String) {
        val wallet = accountRepository.getWalletByAccountId(accountId, false) ?: return
        val event = api.getTransactionEvents(wallet.accountId, wallet.testnet, hash) ?: return
        val tx = historyHelper.mapping(
            wallet = wallet,
            event = event,
            options = ActionOptions(
                safeMode = settingsRepository.isSafeModeEnabled(api),
            )
        ).filterIsInstance<HistoryItem.Event>().firstOrNull() ?: return
        openScreen(TransactionScreen.newInstance(tx))
    }

    private suspend fun signData(
        wallet: WalletEntity,
        connection: AppConnectEntity,
        payload: SignDataRequestPayload,
        eventId: Long
    ) {
        try {
            val proof = SignDataScreen.run(context, wallet, connection.appUrl, payload)
            tonConnectManager.sendSignDataResponseSuccess(
                connection,
                proof,
                wallet.address,
                payload,
                eventId
            )
        } catch (e: Throwable) {
            DevSettings.tonConnectLog("Error while signing data: ${e.bestMessage}", error = true)
            if (e is CancellationException) {
                tonConnectManager.showLogoutAppBar(wallet, context, connection.appUrl)
                tonConnectManager.sendBridgeError(
                    connection,
                    BridgeError.userDeclinedTransaction(),
                    eventId
                )
            } else {
                tonConnectManager.sendBridgeError(
                    connection,
                    BridgeError.unknown(e.bestMessage),
                    eventId
                )
            }
        }
    }

    suspend fun isScamAddress(address: String, testnet: Boolean): Boolean {
        return api.resolveAccount(address, testnet)?.isScam ?: false
    }
}
