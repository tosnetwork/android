package network.tos.wallet.app.ui.screen.init

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import network.tos.blockchain.MnemonicHelper
import network.tos.blockchain.TosV1Mnemonic
import network.tos.blockchain.ton.AndroidSecureRandom
import network.tos.blockchain.ton.EntropyHelper
import network.tos.blockchain.ton.TonMnemonic
import network.tos.blockchain.ton.TonNetwork
import network.tos.blockchain.ton.contract.BaseWalletContract
import network.tos.blockchain.ton.contract.TosWalletV5R1Contract
import network.tos.blockchain.ton.contract.WalletVersion
import network.tos.blockchain.ton.extensions.EmptyPrivateKeyEd25519
import network.tos.blockchain.ton.extensions.toAccountId
import network.tos.blockchain.ton.extensions.toRawAddress
import network.tos.blockchain.ton.extensions.toWalletAddress
import network.tos.emoji.Emoji
import network.tos.extensions.MutableEffectFlow
import network.tos.extensions.logError
import network.tos.icu.Coins
import network.tos.icu.CurrencyFormatter
import network.tos.security.clear
import network.tos.wallet.app.Environment
import network.tos.wallet.app.core.AnalyticsHelper
import network.tos.wallet.app.extensions.consistentBucketFor
import network.tos.wallet.app.manager.push.PushManager
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.screen.init.list.AccountItem
import network.tos.wallet.app.ui.screen.watchonly.WatchInfoScreen
import network.tos.wallet.app.worker.PushToggleWorker
import network.tos.wallet.app.worker.TotalBalancesWorker
import network.tos.uikit.list.ListCell
import network.tos.wallet.api.API
import network.tos.wallet.api.entity.AccountDetailsEntity
import network.tos.wallet.api.entity.TokenEntity
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.Wallet
import network.tos.wallet.data.account.WalletColor
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.account.pq.QuantumNetworkProfile
import network.tos.wallet.data.backup.BackupRepository
import network.tos.wallet.data.passcode.PasscodeManager
import network.tos.wallet.data.rn.RNLegacy
import network.tos.wallet.data.settings.SafeModeState
import network.tos.wallet.data.settings.SettingsRepository
import network.tos.wallet.localization.Localization
import io.tonapi.models.AccountStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ton.api.pub.PublicKeyEd25519
import org.ton.block.AddrStd
import org.ton.mnemonic.Mnemonic
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.String

