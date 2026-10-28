package network.tos.wallet.data.account

import network.tos.blockchain.TosV1Mnemonic
import network.tos.wallet.data.account.entities.WalletIdentity
import network.tos.wallet.data.account.pq.QuantumCreationCustody
import network.tos.wallet.data.account.pq.QuantumWalletCreation
import network.tos.wallet.data.account.pq.QuantumNetworkProfile
import network.tos.wallet.data.account.pq.QuantumProofHttpTransport
import network.tos.wallet.data.account.pq.QuantumSelfPayNetwork
import network.tos.wallet.data.account.pq.QuantumAccountReader
import network.tos.wallet.data.account.pq.QuantumHistoryReader
import network.tos.wallet.data.account.pq.QuantumHistoryHttpTransport
import network.tos.wallet.data.account.pq.QuantumSelfPayAccount
import network.tos.security.pq.QuantumProofSession
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.tos.wallet.data.account.pq.QuantumCreationRecord
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

import android.app.KeyguardManager
import android.content.Context
import network.tos.blockchain.MnemonicHelper
import network.tos.blockchain.ton.contract.BaseWalletContract
import network.tos.blockchain.ton.contract.WalletVersion
import network.tos.blockchain.ton.extensions.base64
import network.tos.blockchain.ton.extensions.equalsAddress
import network.tos.blockchain.ton.extensions.hex
import network.tos.blockchain.ton.extensions.toAccountId
import network.tos.blockchain.tron.KeychainTrxAccountsProvider
import network.tos.ledger.ton.LedgerAccount
import network.tos.wallet.api.API
import network.tos.wallet.api.entity.value.Blockchain
import network.tos.wallet.api.entity.value.BlockchainAddress
import network.tos.wallet.data.account.entities.MessageBodyEntity
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.account.source.DatabaseSource
import network.tos.wallet.data.account.source.StorageSource
import network.tos.wallet.data.account.source.VaultSource
import network.tos.wallet.data.core.recordException
import network.tos.wallet.data.rn.RNLegacy
import network.tos.wallet.data.rn.data.RNWallet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ton.api.pk.PrivateKeyEd25519
import org.ton.api.pub.PublicKeyEd25519
import org.ton.contract.wallet.WalletTransfer
import org.ton.mnemonic.Mnemonic
import java.math.BigInteger
import java.util.UUID