@OptIn(FlowPreview::class)
class InitViewModel(
    app: Application,
    args: InitArgs,
    private val passcodeManager: PasscodeManager,
    private val accountRepository: AccountRepository,
    private val api: API,
    private val backupRepository: BackupRepository,
    private val rnLegacy: RNLegacy,
    private val settingsRepository: SettingsRepository,
    private val environment: Environment,
    private val analytics: AnalyticsHelper,
    savedStateHandle: SavedStateHandle
) : BaseWalletVM(app) {

    private val entropyHelper: EntropyHelper by lazy {
        EntropyHelper(context)
    }

    val installId: String
        get() = settingsRepository.installId

    private val savedState = InitModelState(savedStateHandle)
    private val type = args.type
    private val testnet: Boolean = type == InitArgs.Type.Testnet
    private val walletsCount = AtomicInteger(-1)
    val watchRecoveryAccountId = args.watchRecoveryAccountId

    private val walletLabelHelper: WalletLabelHelper by lazy {
        WalletLabelHelper(context)
    }

    private val tonNetwork: TonNetwork
        get() = if (testnet) TonNetwork.TESTNET else TonNetwork.MAINNET

    private val _uiTopOffset = MutableStateFlow(0)
    val uiTopOffset = _uiTopOffset.asStateFlow()

    private val _eventFlow = MutableEffectFlow<InitEvent>()
    val eventFlow = _eventFlow.asSharedFlow().filterNotNull()

    private val _routeFlow = MutableEffectFlow<InitRoute>()
    val routeFlow = _routeFlow.asSharedFlow().filterNotNull()

    private val _watchAccountResolveFlow = MutableStateFlow<String>("")
    val watchAccountFlow = _watchAccountResolveFlow.asSharedFlow()
        .debounce(1000)
        .filter { it.isNotBlank() }
        .map {
            val account = api.resolveAddressOrName(it, testnet)
            if (account == null || account.walletVersion == WalletVersion.UNKNOWN) {
                setWatchAccount(null, null)
                return@map null
            }
            setWatchAccount(it, account)
            account
        }.flowOn(Dispatchers.IO)

    private val _accountsFlow = MutableEffectFlow<List<AccountItem>?>()
    val accountsFlow = _accountsFlow.asSharedFlow().filterNotNull()

    val labelFlow = savedState.labelFlow

    private val isPinSet = AtomicBoolean(false)
    private val executing = AtomicBoolean(false)
    private var generatedMnemonic: List<String>? = null
    // Keep Quantum recovery phrases process-local; SavedStateHandle can be
    // serialized with activity state. An interrupted recovery can be re-entered.
    private var quantumRecoveryMnemonic: List<String>? = null
    private var quantumRecoveryProfile: QuantumNetworkProfile? = null
    private var newWalletNetworkProfile: QuantumNetworkProfile? = null
    private var hadPinAtFlowStart = false
    private var recoveryPhraseAuthorized = false
    private var recoveryPhraseWasRevealed = false
    private var recoveryChallengeIndices: List<Int> = emptyList()

    private val requestSetPinCode: Boolean
        get() = (type == InitArgs.Type.New || type == InitArgs.Type.Import || type == InitArgs.Type.Testnet) && !isPinSet.get()

    var wordsCount: Int
        get() = savedState.wordsCount
        set(value) {
            savedState.wordsCount = value
        }

    init {
        args.quantumCreationId?.let { savedState.quantumCreationId = java.util.UUID.fromString(it).toString() }
        savedState.publicKey = args.publicKey?.let {
            InitModelState.PublicKey(publicKey = it)
        }

        savedState.ledgerConnectData = args.ledgerConnectData
        savedState.keystone = args.keystone

        viewModelScope.launch(Dispatchers.IO) {
            setLoading(true)
            isPinSet.set(passcodeManager.hasPinCode())
            hadPinAtFlowStart = isPinSet.get()
            if (type == InitArgs.Type.New) savedState.quantumRecoveryPhraseConfirmed = false

            args.labelName?.let(::setInitName)

            if (type == InitArgs.Type.New) {
                withContext(Dispatchers.Main) {
                    entropyHelper.start()
                }
            }

            try {
                if (savedState.quantumCreationId != null) check(isPinSet.get()) { "Existing creation requires its PIN" }
                start()
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) {
                setLoading(false)
                if (error is AccountRepository.PendingQuantumPhraseIdentityMismatch) {
                    _eventFlow.tryEmit(InitEvent.PendingPhraseIdentityMismatch)
                } else {
                    _eventFlow.tryEmit(InitEvent.CreationFailed)
                }
            }
        }
    }

    private suspend fun start() {
        setLabel(
            name = getDefaultWalletName(),
            emoji = Emoji.WALLET_ICON,
            color = WalletColor.all.first()
        )

        when (type) {
            InitArgs.Type.Watch -> routeTo(InitRoute.WatchAccount)
            InitArgs.Type.Import, InitArgs.Type.Testnet -> routeTo(InitRoute.ImportWords)
            InitArgs.Type.Signer, InitArgs.Type.SignerQR -> resolveWallets(savedState.publicKey!!)
            InitArgs.Type.Ledger -> routeTo(InitRoute.SelectAccount)
            InitArgs.Type.Keystone -> {
                setLabel(
                    name = getDefaultWalletName(),
                    emoji = Emoji.WALLET_ICON,
                    color = WalletColor.all.first()
                )
                routeTo(InitRoute.LabelAccount)
            }

            InitArgs.Type.New -> {
                val pendingId = savedState.quantumCreationId
                if (pendingId == null) {
                    generateNewWallet()
                } else {
                    generatedMnemonic = requireNotNull(accountRepository.pendingQuantumMnemonic(pendingId)) {
                        "The unfinished wallet recovery phrase is unavailable"
                    }.toList()
                    newWalletNetworkProfile = accountRepository.pendingQuantumProfile(pendingId)
                }
                if (requestSetPinCode) {
                    routeTo(InitRoute.CreatePasscode)
                } else if (environment.isGooglePlayServicesAvailable) {
                    routeTo(InitRoute.Push)
                } else {
                    routeAfterSetup()
                }
            }
        }
    }

    private fun routeTo(route: InitRoute) {
        _routeFlow.tryEmit(route)
        setLoading(false)
    }

    fun enablePush(enable: Boolean) {
        savedState.enablePush = enable
        routeAfterSetup()
    }

    private fun routeAfterSetup() {
        if (type == InitArgs.Type.New && !savedState.quantumRecoveryPhraseConfirmed) {
            routeTo(InitRoute.QuantumRecoveryPhrase)
        } else {
            routeTo(InitRoute.LabelAccount)
        }
    }

    fun toggleAccountSelection(address: String, selected: Boolean): Boolean {
        val items = getAccounts().toMutableList()
        val index = items.indexOfFirst { it.address.toRawAddress() == address }
        if (index == -1) {
            return false
        }
        val oldItem = items[index]
        if (oldItem.selected == selected) {
            return true
        }
        val newItem = oldItem.copy(selected = selected)
        items[index] = newItem
        setAccounts(items.toList())
        return true
    }

    private fun setLoading(loading: Boolean) {
        _eventFlow.tryEmit(InitEvent.Loading(loading))
    }

    fun setUiTopOffset(offset: Int) {
        _uiTopOffset.value = offset
    }

    fun routePopBackStack() {
        _eventFlow.tryEmit(InitEvent.Back)
    }

    fun resolveWatchAccount(value: String) {
        _watchAccountResolveFlow.tryEmit(value)
    }

    fun setPasscode(passcode: String) {
        savedState.passcode = passcode

        routeTo(InitRoute.ReEnterPasscode)
    }

    fun reEnterPasscode(passcode: String) {
        val valid = savedState.passcode == passcode
        if (!valid) {
            routePopBackStack()
        } else if (watchRecoveryAccountId != null) {
            execute(context)
        } else {
            if (environment.isGooglePlayServicesAvailable) routeTo(InitRoute.Push) else routeAfterSetup()
        }
    }

    suspend fun revealNewWalletRecoveryPhrase(context: Context): List<String>? {
        if (type != InitArgs.Type.New) return null
        if (newWalletRecoveryProfile() == null) return null
        if (hadPinAtFlowStart && !recoveryPhraseAuthorized) {
            if (!passcodeManager.confirmation(context, context.getString(Localization.app_name))) return null
            recoveryPhraseAuthorized = true
        }
        val words = generatedMnemonic ?: return null
        recoveryPhraseWasRevealed = true
        return words.toList()
    }

    suspend fun newWalletRecoveryProfile(): QuantumNetworkProfile? = when (val pendingId = savedState.quantumCreationId) {
        null -> newWalletNetworkProfile ?: QuantumNetworkProfile.effective(context)
        else -> accountRepository.pendingQuantumProfile(pendingId)
    }

    fun beginNewWalletRecoveryCheck() {
        check(type == InitArgs.Type.New && recoveryPhraseWasRevealed)
        val words = requireNotNull(generatedMnemonic)
        recoveryChallengeIndices = words.indices.shuffled().take(3).sorted()
        routeTo(InitRoute.QuantumRecoveryCheck)
    }

    fun newWalletRecoveryCheckIndices(): List<Int>? = recoveryChallengeIndices.takeIf {
        type == InitArgs.Type.New && generatedMnemonic != null && recoveryPhraseWasRevealed && it.size == 3
    }?.toList()

    fun newWalletRecoveryWordIsCorrect(index: Int, answer: String): Boolean {
        val wordIndex = recoveryChallengeIndices.getOrNull(index) ?: return false
        val expected = generatedMnemonic?.getOrNull(wordIndex) ?: return false
        val actual = TosV1Mnemonic.normalize(listOf(answer)).singleOrNull() ?: return false
        return actual == expected
    }

    fun confirmNewWalletRecoveryPhrase(answers: List<String>): Boolean {
        val words = generatedMnemonic ?: return false
        if (!recoveryPhraseWasRevealed || recoveryChallengeIndices.size != 3 || answers.size != 3) return false
        val normalized = answers.map { TosV1Mnemonic.normalize(listOf(it)).singleOrNull() ?: "" }
        if (normalized != recoveryChallengeIndices.map(words::get)) return false
        savedState.quantumRecoveryPhraseConfirmed = true
        // The challenge fragment remains in the back stack. Keep its indices
        // for this phrase until a new phrase is generated or the flow ends.
        routeTo(InitRoute.LabelAccount)
        return true
    }

    suspend fun setMnemonic(words: List<String>, profileOverride: TosV1Mnemonic.Profile? = null): Boolean {
        val normalized = TosV1Mnemonic.normalize(words)
        val profile = profileOverride ?: TosV1Mnemonic.recoveryProfile(normalized) ?: return false
        if (resolveWallets(normalized, profile)) {
            quantumRecoveryMnemonic = null
            quantumRecoveryProfile = null
            savedState.mnemonicProfile = profile
            savedState.mnemonic = normalized
            savedState.quantumRecovery = false
            return true
        }
        return false
    }

    suspend fun setQuantumMnemonic(words: List<String>, confirmedProfile: QuantumNetworkProfile): Boolean {
        val normalized = TosV1Mnemonic.normalize(words)
        if (normalized.size != 24 || !TosV1Mnemonic.isValid(normalized)) return false
        savedState.mnemonicProfile = TosV1Mnemonic.Profile.TOS
        quantumRecoveryMnemonic = normalized.toList()
        quantumRecoveryProfile = confirmedProfile
        // Remove any phrase retained by an earlier recovery attempt/version.
        savedState.mnemonic = null
        savedState.quantumRecovery = true
        savedState.accounts = null
        if (requestSetPinCode) {
            applyAccountName(emptyList())
            routeTo(InitRoute.CreatePasscode)
        } else {
            routeTo(if (environment.isGooglePlayServicesAvailable) InitRoute.Push else InitRoute.LabelAccount)
        }
        return true
    }

    suspend fun preflightQuantumRecovery(profile: QuantumNetworkProfile) {
        withContext(Dispatchers.IO) {
            accountRepository.preflightQuantumNetwork(confirmedProfile = profile)
        }
    }

    fun quantumRecoveryNetworkUnavailable() {
        _eventFlow.tryEmit(InitEvent.QuantumRecoveryNetworkUnavailable)
    }

    private suspend fun resolveWallets(mnemonic: List<String>, profile: TosV1Mnemonic.Profile): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val privateKey = TosV1Mnemonic.recoveryPrivateKey(mnemonic, profile)
                val publicKey = privateKey.publicKey()
                setPublicKey(publicKey)
                resolveWallets(InitModelState.PublicKey(publicKey = publicKey), nativeTos = profile == TosV1Mnemonic.Profile.TOS)
                true
            } catch (e: Throwable) {
                false
            }
        }

    private suspend fun resolveWallets(publicKey: InitModelState.PublicKey, nativeTos: Boolean = true) =
        withContext(Dispatchers.IO) {
            val node = api.tos.snapshot(testnet)
            val targetVersion = if (nativeTos) WalletVersion.TOSV5R1 else WalletVersion.V5R1
            val versions = if (nativeTos) listOf(targetVersion) else listOf(
                WalletVersion.V5R1, WalletVersion.V5BETA, WalletVersion.V4R2,
                WalletVersion.V4R1, WalletVersion.V3R2, WalletVersion.V3R1)
            val networkId = if (nativeTos) node.getNetworkInfo(testnet).requireNativeV5().globalId else tonNetwork.value
            val candidates = mutableListOf<LegacyRecoverySelection.Candidate>()
            val list = versions.mapIndexed { index, version ->
                val contract = BaseWalletContract.create(publicKey.publicKey, version.title, networkId)
                val address = contract.address.toAccountId()
                // Derive every supported address locally: an unavailable public-key
                // index cannot hide a funded classic version during phrase recovery.
                val state = if (publicKey.new) null else node.getRecoveryAccountState(address, testnet)
                val assets = if (publicKey.new) null else try { node.getRecoveryAssets(address, testnet) }
                    catch (error: kotlinx.coroutines.CancellationException) { throw error }
                    catch (_: Exception) { null }
                val balance = state?.balance ?: java.math.BigInteger.ZERO
                require(balance.signum() >= 0)
                val hasAssets = balance.signum() > 0 || assets?.tokens == true || assets?.nfts == true
                val deployed = state?.status in setOf("active", "frozen")
                candidates.add(LegacyRecoverySelection.Candidate(address, version, hasAssets,
                    assetsKnown = publicKey.new || assets?.complete == true, deployed = deployed))
                AccountItem(address = contract.address.toWalletAddress(testnet), name = null,
                    walletVersion = version, balanceFormat = CurrencyFormatter.format("TOS", Coins.ofNano(balance.toString())),
                    tokens = assets?.tokens == true, collectibles = assets?.nfts == true,
                    selected = false, position = ListCell.getPosition(versions.size, index),
                    initialized = deployed)
            }
            val recoveryWallet = getRecoveryWatchWallet(publicKey = publicKey.publicKey)
            if (watchRecoveryAccountId != null) requireNotNull(recoveryWallet)
            val selectedIndices = LegacyRecoverySelection.select(candidates, targetVersion, recoveryWallet?.accountId)
            val items = selectedIndices.mapIndexed { index, selected -> list[selected].copy(selected = true,
                position = ListCell.getPosition(selectedIndices.size, index)) }
            setAccounts(items.toList())

            if (recoveryWallet != null) {
                savedState.enablePush = settingsRepository.getPushWallet(recoveryWallet.id)
            }

            if (recoveryWallet != null && !requestSetPinCode) {
                execute(context)
            } else if (requestSetPinCode) {
                applyAccountName(items)
                routeTo(InitRoute.CreatePasscode)
            } else {
                routeTo(if (environment.isGooglePlayServicesAvailable) InitRoute.Push else InitRoute.LabelAccount)
            }
        }


    private fun setInitName(name: String) {
        if (name.isBlank()) {
            return
        }

        val (parsedName, parsedEmoji) = walletLabelHelper.parseNameAndEmoji(listOf(name))

        if (parsedName.isNullOrBlank() && parsedEmoji.isNullOrEmpty()) {
            return
        }

        setLabel(
            Wallet.Label(
                accountName = parsedName ?: "",
                emoji = parsedEmoji ?: "",
            )
        )
    }

    private suspend fun applyAccountName(accounts: List<AccountItem>) {
        val names = accounts.mapNotNull { it.name?.trim() }

        val (emoji, color) = generateLabel(names)

        setLabel(
            Wallet.Label(
                accountName = names.firstOrNull() ?: getDefaultWalletName(),
                emoji = emoji ?: Emoji.WALLET_ICON,
                color = color ?: WalletColor.all.first()
            )
        )
    }

    private fun applyAccountName() {
        viewModelScope.launch {
            applyAccountName(getSelectedAccounts())
        }
    }

    private fun setPublicKey(publicKey: PublicKeyEd25519?) {
        if (publicKey == null || publicKey == EmptyPrivateKeyEd25519.publicKey()) {
            savedState.publicKey = null
        } else {
            savedState.publicKey = InitModelState.PublicKey(publicKey = publicKey)
        }
    }

    private suspend fun setWatchAccount(
        query: String?,
        account: AccountDetailsEntity?
    ) {
        if (account == null) {
            setPublicKey(null)
            savedState.watchAccount = null
            setLabel(Wallet.Label())
        }

        val oldAccount = getWatchAccount()
        if (oldAccount?.equals(account) == true) {
            return
        }

        val publicKey = account?.address?.let {
            api.safeGetPublicKey(it, testnet)
        }
        setPublicKey(publicKey)

        savedState.watchAccount = account

        val names = listOfNotNull(account?.name, query)
        val (generatedEmoji, generatedColor) = generateLabel(names)
        val accountName = account?.name ?: getDefaultWalletName()

        setLabel(
            Wallet.Label(
                accountName = accountName,
                emoji = generatedEmoji ?: Emoji.WALLET_ICON,
                color = generatedColor ?: WalletColor.all.first()
            )
        )
    }

    private suspend fun getDefaultWalletName(): String {
        val count = getWalletsCount()
        return if (count == 0 && type == InitArgs.Type.New) {
            getString(Localization.app_name)
        } else {
            getString(Localization.wallet)
        }
    }

    private suspend fun getWalletsCount(): Int {
        val count = walletsCount.get()
        return if (0 > count) {
            accountRepository.getWallets().size.also {
                walletsCount.set(it)
            }
        } else {
            count
        }
    }

    fun getWatchAccount(): AccountDetailsEntity? {
        return savedState.watchAccount
    }

    private fun getAccounts(): List<AccountItem> {
        return (savedState.accounts ?: emptyList())
    }

    fun setAccounts(accounts: List<AccountItem>) {
        savedState.accounts = accounts.map { it.copy() }
        _accountsFlow.tryEmit(accounts.map { it.copy() })
    }

    private fun getSelectedAccounts(): List<AccountItem> {
        return getAccounts().toList().filter { it.selected }
    }

    fun setLabel(name: String, emoji: String, color: Int) {
        setLabel(Wallet.Label(name, emoji, color))
    }

    private fun setLabel(label: Wallet.Label) {
        savedState.label = label
    }

    private suspend fun generateLabel(names: List<String> = emptyList()): Pair<CharSequence?, Int?> {
        val publicKey = savedState.publicKey?.publicKey?.key?.toByteArray()
        return walletLabelHelper.generate(names, publicKey)
    }

    private suspend fun getLabel(): Wallet.Label = withContext(Dispatchers.IO) {
        val savedLabel = savedState.label
        if (savedLabel != null && !savedLabel.isEmpty) {
            savedLabel
        } else {
            Wallet.Label(
                accountName = "",
                emoji = "",
                color = Color.TRANSPARENT
            )
        }
    }

    fun nextStep(context: Context, from: InitRoute) {
        if (from == InitRoute.CreatePasscode) {
            routeTo(InitRoute.ReEnterPasscode)
        } else if (from == InitRoute.LabelAccount) {
            execute(context)
        } else if (from == InitRoute.WatchAccount) {
            routeTo(if (environment.isGooglePlayServicesAvailable) InitRoute.Push else InitRoute.LabelAccount)
        } else if (from == InitRoute.SelectAccount && !requestSetPinCode) {
            applyAccountName()
            routeTo(if (environment.isGooglePlayServicesAvailable) InitRoute.Push else InitRoute.LabelAccount)
        } else if (requestSetPinCode) {
            applyAccountName()
            routeTo(InitRoute.CreatePasscode)
        } else {
            execute(context)
        }
    }

    suspend fun getRecoveryWatchWallet(mnemonic: List<String>, profile: TosV1Mnemonic.Profile? = null) = getRecoveryWatchWallet(
        TosV1Mnemonic.recoveryPrivateKey(mnemonic, profile ?: savedState.mnemonicProfile).publicKey()
    )

    suspend fun getRecoveryWatchWallet(publicKey: PublicKeyEd25519): WalletEntity? = withContext(Dispatchers.IO) {
        if (watchRecoveryAccountId == null) {
            return@withContext null
        }

        val wallet = accountRepository.getWallets()
            .first { it.accountId == watchRecoveryAccountId && it.type == Wallet.Type.Watch }

        if (wallet.classicalPublicKey == publicKey) {
            wallet
        } else {
            null
        }
    }

    private fun execute(context: Context) {
        if (!executing.compareAndSet(false, true)) return
        setLoading(true)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (requestSetPinCode) {
                    // A failed attempt may already have persisted the PIN. Never overwrite it on retry.
                    val code = requireNotNull(savedState.passcode)
                    if (passcodeManager.hasPinCode()) check(passcodeManager.isValid(context, code))
                    passcodeManager.save(code)
                    if (type == InitArgs.Type.New) isPinSet.set(true)
                }

                val alreadyWalletCount = accountRepository.getWallets()
                if (alreadyWalletCount.isEmpty() && api.config.flags.safeModeEnabled) {
                    settingsRepository.setSafeModeState(SafeModeState.Enabled)
                    if (type == InitArgs.Type.Import || type == InitArgs.Type.Testnet) {
                        settingsRepository.showSafeModeSetup = true
                    }
                }

                val wallets = mutableListOf<WalletEntity>()
                when (type) {
                    InitArgs.Type.Watch -> wallets.add(saveWatchWallet())
                    InitArgs.Type.New -> wallets.add(newWallet(context))
                    InitArgs.Type.Import, InitArgs.Type.Testnet -> wallets.addAll(
                        importWallet(
                            context
                        )
                    )

                    InitArgs.Type.Signer -> wallets.addAll(signerWallets(false))
                    InitArgs.Type.SignerQR -> wallets.addAll(signerWallets(true))
                    InitArgs.Type.Ledger -> wallets.addAll(ledgerWallets())
                    InitArgs.Type.Keystone -> wallets.addAll(keystoneWallet())
                }

                if (type == InitArgs.Type.Import || type == InitArgs.Type.Testnet || type == InitArgs.Type.New) {
                    backupRepository.addBackups(wallets.map { it.id })
                }

                if (savedState.enablePush) {
                    withContext(Dispatchers.Main) {
                        PushToggleWorker.run(context, wallets, PushManager.State.Enable)
                    }
                }

                withContext(Dispatchers.Main) {
                    TotalBalancesWorker.run(context)
                }

                val selectedWallet = wallets.minByOrNull { it.version }!!
                if (type == InitArgs.Type.New || savedState.quantumRecovery) {
                    accountRepository.completeQuantumCreation(selectedWallet.id)
                    if (savedState.quantumRecovery) {
                        quantumRecoveryMnemonic = null
                        quantumRecoveryProfile = null
                        savedState.mnemonic = null
                        savedState.quantumRecovery = false
                    }
                } else accountRepository.setSelectedWallet(selectedWallet.id)

                if (type == InitArgs.Type.Watch) {
                    openScreen(WatchInfoScreen.newInstance(selectedWallet))
                }

                finish()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                context.logError(e)
                setLoading(false)
                _eventFlow.tryEmit(InitEvent.CreationFailed)
            } finally { executing.set(false) }
        }
    }

    private suspend fun buildNewLabel(accounts: List<SimpleAccount>): Wallet.NewLabel {
        val walletsCount = getWalletsCount()
        val label = getLabel()
        val names = LegacyRecoverySelection.names(label.name, accounts.size, walletsCount,
            label.name == getDefaultWalletName())

        val (generatedEmoji, generatedColor) = generateLabel(names)

        val emoji = label.emoji.ifBlank {
            generatedEmoji
        }

        val color = if (label.color == Color.TRANSPARENT) {
            generatedColor
        } else {
            label.color
        }

        return Wallet.NewLabel(
            names = names,
            emoji = emoji ?: Emoji.WALLET_ICON,
            color = color ?: WalletColor.all.first()
        )
    }

    private suspend fun buildNewLabel(account: SimpleAccount): Wallet.NewLabel {
        return buildNewLabel(listOf(account))
    }

    private suspend fun saveWatchWallet(): WalletEntity {
        val account = getWatchAccount() ?: throw IllegalStateException("Account is not set")
        val publicKey = savedState.publicKey?.publicKey ?: EmptyPrivateKeyEd25519.publicKey()

        val label = buildNewLabel(SimpleAccount(account))

        return accountRepository.addWatchWallet(label, publicKey, account.walletVersion)
    }

    private suspend fun generateNewWallet() = withContext(Dispatchers.IO) {
        val profile = QuantumNetworkProfile.effective(context)
        // Every new-wallet entry point must authenticate the same profile that
        // will be displayed with the backup and used when publishing the wallet.
        accountRepository.preflightQuantumNetwork(confirmedProfile = profile)
        newWalletNetworkProfile = profile
        AndroidSecureRandom.seed(entropyHelper.getSeed(512))
        val mnemonic = TosV1Mnemonic.generate(random = AndroidSecureRandom)
        // New-wallet secrets remain in the ViewModel, not in saved instance state.
        generatedMnemonic = mnemonic
        savedState.quantumRecoveryPhraseConfirmed = false
        recoveryPhraseAuthorized = false
        recoveryPhraseWasRevealed = false
        recoveryChallengeIndices = emptyList()

        val (emoji, color) = generateLabel()

        setLabel(
            name = getDefaultWalletName(),
            emoji = emoji?.toString() ?: Emoji.WALLET_ICON,
            color = color ?: WalletColor.all.first()
        )
    }

    private suspend fun newWallet(context: Context): WalletEntity {
        check(savedState.quantumRecoveryPhraseConfirmed) { "Save and confirm the recovery phrase before creating the wallet" }
        if (!recoveryPhraseAuthorized) {
            check(passcodeManager.confirmation(context, context.getString(Localization.app_name))) { "Creation authentication cancelled" }
        }
        val label = buildNewLabel(SimpleAccount(version = WalletVersion.QUANTUM_SELF_PAY))
        val pending = savedState.quantumCreationId
        val wallet = if (pending != null) {
            accountRepository.resumeQuantumCreation(java.util.UUID.fromString(pending), label,
                backupConfirmed = savedState.quantumRecoveryPhraseConfirmed, mnemonic = generatedMnemonic)
        } else {
            val mnemonic = requireNotNull(generatedMnemonic) { "Wallet entropy is not ready" }
            val profile = requireNotNull(newWalletNetworkProfile) { "Trusted Quantum network profile is unavailable" }
            accountRepository.addNewWallet(label, mnemonic, profile,
                backupConfirmed = savedState.quantumRecoveryPhraseConfirmed) {
                savedState.quantumCreationId = it.toString()
            }
        }
        generatedMnemonic = null
        newWalletNetworkProfile = null

        analytics.simpleTrackEvent(
            "wallet_generate",
            hashMapOf("wallet_type" to wallet.version.title)
        )
        return wallet
    }

    private suspend fun importWallet(context: Context): List<WalletEntity> =
        withContext(Dispatchers.IO) {
            if (savedState.quantumRecovery) {
                val mnemonic = quantumRecoveryMnemonic ?: throw IllegalStateException(
                    "Recovery phrase is no longer available in memory. Enter it again to continue."
                )
                val profile = quantumRecoveryProfile ?: throw IllegalStateException(
                    "Confirmed recovery network is no longer available. Review the network and enter the phrase again."
                )
                require(TosV1Mnemonic.isValid(mnemonic) && mnemonic.size == 24) { "A valid 24-word native TOS phrase is required" }
                val label = buildNewLabel(SimpleAccount(version = WalletVersion.QUANTUM_SELF_PAY))
                val wallet = accountRepository.restoreQuantumWallet(label, mnemonic, profile)
                savedState.quantumCreationId = wallet.id
                analytics.simpleTrackEvent("wallet_import", hashMapOf("wallet_type" to wallet.version.title))
                return@withContext listOf(wallet)
            }
            val accounts = getSelectedAccounts()
            if (accounts.isEmpty()) {
                throw IllegalStateException("Wallet versions are not set")
            }

            val mnemonic = savedState.mnemonic ?: throw IllegalStateException("Mnemonic is not set")
            TosV1Mnemonic.recoveryPrivateKey(mnemonic, savedState.mnemonicProfile)

            val ids = accounts.map { AccountRepository.newWalletId() }
            saveMnemonic(context, ids, mnemonic)

            val recoveryWallet = getRecoveryWatchWallet(mnemonic)

            val label = recoveryWallet?.let {
                Wallet.NewLabel(
                    names = listOf(it.label.name),
                    emoji = it.label.emoji,
                    color = it.label.color
                )
            } ?: buildNewLabel(accounts.map {
                SimpleAccount(
                    name = it.name,
                    version = it.walletVersion
                )
            })

            accounts.map {
                analytics.simpleTrackEvent(
                    "wallet_import",
                    hashMapOf("wallet_type" to it.walletVersion.title)
                )
            }

            val wallets = accountRepository.importWallet(
                ids,
                label,
                mnemonic,
                accounts.map { it.walletVersion },
                testnet,
                accounts.map { it.initialized })

            // delete watch only wallet
            recoveryWallet?.let {
                PushToggleWorker.run(context, recoveryWallet, PushManager.State.Delete)
                accountRepository.delete(it)
            }

            if (!testnet) {
                checkTronBalance(wallets)
            }

            wallets
        }

    private suspend fun checkTronBalance(wallets: List<WalletEntity>) {
        val wallet = wallets.first()
        val tronAddress = accountRepository.getTronAddress(wallet.id) ?: return
        val balance = api.tron.getTronUsdtBalance(tronAddress)

        if (balance.value.isPositive) {
            wallets.forEach {
                settingsRepository.setTokenHidden(it.id, TokenEntity.TRON_USDT.address, false)
                settingsRepository.setTokenPinned(it.id, TokenEntity.TRON_USDT.address, true)
                settingsRepository.setTokensSort(
                    wallet.id,
                    listOf(
                        TokenEntity.USDT.address,
                        TokenEntity.TRON_USDT.address,
                        TokenEntity.USDE.address
                    )
                )
            }
        }
    }

    private suspend fun ledgerWallets(): List<WalletEntity> {
        val ledgerConnectData = savedState.ledgerConnectData
            ?: throw IllegalStateException("Ledger connect data is not set")

        val accounts = getSelectedAccounts()

        val ledgerAccounts = accounts.map { selectedAccount ->
            ledgerConnectData.accounts.find { account ->
                account.path.index == selectedAccount.ledgerIndex
            } ?: throw IllegalStateException("Ledger account is not found")
        }

        val label = buildNewLabel(accounts.map {
            SimpleAccount(
                name = it.name,
                version = WalletVersion.V4R2
            )
        })

        return accountRepository.pairLedger(
            label = label,
            ledgerAccounts = ledgerAccounts,
            deviceId = ledgerConnectData.deviceId,
            initialized = accounts.map { it.initialized }
        )
    }

    private suspend fun signerWallets(qr: Boolean): List<WalletEntity> {
        val accounts = getSelectedAccounts()
        val publicKey = savedState.publicKey ?: throw IllegalStateException("Public key is not set")
        val label = buildNewLabel(accounts.map {
            SimpleAccount(
                name = it.name,
                version = it.walletVersion
            )
        })

        return accountRepository.pairSigner(
            label,
            publicKey.publicKey,
            accounts.map { it.walletVersion },
            qr,
            accounts.map { it.initialized })
    }

    private suspend fun keystoneWallet(): List<WalletEntity> {
        val publicKey = savedState.publicKey ?: throw IllegalStateException("Public key is not set")
        val keystone = savedState.keystone ?: throw IllegalStateException("Keystone is not set")

        val label = buildNewLabel(
            SimpleAccount(
                version = WalletVersion.V4R2
            )
        )

        val contact = BaseWalletContract.create(
            publicKey.publicKey,
            WalletVersion.V4R2.title,
            tonNetwork.value
        )
        val account =
            api.resolveAccount(contact.address.toWalletAddress(testnet = testnet), testnet)
        val initialized =
            account != null && (account.status == AccountStatus.active || account.status == AccountStatus.frozen)

        return accountRepository.pairKeystone(label, publicKey.publicKey, keystone, initialized)
    }

    private suspend fun saveMnemonic(
        context: Context,
        walletIds: List<String>,
        mnemonic: List<String>
    ) = withContext(Dispatchers.IO) {
        if (requestSetPinCode) {
            return@withContext
        }
        if (passcodeManager.hasPinCode()) {
            val isValid =
                passcodeManager.confirmation(context, context.getString(Localization.app_name))
            if (!isValid) {
                throw IllegalStateException("wrong passcode")
            }
        } else {
            var passcode = savedState.passcode
            if (passcode == null) {
                passcode = withContext(Dispatchers.Main) {
                    passcodeManager.legacyGetPasscode(context)
                }
            }
            if (passcode.isNullOrBlank()) {
                throw IllegalStateException("wrong passcode")
            }
            rnLegacy.addMnemonics(passcode, walletIds, mnemonic)
        }
    }

    override fun onCleared() {
        super.onCleared()
        entropyHelper.stop()
    }

    private data class SimpleAccount(
        val name: String? = null,
        val version: WalletVersion
    ) {

        constructor(account: AccountDetailsEntity) : this(
            name = account.name,
            version = account.walletVersion
        )
    }
}