class AccountRepository(
    private val context: Context,
    private val api: API,
    private val rnLegacy: RNLegacy,
) {

    companion object {
        fun newWalletId(): String {
            return UUID.randomUUID().toString()
        }
    }

    sealed class SelectedState {
        data object Initialization : SelectedState()
        data object Empty : SelectedState()
        data class Wallet(val wallet: WalletEntity) : SelectedState()
    }

    private val keyguardManager by lazy {
        context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val database = DatabaseSource(context)
    private val storageSource: StorageSource by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { StorageSource(context) }
    private val vaultSource: VaultSource by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { VaultSource(context) }
    private val quantumCreationMutex = Mutex()
    private val quantumHttpClient by lazy { OkHttpClient() }
    private val quantumCustody by lazy { QuantumCreationCustody(context) }
    private val migrationHelper = RNMigrationHelper(rnLegacy)

    private val _selectedStateFlow = MutableStateFlow<SelectedState>(SelectedState.Initialization)
    val selectedStateFlow = _selectedStateFlow.stateIn(
        scope,
        SharingStarted.Eagerly,
        SelectedState.Initialization
    )
    val selectedWalletFlow = selectedStateFlow.filterNotNull().filterIsInstance<SelectedState.Wallet>().map {
        it.wallet
    }.shareIn(scope, SharingStarted.Eagerly, 1).distinctUntilChanged()

    private val selectedId: String?
        get() = (selectedStateFlow.value as? SelectedState.Wallet)?.wallet?.id

    init {
        scope.launch(Dispatchers.IO) {
            if (rnLegacy.isRequestMainMigration()) {
                // database.clearAccounts()
                // storageSource.clear()
                migrationFromRN()
                rnLegacy.setWalletMigrated()
            }

            val selectedId = storageSource.getSelectedId()
            setSelectedWallet(selectedId)
        }
    }

    suspend fun setInitialized(id: String, initialized: Boolean) {
        try {
            database.setInitialized(id, initialized)
        } catch (e: Throwable) {
            recordException(e)
        }
    }

    suspend fun importPrivateKeysFromRNLegacy(passcode: String): Boolean = withContext(Dispatchers.IO) {
        val vaultState = migrationHelper.loadSecureStore(passcode)
        if (vaultState.hasError) {
            false
        } else {
            val list = vaultState.list()
            if (list.isNotEmpty()) {
                for (mnemonic in list) {
                    vaultSource.addMnemonic(mnemonic.mnemonic.split(" "))
                }
            }
            true
        }
    }

    fun addMnemonic(words: List<String>) {
        vaultSource.addMnemonic(words)
    }

    private suspend fun migrationFromRN() = withContext(Dispatchers.IO) {
        val (selectedId, wallets) = migrationHelper.loadLegacy()
        if (wallets.isNotEmpty()) {
            database.insertAccounts(wallets)
            for (wallet in wallets) {
                if (wallet.isQuantum) continue
                val token = rnLegacy.getTonProof(wallet.id) ?: continue
                storageSource.setTonProofToken(wallet.publicKey, token)
            }
            storageSource.setSelectedId(selectedId)
        }
    }

    private suspend fun addWalletToRN(wallet: WalletEntity) {
        rnLegacy.addWallet(wallet.toRNWallet())
    }

    fun editLabel(
        walletId: String,
        name: String,
        emoji: CharSequence,
        color: Int
    ) {
        scope.launch(scope.coroutineContext) {
            val selectedWallet = getSelectedWallet() ?: return@launch
            val isEditSelectedWallet = walletId.isBlank() || walletId == selectedWallet.id
            val wallet = if (isEditSelectedWallet) {
                selectedWallet
            } else {
                getWalletById(walletId) ?: return@launch
            }

            val newLabel = Wallet.Label(name, emoji, color)
            _selectedStateFlow.value = SelectedState.Wallet(wallet.copy(label = newLabel))

            database.editAccount(wallet.id, Wallet.Label(name, emoji, color))
            rnLegacy.edit(wallet.id, name, RNWallet.fixEmoji(emoji), color)
        }
    }

    suspend fun requestTonProofToken(wallet: WalletEntity): String? = withContext(Dispatchers.IO) {
        try {
            if (keyguardManager.isDeviceLocked) {
                return@withContext null
            }

            if (!wallet.hasPrivateKey) {
                return@withContext null
            }
            val token = storageSource.getTonProofToken(wallet.publicKey)
            if (token != null) {
                return@withContext token
            }
            val tonProofToken = createTonProofToken(wallet) ?: return@withContext null
            saveTonProof(wallet, tonProofToken)
            tonProofToken
        } catch (e: Throwable) {
            recordException(e)
            null
        }
    }


    private suspend fun saveTonProof(wallet: WalletEntity, token: String) = withContext(Dispatchers.IO) {
        storageSource.setTonProofToken(wallet.publicKey, token)

        val wallets = getWalletByPublicKey(wallet.publicKey, wallet.testnet)
        for (w in wallets) {
            rnLegacy.setTonProof(w.id, token)
        }
    }

    suspend fun getWallets() = database.getAccounts()

    suspend fun getUninitializedWallets() = database.getAccounts().filter { !it.initialized }

    suspend fun getInitializedWallets() = database.getAccounts().filter { it.initialized }

    fun getVaultKeys(): String {
        return vaultSource.getVaultKeys()
    }

    suspend fun getMnemonic(id: String): Array<String>? {
        val wallet = database.getAccount(id) ?: return null
        return if (wallet.isQuantum) quantumCustody.mnemonic(UUID.fromString(id))
            else wallet.classicalPublicKey?.let(vaultSource::getMnemonic)
    }

    suspend fun getPrivateKey(id: String): PrivateKeyEd25519? {
        val wallet = database.getAccount(id) ?: return null
        return wallet.classicalPublicKey?.let { vaultSource.getPrivateKey(it) }
    }

    private suspend fun getTrxAccountsProvider(id: String): KeychainTrxAccountsProvider? {
        val mnemonic = getMnemonic(id)?.toList() ?: return null
        if (MnemonicHelper.isValidStandardTonMnemonic(mnemonic)) {
            val entropy = Mnemonic.toEntropy(mnemonic)
            return KeychainTrxAccountsProvider.fromEntropy(entropy)
        } else {
            return KeychainTrxAccountsProvider.fromMnemonic(mnemonic)
        }
    }

    suspend fun getTronMnemonic(id: String): Array<String>? {
        val trxAccountsProvider = getTrxAccountsProvider(id) ?: return null
        return trxAccountsProvider.mnemonics.toTypedArray()
    }

    suspend fun getTronAddress(id: String): String? {
        val trxAccountsProvider = getTrxAccountsProvider(id) ?: return null
        return trxAccountsProvider.getAddress()
    }

    suspend fun getTronBlockchainAddress(id: String): BlockchainAddress? {
        val address = getTronAddress(id) ?: return null
        return BlockchainAddress(
            value = address,
            testnet = false,
            blockchain = Blockchain.TRON
        )
    }

    suspend fun getTronPrivateKey(id: String): BigInteger? {
        val trxAccountsProvider = getTrxAccountsProvider(id) ?: return null
        return trxAccountsProvider.getPrivateKey()
    }

    suspend fun pairLedger(
        label: Wallet.NewLabel,
        ledgerAccounts: List<LedgerAccount>,
        deviceId: String,
        initialized: List<Boolean>
    ): List<WalletEntity> {
        val list = mutableListOf<WalletEntity>()
        for ((index, account) in ledgerAccounts.withIndex()) {
            val entity = WalletEntity(
                id = newWalletId(),
                publicKey = account.publicKey,
                type = Wallet.Type.Ledger,
                version = WalletVersion.V4R2,
                label = label.create(index),
                ledger = WalletEntity.Ledger(
                    deviceId = deviceId,
                    accountIndex = account.path.index
                ),
                initialized = initialized[index]
            )
            list.add(entity)
        }

        insertWallets(list)
        return list.toList()
    }

    suspend fun pairSigner(
        label: Wallet.NewLabel,
        publicKey: PublicKeyEd25519,
        versions: List<WalletVersion>,
        qr: Boolean,
        initialized: List<Boolean>
    ): List<WalletEntity> {
        val type = if (qr) Wallet.Type.SignerQR else Wallet.Type.Signer
        return addWallet(versions.map { newWalletId() }, label, publicKey, versions, type, initialized = initialized)
    }

    suspend fun pairKeystone(
        label: Wallet.NewLabel,
        publicKey: PublicKeyEd25519,
        keystone: WalletEntity.Keystone,
        initialized: Boolean
    ): List<WalletEntity> {
        val entity = WalletEntity(
            id = newWalletId(),
            publicKey = publicKey,
            type = Wallet.Type.Keystone,
            version = WalletVersion.V4R2,
            label = label.create(0),
            keystone = keystone,
            initialized = initialized
        )

        val list = listOf(entity)
        insertWallets(list)
        return list
    }

    suspend fun importWallet(
        ids: List<String>,
        label: Wallet.NewLabel,
        mnemonic: List<String>,
        versions: List<WalletVersion>,
        testnet: Boolean,
        initialized: List<Boolean>
    ): List<WalletEntity> {
        require(versions.isNotEmpty()) { "Wallet version is required" }
        val nativeTos = WalletVersion.TOSV5R1 in versions
        require(!nativeTos || versions.all { it == WalletVersion.TOSV5R1 }) { "Cannot mix TOS and legacy mnemonic derivation" }
        require(if (nativeTos) TosV1Mnemonic.isValid(mnemonic) else TosV1Mnemonic.isLegacyValid(mnemonic)) { "Recovery phrase does not match wallet format" }
        val networkId = if (nativeTos) api.tos.getNetworkInfo(testnet).requireNativeV5().globalId else null
        val publicKey = vaultSource.addMnemonic(mnemonic, nativeTos = nativeTos)
        val type = if (testnet) Wallet.Type.Testnet else Wallet.Type.Default
        return addWallet(ids, label, publicKey, versions, type, initialized = initialized, networkGlobalId = networkId)
    }

    suspend fun addWallet(
        ids: List<String>,
        label: Wallet.NewLabel,
        publicKey: PublicKeyEd25519,
        versions: List<WalletVersion>,
        type: Wallet.Type,
        initialized: List<Boolean>,
        networkGlobalId: Int? = null,
    ): List<WalletEntity> {
        val networkId = networkGlobalId ?: if (WalletVersion.TOSV5R1 in versions) {
            api.tos.getNetworkInfo(type == Wallet.Type.Testnet).requireNativeV5().globalId
        } else null
        val list = mutableListOf<WalletEntity>()
        for ((index, version) in versions.withIndex()) {
            val entity = WalletEntity(
                id = ids.getOrNull(index) ?: newWalletId(),
                publicKey = publicKey,
                type = type,
                version = version,
                label = label.create(index),
                initialized = initialized[index],
                networkGlobalId = if (version == WalletVersion.TOSV5R1) networkId else null,
            )
            list.add(entity)
        }

        insertWallets(list)
        return list.toList()
    }

    suspend fun addWatchWallet(
        label: Wallet.NewLabel,
        publicKey: PublicKeyEd25519,
        version: WalletVersion,
    ): WalletEntity {
        return addWallet(newWalletId(), label, publicKey, Wallet.Type.Watch, version, initialized = false)
    }

    suspend fun pendingQuantumCreations(): List<QuantumCreationRecord> = withContext(Dispatchers.IO) {
        quantumCreationMutex.withLock { quantumCustody.pendingIds().map { QuantumCreationRecord.load(context, it) } }
    }

    suspend fun addNewWallet(label: Wallet.NewLabel, mnemonic: List<String>,
                             onEnrollment: (UUID) -> Unit = {}): WalletEntity = withContext(Dispatchers.IO) {
        quantumCreationMutex.withLock {
            val profile = requireNotNull(QuantumNetworkProfile.selected(context)) { "Configure a trusted Quantum network before creating a wallet" }
            quantumCreator(false, onEnrollment).create(mnemonic, profile) { finishQuantumRegistration(it, label) }
        }
    }

    /** The original UUID, anchor and birth identity survive every retry, including a process restart. */
    suspend fun resumeQuantumCreation(id: UUID, label: Wallet.NewLabel, mnemonic: List<String>? = null): WalletEntity = withContext(Dispatchers.IO) {
        quantumCreationMutex.withLock {
            val record = QuantumCreationRecord.load(context, id)
            val words = mnemonic ?: requireNotNull(quantumCustody.mnemonic(id)) { "Creation mnemonic is unavailable" }.toList()
            quantumCreator(true).resume(record, words) { finishQuantumRegistration(it, label) }
        }
    }

    suspend fun readQuantumAccount(wallet: WalletEntity): QuantumSelfPayAccount.Snapshot = runInterruptible(Dispatchers.IO) {
        val identity = requireNotNull(wallet.quantumIdentity) { "Quantum identity required" }
        val endpoint = requireNotNull(api.customTosRpcEndpoint) { "Configure the RPC node before reading a wallet" }
            .trimEnd('/').plus("/jsonRPC").toHttpUrl()
        QuantumAccountReader(context, QuantumProofHttpTransport(endpoint, quantumHttpClient))
            .read(UUID.fromString(wallet.id), identity)
    }

    /** Proof-anchored cells for product history; no ordinary event cache is consulted. */
    suspend fun readQuantumHistory(wallet: WalletEntity, cursor: QuantumHistoryReader.Cursor? = null,
                                   limit: Int = 20): QuantumHistoryReader.Page {
        val identity = requireNotNull(wallet.quantumIdentity) { "Quantum identity required" }
        val endpoint = requireNotNull(api.customTosRpcEndpoint) { "Configure the RPC node before reading a wallet" }
            .trimEnd('/').plus("/jsonRPC").toHttpUrl()
        return QuantumHistoryReader(QuantumAccountReader(context, QuantumProofHttpTransport(endpoint, quantumHttpClient)),
            QuantumHistoryHttpTransport(endpoint, quantumHttpClient)).read(UUID.fromString(wallet.id), identity, cursor, limit)
    }

    private fun quantumPaymentEndpoint() = requireNotNull(api.customTosRpcEndpoint) {
        "Configure the wallet RPC endpoint"
    }.trimEnd('/').plus("/jsonRPC").toHttpUrl()

    private fun quantumPayments(wallet: WalletEntity): network.tos.wallet.data.account.pq.QuantumPaymentCoordinator {
        val identity = requireNotNull(wallet.quantumIdentity)
        require(wallet.networkGlobalId == identity.wallet.initialAuthority.globalId)
        val endpoint = quantumPaymentEndpoint()
        val broadcast = network.tos.wallet.data.account.pq.QuantumPaymentHttpTransport(endpoint, quantumHttpClient)
        return network.tos.wallet.data.account.pq.QuantumPaymentCoordinator(context,
            QuantumProofHttpTransport(endpoint, quantumHttpClient), broadcast::send)
    }
    suspend fun pendingQuantumPayment(wallet: WalletEntity) = quantumPayments(wallet).pending(
        UUID.fromString(wallet.id), requireNotNull(wallet.quantumIdentity))
    private suspend fun checkQuantumRecipient(wallet: WalletEntity, intent: network.tos.blockchain.ton.contract.QuantumPaymentIntent) {
        // Metadata is advisory only and cannot change the raw signed recipient.
        val raw = "${intent.recipient.workchainId}:" + intent.recipient.address.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
        val hints = api.resolveAccount(raw, wallet.testnet)
        check(hints?.isScam != true) { "Recipient is marked unsafe" }
        check(hints?.memoRequired != true || !intent.comment.isNullOrEmpty()) { "Recipient requires a comment" }
    }
    suspend fun prepareQuantumPayment(wallet: WalletEntity, intent: network.tos.blockchain.ton.contract.QuantumPaymentIntent): network.tos.wallet.data.account.pq.QuantumPreparedPayment {
        checkQuantumRecipient(wallet, intent)
        return quantumPayments(wallet).prepare(UUID.fromString(wallet.id), requireNotNull(wallet.quantumIdentity), intent)
    }
    /** The App must complete its existing passcode/biometry gate before this call. */
    suspend fun authorizeQuantumPayment(wallet: WalletEntity, payment: network.tos.wallet.data.account.pq.QuantumPreparedPayment): network.tos.wallet.data.account.pq.QuantumPaymentQuote {
        require(payment.walletId == UUID.fromString(wallet.id) && payment.identity == wallet.quantumIdentity)
        checkQuantumRecipient(wallet, payment.intent)
        return quantumPayments(wallet).authorizeAndEstimate(payment)
    }
    suspend fun submitQuantumPayment(wallet: WalletEntity, quote: network.tos.wallet.data.account.pq.QuantumPaymentQuote) {
        require(quote.payment.walletId == UUID.fromString(wallet.id) && quote.payment.identity == wallet.quantumIdentity)
        quantumPayments(wallet).submit(quote)
    }
    suspend fun refreshQuantumPayment(wallet: WalletEntity, quote: network.tos.wallet.data.account.pq.QuantumPaymentQuote): network.tos.wallet.data.account.pq.QuantumPaymentQuote {
        require(quote.payment.walletId == UUID.fromString(wallet.id) && quote.payment.identity == wallet.quantumIdentity)
        return quantumPayments(wallet).refresh(quote)
    }
    suspend fun estimatePendingQuantumPayment(wallet: WalletEntity): network.tos.wallet.data.account.pq.QuantumPaymentQuote {
        val coordinator = quantumPayments(wallet)
        val id = UUID.fromString(wallet.id); val identity = requireNotNull(wallet.quantumIdentity)
        val entry = checkNotNull(coordinator.pending(id, identity))
        checkQuantumRecipient(wallet, entry.intent)
        return coordinator.estimatePending(id, identity)
    }
    suspend fun cancelUnsignedQuantumPayment(wallet: WalletEntity, operation: UUID) = quantumPayments(wallet).cancelUnsigned(
        UUID.fromString(wallet.id), requireNotNull(wallet.quantumIdentity), operation)
    /** Authentication-only expiry capability; this does not remove an authorization or enable signing. */
    suspend fun readQuantumPaymentExpiry(wallet: WalletEntity): network.tos.wallet.data.account.pq.QuantumExpiredAuthorization {
        val endpoint = quantumPaymentEndpoint()
        return network.tos.wallet.data.account.pq.QuantumPaymentOutcomeReader(context,
            QuantumAccountReader(context, QuantumProofHttpTransport(endpoint, quantumHttpClient)),
            QuantumHistoryHttpTransport(endpoint, quantumHttpClient)).expiry(UUID.fromString(wallet.id), requireNotNull(wallet.quantumIdentity))
    }
    suspend fun readQuantumPaymentOutcome(wallet: WalletEntity, cursor: network.tos.wallet.data.account.pq.QuantumPaymentOutcomeReader.Cursor? = null): network.tos.wallet.data.account.pq.QuantumPaymentOutcomeReader.Page {
        val endpoint = quantumPaymentEndpoint()
        return network.tos.wallet.data.account.pq.QuantumPaymentOutcomeReader(context,
            QuantumAccountReader(context, QuantumProofHttpTransport(endpoint, quantumHttpClient)),
            QuantumHistoryHttpTransport(endpoint, quantumHttpClient)).read(UUID.fromString(wallet.id), requireNotNull(wallet.quantumIdentity), cursor)
    }

    private fun quantumCreator(resuming: Boolean, onEnrollment: (UUID) -> Unit = {}): QuantumWalletCreation {
        val endpoint = requireNotNull(api.customTosRpcEndpoint) { "Configure the RPC node before creating a wallet" }
            .trimEnd('/').plus("/jsonRPC").toHttpUrl()
        return QuantumWalletCreation(
            network = { id, selected ->
                runInterruptible(Dispatchers.IO) {
                    val now = System.currentTimeMillis() / 1000
                    val session = QuantumProofSession(context, id, selected.anchor())
                    val transport = QuantumProofHttpTransport(endpoint, quantumHttpClient)
                    val request = QuantumSelfPayNetwork.request(300)
                    val proof = if (resuming) session.readBound(request, now, transport) else session.enrollBound(request, now, transport)
                    QuantumSelfPayNetwork.bind(proof, selected.globalId, System.currentTimeMillis() / 1000, 300)
                }
            },
            saveRecord = { it.save(context); onEnrollment(it.id) },
            saveMnemonic = { id, words ->
                if (resuming) quantumCustody.ensureMnemonic(id, words) else quantumCustody.saveMnemonic(id, words)
            },
            storeRole = { id, role, binding, seed ->
                if (resuming) quantumCustody.ensureRole(id, role, binding, seed) else quantumCustody.store(id, role, binding, seed)
            },
        )
    }

    private suspend fun finishQuantumRegistration(record: QuantumCreationRecord, label: Wallet.NewLabel): WalletEntity {
        val identity = WalletIdentity.Quantum(record.identity)
        val existing = database.getAccount(record.id.toString())
        val wallet = if (existing != null) {
            require(existing.identity == identity && existing.type == Wallet.Type.Default) { "Creation identifier collision" }
            existing
        } else {
            WalletEntity(id = record.id.toString(), identity = identity, type = Wallet.Type.Default,
                label = label.create(0), initialized = false).also { database.insertAccounts(listOf(it)) }
        }
        // A main-row commit followed by a mirror failure is resumed with the same UUID.
        // Do not delete custody or attempt an unsafe rollback of a possibly committed row.
        addWalletToRN(wallet)
        return wallet
    }

    private suspend fun addWallet(
        id: String,
        label: Wallet.NewLabel,
        publicKey: PublicKeyEd25519,
        type: Wallet.Type,
        version: WalletVersion,
        new: Boolean = false,
        initialized: Boolean,
        networkGlobalId: Int? = null,
    ): WalletEntity {
        val networkId = networkGlobalId ?: if (version == WalletVersion.TOSV5R1) api.tos.getNetworkInfo(false).requireNativeV5().globalId else null
        val entity = WalletEntity(
            id = id,
            publicKey = publicKey,
            type = type,
            version = version,
            label = label.create(0),
            initialized = initialized,
            networkGlobalId = networkId,
        )

        insertWallets(listOf(entity), new)
        return entity
    }

    private suspend fun insertWallets(list: List<WalletEntity>, new: Boolean = false) {
        database.insertAccounts(list)
        for (wallet in list) {
            addWalletToRN(wallet)
            if (!new && wallet.hasPrivateKey) {
                requestTonProofToken(wallet)
            }
        }
    }

    private suspend fun createTonProofToken(wallet: WalletEntity): String? {
        val payload = api.tonconnectPayload() ?: return null
        return try {
            val publicKey = wallet.publicKey
            val contract = BaseWalletContract.create(publicKey, WalletVersion.V4R2.title, wallet.testnet)
            val secretKey = vaultSource.getPrivateKey(publicKey) ?: throw Exception("private key not found")
            val address = contract.address
            val proof = WalletProof.signTosWallet(
                address = address,
                secretKey = secretKey,
                payload = payload,
                stateInit = contract.stateInitCell().base64()
            )
            api.tonconnectProof(address.toAccountId(), proof.string(false))
        } catch (e: Throwable) {
            recordException(e)
            null
        }
    }

    fun safeSetSelectedWallet(id: String?) {
        scope.launch { setSelectedWallet(id) }
    }

    suspend fun setSelectedWallet(id: String?) = selectWallet(id, completingCreation = false)

    /** Called by the authenticated creation flow, not by background metadata selection. */
    suspend fun completeQuantumCreation(id: String) = quantumCreationMutex.withLock {
        selectWallet(id, completingCreation = true)
    }

    private suspend fun selectWallet(id: String?, completingCreation: Boolean) {
        val entity = if (id == null) null else database.getAccount(id) ?: database.getAccounts().firstOrNull()
        if (completingCreation) require(entity?.isQuantum == true && entity.id == id) { "Created wallet is unavailable" }
        if (entity == null) {
            rnLegacy.setSelectedWallet("")
            storageSource.setSelectedId(null)
            _selectedStateFlow.value = SelectedState.Empty
        } else {
            if (entity.isQuantum) addWalletToRN(entity)
            rnLegacy.setSelectedWallet(entity.id)
            storageSource.setSelectedId(entity.id)
            if (completingCreation) {
                require(entity.isQuantum)
                quantumCustody.markComplete(UUID.fromString(entity.id))
            }
            _selectedStateFlow.value = SelectedState.Wallet(entity)
        }
    }

    suspend fun delete(wallet: WalletEntity) = withContext(Dispatchers.IO) {
        quantumCreationMutex.withLock {
            if (wallet.isQuantum) quantumCustody.delete(UUID.fromString(wallet.id))
            rnLegacy.delete(wallet.id)
            database.deleteAccount(wallet.id)
            setSelectedWallet(database.getFirstAccountId())
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        quantumCreationMutex.withLock {
            quantumCustody.deleteAll()
            rnLegacy.clear()
            database.clearAccounts()
            setSelectedWallet(null)
        }
    }

    suspend fun getWalletsByAccountId(accountId: String, testnet: Boolean): List<WalletEntity> {
        return database.getAccounts().filter {
            it.accountId.equalsAddress(accountId) && it.testnet == testnet
        }
    }

    suspend fun getWalletByPublicKey(publicKey: PublicKeyEd25519, testnet: Boolean): List<WalletEntity> {
        return database.getAccounts().filter {
            it.classicalPublicKey == publicKey && it.testnet == testnet
        }
    }

    suspend fun getWalletByAccountId(accountId: String, testnet: Boolean = false): WalletEntity? {
        val wallets = getWalletsByAccountId(accountId, testnet)
        if (wallets.isEmpty()) {
            return null
        }
        if (wallets.size == 1) {
            return wallets.first()
        }
        return wallets.firstOrNull {
            it.hasPrivateKey
        } ?: wallets.firstOrNull {
            it.isTonConnectSupported
        } ?: wallets.first()
    }

    suspend fun getWalletById(id: String): WalletEntity? {
        return database.getAccount(id)
    }

    suspend fun getSelectedWallet(): WalletEntity? {
        return (_selectedStateFlow.value as? SelectedState.Wallet)?.wallet ?: database.getAccount(selectedId ?: return null)
    }

    suspend fun getSeqno(
        wallet: WalletEntity
    ): Int = withContext(Dispatchers.IO) {
        require(!wallet.isQuantum) { "Quantum sequence and authority must come from authenticated self-pay state" }
        val node = api.tos.snapshot(wallet.testnet)
        wallet.networkGlobalId?.let { node.requireNetwork(it, wallet.testnet) }
        node.getSeqno(wallet.accountId, wallet.testnet, wallet.contract)
    }

    suspend fun getValidUntil(
        testnet: Boolean
    ): Long = withContext(Dispatchers.IO) {
        val seconds = api.getServerTime(testnet)
        seconds + (5 * 30L) // 5 minutes
    }

    fun messageBody(
        wallet: WalletEntity,
        seqNo: Int,
        validUntil: Long,
        transfers: List<WalletTransfer>
    ): MessageBodyEntity {
        return MessageBodyEntity(wallet, seqNo, validUntil, transfers)
    }

    suspend fun messageBody(
        wallet: WalletEntity,
        validUntil: Long = 0,
        transfers: List<WalletTransfer>
    ): MessageBodyEntity {
        val seqNo = getSeqno(wallet)
        return messageBody(
            wallet = wallet,
            seqNo = seqNo,
            validUntil = if (validUntil > 0) validUntil else getValidUntil(wallet.testnet),
            transfers = transfers
        )
    }
}
