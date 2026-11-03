package network.tos.wallet.data.account

import network.tos.blockchain.TosV1Mnemonic
import network.tos.blockchain.TosQuantumMnemonic
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
import network.tos.wallet.data.account.privacy.PrivacyBalanceState
import network.tos.wallet.data.account.privacy.PrivacyMasterUnavailable
import network.tos.wallet.data.account.privacy.PrivacyPoolRegistry
import network.tos.wallet.data.account.privacy.PrivacyPoolSelection
import network.tos.wallet.data.account.pq.PrivacyBalanceReader
import network.tos.wallet.data.account.pq.PrivacyHistorySynchronizer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.tos.wallet.data.account.pq.QuantumCreationRecord
import network.tos.wallet.data.account.pq.QuantumRecoveryRecord
import network.tos.wallet.data.account.pq.QuantumPaymentOutbox
import network.tos.security.pq.QuantumBackupInspection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import network.tos.wallet.data.account.pq.sameProfile
import network.tos.security.pq.QuantumCrypto
import network.tos.security.pq.QuantumKdf
import network.tos.security.pq.QuantumRole
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
import network.tos.wallet.api.tos.TosRpcEndpoint
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
import org.ton.boc.BagOfCells

class AccountRepository(
    private val context: Context,
    private val api: API,
    private val rnLegacy: RNLegacy,
) {

    class PendingQuantumPhraseIdentityMismatch : IllegalStateException(
        "Saved recovery phrase does not match the pending wallet identity"
    )

    companion object {
        private val quantumBackupImport = Mutex()
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
        if (wallet.isQuantum) rnLegacy.addBoundQuantumWallet(wallet)
        else rnLegacy.addWallet(wallet.toRNWallet())
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

    /** Mnemonic for an unpublished Quantum creation, shown only inside the authenticated creation flow. */
    suspend fun pendingQuantumMnemonic(id: String): Array<String>? = withContext(Dispatchers.IO) {
        val walletId = UUID.fromString(id)
        quantumCreationMutex.withLock {
            val record = QuantumCreationRecord.load(context, walletId)
            val words = quantumCustody.mnemonic(walletId)?.toList() ?: return@withLock null
            if (record.identity !in phraseMatchingIdentities(words, listOf(record.identity))) {
                throw PendingQuantumPhraseIdentityMismatch()
            }
            words.toTypedArray()
        }
    }

    suspend fun pendingQuantumProfile(id: String): QuantumNetworkProfile = withContext(Dispatchers.IO) {
        QuantumCreationRecord.load(context, UUID.fromString(id)).profile
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

    /** Authenticate the configured node against the local anchor before exposing a new recovery phrase. */
    suspend fun preflightQuantumNetwork(
        creationId: String? = null,
        confirmedProfile: QuantumNetworkProfile? = null
    ) {
        require(creationId == null || confirmedProfile == null) { "Choose either a pending creation or a confirmed recovery profile" }
        val profile = confirmedProfile ?: if (creationId == null) {
            QuantumNetworkProfile.effective(context)
        } else {
            pendingQuantumProfile(creationId)
        }
        proveQuantumNetwork(profile, recovery = false)
    }

    /** Recovery needs authenticated network identity even after PRIMARY creation admission ends. */
    suspend fun quantumRecoveryNamespace(profile: QuantumNetworkProfile): ByteArray {
        check(quantumBackupImport.tryLock()) { "Another backup import is in progress" }
        try { return proveQuantumNetwork(profile, recovery = true) }
        finally { quantumBackupImport.unlock() }
    }

    private suspend fun proveQuantumNetwork(profile: QuantumNetworkProfile, recovery: Boolean): ByteArray {
        val id = UUID.randomUUID()
        val session = QuantumProofSession(context, id, profile.anchor())
        var failure: Throwable? = null
        try {
            val endpoint = TosRpcEndpoint.jsonRpcUrl(api.tosRpcEndpoint(false)).toHttpUrl()
            val transport = QuantumProofHttpTransport(endpoint, quantumHttpClient)
            val now = System.currentTimeMillis() / 1000
            val proof = runInterruptible(Dispatchers.IO) {
                session.enrollBound(QuantumSelfPayNetwork.request(300), now, transport)
            }
            val verifiedAt = System.currentTimeMillis() / 1000
            return if (recovery) QuantumSelfPayNetwork.recoveryNamespace(proof, profile.globalId, verifiedAt)
            else QuantumSelfPayNetwork.bind(proof, profile.globalId, verifiedAt, 300).also {
                it.requireFresh(verifiedAt)
            }.namespace
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { session.removeCheckpoint() }
            catch (cleanupError: Throwable) {
                if (failure != null) failure.addSuppressed(cleanupError) else throw cleanupError
            }
        }
    }

    suspend fun addNewWallet(label: Wallet.NewLabel, mnemonic: List<String>, profile: QuantumNetworkProfile,
                             backupConfirmed: Boolean,
                             onEnrollment: (UUID) -> Unit = {}): WalletEntity = withContext(Dispatchers.IO) {
        require(backupConfirmed) { "Save and confirm the recovery phrase before creating the wallet" }
        quantumCreationMutex.withLock {
            quantumCreator(false, onEnrollment = onEnrollment).create(mnemonic, profile) { finishQuantumRegistration(it, label) }
        }
    }

    /** Restore a native phrase into its deterministic Quantum identity, reusing an existing
     * enrollment UUID when the same identity is already present on this device. */
    suspend fun restoreQuantumWallet(
        label: Wallet.NewLabel,
        mnemonic: List<String>,
        confirmedProfile: QuantumNetworkProfile
    ): WalletEntity = withContext(Dispatchers.IO) {
        quantumCreationMutex.withLock {
            require(mnemonic.size == 24 && TosV1Mnemonic.isValid(mnemonic)) { "A valid 24-word native TOS phrase is required" }
            // Restore on the exact trust profile the user reviewed before
            // entering the passcode and account details.
            val selected = confirmedProfile
            val wallets = database.getAccounts().filter { it.version == WalletVersion.QUANTUM_SELF_PAY }
            val records = linkedMapOf<UUID, QuantumCreationRecord>()
            for (wallet in wallets) {
                val id = runCatching { UUID.fromString(wallet.id) }.getOrNull()
                    ?: throw IllegalStateException("Existing Quantum wallet has an invalid identifier")
                val record = runCatching { QuantumCreationRecord.load(context, id) }.getOrNull()
                if (record != null) records[id] = record
            }
            for (id in quantumCustody.pendingIds()) {
                records.putIfAbsent(id, QuantumCreationRecord.load(context, id))
            }

            val identities = (records.values.map { it.identity } + wallets.mapNotNull { it.quantumIdentity }).distinct()
            val matchingIdentities = phraseMatchingIdentities(mnemonic, identities)
            val matching = records.values.filter { it.identity in matchingIdentities }
            require(matching.size <= 1) { "Phrase matches multiple local Quantum enrollments; resolve duplicate wallet data first" }
            val record = matching.singleOrNull()
            if (record != null) {
                require(sameProfile(record.profile, selected)) {
                    "This phrase is enrolled for a different trusted network profile"
                }
                val row = wallets.singleOrNull { it.id == record.id.toString() }
                require(wallets.count { it.quantumIdentity == record.identity } <= 1) {
                    "Duplicate Quantum wallet identities exist on this device"
                }
                if (row != null) require(row.identity == WalletIdentity.Quantum(record.identity) && row.type == Wallet.Type.Default) {
                    "Existing Quantum wallet row does not match its enrollment"
                }
                quantumCreator(true, ordinaryRestore = true).resume(record, mnemonic) { finishQuantumRegistration(it, label) }
            } else {
                require(wallets.none { row ->
                    val identity = row.quantumIdentity ?: return@none false
                    identity in matchingIdentities
                }) { "Matching Quantum wallet has no protected enrollment record; refusing to create a duplicate" }
                quantumCreator(false, ordinaryRestore = true).create(mnemonic, selected) { finishQuantumRegistration(it, label) }
            }
        }
    }

    private fun phraseMatchingIdentities(
        mnemonic: List<String>,
        identities: List<network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity>
    ): Set<network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity> {
        val groups = identities.groupBy { identity ->
            val authority = identity.wallet.initialAuthority
            Pair(authority.globalId, authority.namespace.joinToString("") { "%02x".format(it.toInt() and 255) })
        }
        val matches = mutableSetOf<network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity>()
        for (group in groups.values) {
            val authority = group.first().wallet.initialAuthority
            val namespace = authority.namespace
            var master = ByteArray(0)
            var primary = ByteArray(0)
            var rescue = ByteArray(0)
            try {
                master = TosQuantumMnemonic.masterAndWipePassword(mnemonic, CharArray(0))
                primary = QuantumKdf.deriveAndWipe(QuantumKdf.Material.PRIMARY, master.copyOf(), namespace, authority.globalId, 0, 0)
                rescue = QuantumKdf.deriveAndWipe(QuantumKdf.Material.RESCUE, master.copyOf(), namespace, authority.globalId, 0, 0)
                val primaryKey = QuantumCrypto.publicKey(QuantumRole.PRIMARY, primary)
                val rescueKey = QuantumCrypto.publicKey(QuantumRole.RESCUE, rescue)
                for (identity in group) {
                    val candidate = identity.wallet.initialAuthority
                    if (primaryKey.contentEquals(candidate.primaryKey) && rescueKey.contentEquals(candidate.rescueKey)) {
                        matches.add(identity)
                    }
                }
            } finally { master.fill(0); primary.fill(0); rescue.fill(0); namespace.fill(0) }
        }
        return matches
    }

    /** The original UUID, anchor and birth identity survive every retry, including a process restart. */
    suspend fun resumeQuantumCreation(id: UUID, label: Wallet.NewLabel, backupConfirmed: Boolean,
                                     mnemonic: List<String>? = null): WalletEntity = withContext(Dispatchers.IO) {
        require(backupConfirmed) { "Save and confirm the recovery phrase before resuming the wallet" }
        quantumCreationMutex.withLock {
            val record = QuantumCreationRecord.load(context, id)
            val words = mnemonic ?: requireNotNull(quantumCustody.mnemonic(id)) { "Creation mnemonic is unavailable" }.toList()
            quantumCreator(true).resume(record, words) { finishQuantumRegistration(it, label) }
        }
    }

    suspend fun readQuantumAccount(wallet: WalletEntity): QuantumSelfPayAccount.Snapshot = runInterruptible(Dispatchers.IO) {
        val identity = requireNotNull(wallet.quantumIdentity) { "Quantum identity required" }
        val endpoint = TosRpcEndpoint.jsonRpcUrl(api.tosRpcEndpoint(false)).toHttpUrl()
        QuantumAccountReader(context, QuantumProofHttpTransport(endpoint, quantumHttpClient))
            .read(UUID.fromString(wallet.id), identity)
    }

    /** Explicit deployment trust input only. No RPC discovery path calls this API.
     * Proof authenticates that the configured deployment matches those pins; it does NOT
     * authenticate who supplied the pins. Caller must authenticate configuration changes.
     */
    suspend fun configureTrustedPrivacyPool(wallet: WalletEntity, selection: PrivacyPoolSelection) = withContext(Dispatchers.IO) {
        val stored = checkNotNull(database.getAccount(wallet.id))
        require(stored.identity == wallet.identity && stored.isQuantum) { "An enrolled TOS proof profile is required" }
        val record = QuantumCreationRecord.load(context, UUID.fromString(wallet.id))
        require(record.identity == stored.quantumIdentity); selection.requireProfile(record.profile)
        val endpoint = quantumPaymentEndpoint()
        runInterruptible {
            PrivacyHistorySynchronizer.Lease(record.id).use {
                val observed = QuantumAccountReader(context, QuantumProofHttpTransport(endpoint, quantumHttpClient))
                    .observePool(record.id, record.identity, record.profile, selection.address(), selection.expectedCode(),
                        selection.profileBytes(), selection.manifestBytes(), selection.verifyingKeyBytes())
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Privacy configuration cancelled")
                observed.requireFresh(System.currentTimeMillis() / 1000)
                PrivacyPoolRegistry(context).saveExplicit(selection)
            }
        }
    }

    /** Non-secret local preflight: no authentication prompt, RPC or master derivation. */
    suspend fun privacyReadAvailability(wallet: WalletEntity): PrivacyBalanceState? = withContext(Dispatchers.IO) {
        try {
            val stored = database.getAccount(wallet.id) ?: return@withContext PrivacyBalanceState.MaterialUnavailable
            if (stored.identity != wallet.identity) return@withContext PrivacyBalanceState.MaterialUnavailable
            if (!stored.isQuantum) return@withContext PrivacyBalanceState.WalletNotProvisioned
            val id = UUID.fromString(wallet.id)
            val record = QuantumCreationRecord.load(context, id)
            require(record.identity == stored.quantumIdentity)
            if (PrivacyPoolRegistry(context).selected(record.profile) == null) return@withContext PrivacyBalanceState.NotConfigured
            if (keyguardManager.isDeviceLocked || !quantumCustody.hasOriginalPrivacyMaster(id))
                PrivacyBalanceState.MaterialUnavailable else null
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { PrivacyBalanceState.Failed(PrivacyBalanceState.Failure.VERIFICATION_OR_TRANSPORT) }
    }

    /** Read-only observation using original protected master and retained birth/proof provenance.
     * No descriptor index, signature, reservation, submission or backup state is created.
     * Non-Quantum wallets need an explicitly provisioned privacy proof profile; no synthetic
     * identity/anchor is generated here. Role-only restores cannot substitute a signing seed.
     */
    suspend fun readPrivacyBalance(wallet: WalletEntity, stopped: () -> Boolean = { false }): PrivacyBalanceState = withContext(Dispatchers.IO) {
        try {
            val stored = database.getAccount(wallet.id) ?: return@withContext PrivacyBalanceState.MaterialUnavailable
            if (stored.identity != wallet.identity) return@withContext PrivacyBalanceState.MaterialUnavailable
            if (!stored.isQuantum) return@withContext PrivacyBalanceState.WalletNotProvisioned
            if (keyguardManager.isDeviceLocked) return@withContext PrivacyBalanceState.MaterialUnavailable
            val id = UUID.fromString(wallet.id)
            if (!quantumCustody.hasOriginalPrivacyMaster(id)) return@withContext PrivacyBalanceState.MaterialUnavailable
            val record = QuantumCreationRecord.load(context, id)
            require(record.identity == stored.quantumIdentity)
            val selection = PrivacyPoolRegistry(context).selected(record.profile) ?: return@withContext PrivacyBalanceState.NotConfigured
            val endpoint = quantumPaymentEndpoint()
            val observed = runInterruptible {
                PrivacyBalanceReader(QuantumAccountReader(context, QuantumProofHttpTransport(endpoint, quantumHttpClient)),
                    QuantumHistoryHttpTransport(endpoint, quantumHttpClient)).read(record, selection, stopped) { body ->
                    quantumCustody.withOriginalPrivacyMaster(id, record.identity, body)
                }
            }
            currentCoroutineContext().ensureActive()
            if (stopped()) throw kotlinx.coroutines.CancellationException("Privacy page left foreground")
            val after = database.getAccount(wallet.id)
            if (after?.identity != stored.identity || keyguardManager.isDeviceLocked || !quantumCustody.hasOriginalPrivacyMaster(id)) {
                PrivacyBalanceState.MaterialUnavailable
            } else observed
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: PrivacyMasterUnavailable) { PrivacyBalanceState.MaterialUnavailable }
        catch (error: IllegalStateException) {
            PrivacyBalanceState.Failed(if (error.message == "Privacy history synchronizer busy") PrivacyBalanceState.Failure.BUSY
                else PrivacyBalanceState.Failure.VERIFICATION_OR_TRANSPORT)
        } catch (_: Exception) { PrivacyBalanceState.Failed(PrivacyBalanceState.Failure.VERIFICATION_OR_TRANSPORT) }
    }

    /** One frozen endpoint and one authenticated owner checkpoint for the entire asset refresh. */
    suspend fun readQuantumAssets(wallet: WalletEntity): network.tos.wallet.data.account.pq.QuantumTokenAssets =
        runInterruptible(Dispatchers.IO) {
            val identity = requireNotNull(wallet.quantumIdentity) { "Quantum identity required" }
            val endpoint = TosRpcEndpoint.jsonRpcUrl(api.tosRpcEndpoint(false)).toHttpUrl()
            val reader = QuantumAccountReader(context, QuantumProofHttpTransport(endpoint, quantumHttpClient))
            val rpc = network.tos.wallet.api.tos.TosRpcClient(quantumHttpClient, { endpoint.toString() })
            network.tos.wallet.data.account.pq.QuantumTokenAssets.read(reader, rpc, UUID.fromString(wallet.id), identity)
        }

    /** Proof-anchored cells for product history; no ordinary event cache is consulted. */
    suspend fun readQuantumHistory(wallet: WalletEntity, cursor: QuantumHistoryReader.Cursor? = null,
                                   limit: Int = 20): QuantumHistoryReader.Page {
        val identity = requireNotNull(wallet.quantumIdentity) { "Quantum identity required" }
        val endpoint = TosRpcEndpoint.jsonRpcUrl(api.tosRpcEndpoint(false)).toHttpUrl()
        return QuantumHistoryReader(QuantumAccountReader(context, QuantumProofHttpTransport(endpoint, quantumHttpClient)),
            QuantumHistoryHttpTransport(endpoint, quantumHttpClient)).read(UUID.fromString(wallet.id), identity, cursor, limit)
    }

    /** Authenticated control inclusion only; no journal retirement or recovery unlock. */
    suspend fun readQuantumRecoveryControlOutcome(wallet: WalletEntity):
        network.tos.wallet.data.account.pq.QuantumRecoveryControlOutcomeReader.Result {
        val identity = requireNotNull(wallet.quantumIdentity)
        val endpoint = quantumPaymentEndpoint()
        val history = QuantumHistoryReader(QuantumAccountReader(context, QuantumProofHttpTransport(endpoint, quantumHttpClient)),
            QuantumHistoryHttpTransport(endpoint, quantumHttpClient))
        return network.tos.wallet.data.account.pq.QuantumRecoveryControlOutcomeReader(context, history)
            .read(UUID.fromString(wallet.id), identity)
    }

    /** Caller authenticates. Stages independent roots without installing them or changing current custody. */
    suspend fun stageQuantumSuccessorMaterial(wallet: WalletEntity, resume: Boolean = false):
        network.tos.security.pq.QuantumSuccessorMaterialVault.Stage = withContext(Dispatchers.IO) {
        quantumCreationMutex.withLock {
            val coroutine = currentCoroutineContext()
            coroutine.ensureActive()
            val actual = checkNotNull(database.getAccount(wallet.id))
            require(actual.identity == wallet.identity && actual.type == Wallet.Type.Default)
            val observed = observeCompletedRecoveryLock(actual)
            val identity = requireNotNull(actual.quantumIdentity)
            val state = (network.tos.wallet.data.account.pq.QuantumSelfPayAccount.bind(identity, observed.proof,
                System.currentTimeMillis() / 1000, 300).snapshot(System.currentTimeMillis() / 1000).status
                as network.tos.wallet.data.account.pq.QuantumSelfPayAccount.Status.Active).state
            val staged = QuantumPaymentOutbox(context).withRecoveryControlIsolation(identity) {
                coroutine.ensureActive()
                quantumCustody.successorMaterial(UUID.fromString(actual.id), identity, state, observed.profile, resume)
            }
            // A failed final proof leaves staged material intact; explicit resume never replaces roots.
            val fresh = observeCompletedRecoveryLock(actual)
            require(sameProfile(observed.profile, fresh.profile))
            coroutine.ensureActive()
            staged
        }
    }

    /** Caller authenticates. Read the original protected roots after the exact recorded funding transition.
     * This never generates/replaces roots, signs, installs keys or releases payment isolation. */
    suspend fun inspectFundedQuantumSuccessorMaterial(wallet: WalletEntity):
        network.tos.security.pq.QuantumSuccessorMaterialVault.Stage = withContext(Dispatchers.IO) {
        quantumCreationMutex.withLock {
            val coroutine = currentCoroutineContext()
            val actual = checkNotNull(database.getAccount(wallet.id))
            require(actual.type == Wallet.Type.Default && actual.identity == wallet.identity)
            val identity = requireNotNull(actual.quantumIdentity)
            val record = checkNotNull(QuantumRecoveryRecord.findIdentity(context, identity))
            require(record.id.toString() == actual.id && record.identity == identity)
            val funding = network.tos.wallet.data.account.pq.QuantumProbeFundingJournal(context)
            val controls = network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal(context)
            val probes = network.tos.wallet.data.account.pq.QuantumRecoveryProbeJournal(context)
            val outbox = QuantumPaymentOutbox(context)
            val originals = outbox.withRecoveryControlIsolation(identity) {
                val entry = checkNotNull(funding.pending(identity))
                check(entry.phase == network.tos.wallet.data.account.pq.QuantumProbeFundingJournal.Phase.COMPLETED)
                require(entry.walletId == record.id)
                Triple(entry.encode(), checkNotNull(controls.pending(identity)).encode(), checkNotNull(probes.pending(identity)).encode())
            }
            val operation = checkNotNull(funding.pending(identity)).operation
            fun inspect(result: network.tos.wallet.data.account.pq.QuantumProbeFundingOutcomeReader.Result) =
                outbox.withRecoveryControlIsolation(identity) {
                    coroutine.ensureActive()
                    val entry = checkNotNull(funding.pending(identity)); val prior = checkNotNull(controls.pending(identity))
                    val probe = checkNotNull(probes.pending(identity))
                    check(originals.first.contentEquals(entry.encode()) && originals.second.contentEquals(prior.encode()) &&
                        originals.third.contentEquals(probe.encode())) { "Funded material records changed" }
                    require(entry.operation == operation && sameProfile(probe.profile, record.profile))
                    val state = result.requireRecordedFunding(entry, prior, probe, System.currentTimeMillis() / 1000)
                    val stage = quantumCustody.inspectFundedSuccessor(record.id, identity, prior.expectedControlState(), state,
                        record.profile, probe.materialDigest, probe.successor)
                    coroutine.ensureActive()
                    check(originals.first.contentEquals(checkNotNull(funding.pending(identity)).encode()) &&
                        originals.second.contentEquals(checkNotNull(controls.pending(identity)).encode()) &&
                        originals.third.contentEquals(checkNotNull(probes.pending(identity)).encode()))
                    result.requireRecordedFunding(entry, prior, probe, System.currentTimeMillis() / 1000)
                    stage
                }
            val first = inspect(readQuantumProbeFundingOutcome(actual, operation))
            coroutine.ensureActive()
            val current = checkNotNull(database.getAccount(actual.id)); require(current.identity == actual.identity && current.type == Wallet.Type.Default)
            val second = inspect(readQuantumProbeFundingOutcome(current, operation))
            check(first.inspection.encryptedDigest.contentEquals(second.inspection.encryptedDigest) &&
                first.inspection.snapshot.hash.contentEquals(second.inspection.snapshot.hash))
            second
        }
    }

    class SuccessorBackupExport internal constructor(
        internal val binding: network.tos.wallet.data.account.pq.QuantumSuccessorBackupConfirmation.Binding,
        encrypted: ByteArray,
    ) {
        private val file = encrypted.copyOf()
        internal val digest: ByteArray get() = java.security.MessageDigest.getInstance("SHA-256").digest(file)
        fun encryptedFile(): ByteArray = file.copyOf()
    }

    /** Must run under the creation mutex and recovery isolation lease, after fresh funded inspection. */
    private fun successorBackupBinding(wallet: WalletEntity,
        stage: network.tos.security.pq.QuantumSuccessorMaterialVault.Stage,
    ): Triple<QuantumRecoveryRecord, network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal.Entry,
        network.tos.wallet.data.account.pq.QuantumSuccessorBackupConfirmation.Binding> {
        require(wallet.type == Wallet.Type.Default)
        val identity = requireNotNull(wallet.quantumIdentity)
        val record = checkNotNull(QuantumRecoveryRecord.findIdentity(context, identity))
        val entry = checkNotNull(network.tos.wallet.data.account.pq.QuantumProbeFundingJournal(context).pending(identity))
        val prior = checkNotNull(network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal(context).pending(identity))
        val probe = checkNotNull(network.tos.wallet.data.account.pq.QuantumRecoveryProbeJournal(context).pending(identity))
        check(entry.phase == network.tos.wallet.data.account.pq.QuantumProbeFundingJournal.Phase.COMPLETED)
        require(record.id.toString() == wallet.id && entry.walletId == record.id && stage.walletId == record.id &&
            stage.inspection.birth == identity && sameProfile(record.profile, probe.profile) &&
            stage.inspection.encryptedDigest.contentEquals(probe.materialDigest) &&
            stage.inspection.snapshot.hash.contentEquals(probe.successor.hash))
        fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return Triple(record, prior, network.tos.wallet.data.account.pq.QuantumSuccessorBackupConfirmation.Binding(
            record.id, entry.operation, identity.cell.hash().toByteArray(), hex(record.profile.rootHash), hex(record.profile.fileHash),
            probe.materialDigest, probe.successor.hash))
    }

    /** Caller authenticates. Export a pending successor, never mark the current wallet backed up. */
    suspend fun exportFundedQuantumSuccessorBackup(wallet: WalletEntity, password: CharArray): SuccessorBackupExport {
        var encrypted = ByteArray(0)
        try {
            val stage = inspectFundedQuantumSuccessorMaterial(wallet)
            val output = withContext(Dispatchers.IO) {
                val coroutine = currentCoroutineContext()
                quantumCreationMutex.withLock {
                    val actual = checkNotNull(database.getAccount(wallet.id))
                    require(actual.identity == wallet.identity && actual.type == Wallet.Type.Default)
                    QuantumPaymentOutbox(context).withRecoveryControlIsolation(requireNotNull(wallet.quantumIdentity)) {
                        coroutine.ensureActive()
                        val (record, prior, binding) = successorBackupBinding(wallet, stage)
                        encrypted = quantumCustody.exportSuccessorMaterial(record, prior.expectedControlState(),
                            stage.inspection.encryptedDigest, stage.inspection.snapshot, password)
                        SuccessorBackupExport(binding, encrypted)
                    }
                }
            }
            val fresh = inspectFundedQuantumSuccessorMaterial(wallet)
            withContext(Dispatchers.IO) {
                quantumCreationMutex.withLock {
                    val actual = checkNotNull(database.getAccount(wallet.id))
                    require(actual.identity == wallet.identity && actual.type == Wallet.Type.Default)
                    QuantumPaymentOutbox(context).withRecoveryControlIsolation(requireNotNull(wallet.quantumIdentity)) {
                        check(output.binding.encode().contentEquals(successorBackupBinding(wallet, fresh).third.encode()))
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            return output
        } finally { encrypted.fill(0); password.fill('\u0000') }
    }

    /** A system-file read-back capability plus fresh material/operation checks is required. */
    suspend fun confirmFundedQuantumSuccessorBackup(wallet: WalletEntity, exported: SuccessorBackupExport,
        saved: network.tos.wallet.data.account.pq.QuantumBackupExportFile.Saved,
    ) {
        val fresh = inspectFundedQuantumSuccessorMaterial(wallet)
        withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            quantumCreationMutex.withLock {
                val actual = checkNotNull(database.getAccount(wallet.id))
                require(actual.identity == wallet.identity && actual.type == Wallet.Type.Default)
                QuantumPaymentOutbox(context).withRecoveryControlIsolation(requireNotNull(wallet.quantumIdentity)) {
                    coroutine.ensureActive()
                    val binding = successorBackupBinding(wallet, fresh).third
                    check(binding.encode().contentEquals(exported.binding.encode())) { "Successor export operation changed" }
                    quantumCustody.confirmSuccessorBackup(binding, exported.digest, saved)
                }
            }
        }
    }

    /** Revalidates a retained saved-file confirmation; does not authorize migration or payment. */
    suspend fun verifiedFundedQuantumSuccessorBackup(wallet: WalletEntity): String {
        val fresh = inspectFundedQuantumSuccessorMaterial(wallet)
        return withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            quantumCreationMutex.withLock {
                val actual = checkNotNull(database.getAccount(wallet.id))
                require(actual.identity == wallet.identity && actual.type == Wallet.Type.Default)
                QuantumPaymentOutbox(context).withRecoveryControlIsolation(requireNotNull(wallet.quantumIdentity)) {
                    coroutine.ensureActive()
                    quantumCustody.requireSuccessorBackup(successorBackupBinding(wallet, fresh).third)
                        .joinToString("") { "%02x".format(it) }
                }
            }
        }
    }

    /** Caller authenticates. Backup confirmation is mandatory before preparing any new authorization.
     * This creates no signature, journal slot, network message or installation authority. */
    suspend fun prepareFundedQuantumSuccessorMigration(wallet: WalletEntity):
        network.tos.wallet.data.account.pq.QuantumPreparedSuccessorMigration =
        fundedQuantumSuccessorMigration(wallet, reviewed = null)

    /** Caller authenticates and reviews the prepared request before reserving. No key is used.
     * Existing reservations retain their original deadline; uncertain signing cannot restart. */
    suspend fun reserveFundedQuantumSuccessorMigration(wallet: WalletEntity,
        reviewed: network.tos.wallet.data.account.pq.QuantumPreparedSuccessorMigration):
        network.tos.wallet.data.account.pq.QuantumPreparedSuccessorMigration =
        fundedQuantumSuccessorMigration(wallet, reviewed)

    /** Caller authenticates the reviewed migration. Durable SIGNING precedes protected key use.
     * No broadcast, material installation or recovery unlock occurs. */
    suspend fun signFundedQuantumSuccessorMigration(wallet: WalletEntity,
        reviewed: network.tos.wallet.data.account.pq.QuantumPreparedSuccessorMigration) {
        fundedQuantumSuccessorMigration(wallet, reviewed, sign = true)
    }

    data class MigrationSigningStatus(val operation: UUID, val phase: String, val requestHash: String,
        val deadline: Long, val signatureDigest: String?)

    /** Local historical consistency only. SIGNED also verifies its original native signature.
     * No fresh chain execution, delivery, current backup confirmation or retry permission is inferred. */
    suspend fun pendingFundedQuantumSuccessorMigration(wallet: WalletEntity): MigrationSigningStatus? = withContext(Dispatchers.IO) {
        quantumCreationMutex.withLock {
            val actual = checkNotNull(database.getAccount(wallet.id))
            require(actual.identity == wallet.identity && actual.type == Wallet.Type.Default)
            val identity = requireNotNull(actual.quantumIdentity)
            QuantumPaymentOutbox(context).withRecoveryControlIsolation(identity) {
                val key = identity.cell.hash().toByteArray().joinToString("") { "%02x".format(it) }
                val store = network.tos.wallet.data.account.pq.QuantumMigrationSigningStore(
                    network.tos.wallet.data.account.pq.QuantumMigrationSigningAndroidIO(context, key))
                val saved = store.load() ?: return@withRecoveryControlIsolation null
                val entry = checkNotNull(network.tos.wallet.data.account.pq.QuantumProbeFundingJournal(context).pending(identity))
                val prior = checkNotNull(network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal(context).pending(identity))
                val probe = checkNotNull(network.tos.wallet.data.account.pq.QuantumRecoveryProbeJournal(context).pending(identity))
                require(entry.walletId.toString() == actual.id && prior.identity == identity && probe.identity == identity)
                val metadata = network.tos.wallet.data.account.pq.QuantumMigrationSigningSnapshot.decode(saved.binding)
                    .inspectSaved(entry, prior, probe, saved)
                check(checkNotNull(store.load()).encode().contentEquals(saved.encode())) { "Migration record changed during inspection" }
                MigrationSigningStatus(saved.operation, saved.phase.name, metadata.requestHash, metadata.deadline,
                    saved.signatureDigest?.joinToString("") { "%02x".format(it) })
            }
        }
    }

    private suspend fun fundedQuantumSuccessorMigration(wallet: WalletEntity,
        reviewed: network.tos.wallet.data.account.pq.QuantumPreparedSuccessorMigration?, sign: Boolean = false,
        readSigned: Boolean = false):
        network.tos.wallet.data.account.pq.QuantumPreparedSuccessorMigration = withContext(Dispatchers.IO) {
        val stage = inspectFundedQuantumSuccessorMaterial(wallet)
        val operation = checkNotNull(pendingQuantumProbeFunding(wallet)).operation
        val result = readQuantumProbeFundingOutcome(wallet, operation)
        val coroutine = currentCoroutineContext()
        quantumCreationMutex.withLock {
            val actual = checkNotNull(database.getAccount(wallet.id))
            require(actual.identity == wallet.identity && actual.type == Wallet.Type.Default)
            QuantumPaymentOutbox(context).withRecoveryControlIsolation(requireNotNull(wallet.quantumIdentity)) {
                coroutine.ensureActive()
                val (record, prior, binding) = successorBackupBinding(actual, stage)
                val backup = quantumCustody.requireSuccessorBackup(binding)
                val entry = checkNotNull(network.tos.wallet.data.account.pq.QuantumProbeFundingJournal(context).pending(record.identity))
                val probe = checkNotNull(network.tos.wallet.data.account.pq.QuantumRecoveryProbeJournal(context).pending(record.identity))
                check(entry.operation == operation)
                val now = System.currentTimeMillis() / 1000
                val key = record.identity.cell.hash().toByteArray().joinToString("") { "%02x".format(it) }
                val store = network.tos.wallet.data.account.pq.QuantumMigrationSigningStore(
                    network.tos.wallet.data.account.pq.QuantumMigrationSigningAndroidIO(context, key))
                val journal = network.tos.wallet.data.account.pq.QuantumMigrationSigningJournal(store)
                val retained = store.load()
                if (readSigned) checkNotNull(retained) { "Migration signature missing" }
                val plan = if (reviewed == null && retained == null) result.prepareMigration(entry, prior, probe, backup, now, 120) else {
                    reviewed?.requireRecords(entry, prior, probe, backup)
                    reviewed?.requireFresh(now)
                    if (retained == null) {
                        val original = checkNotNull(reviewed)
                        val prepared = result.prepareMigration(entry, prior, probe, backup, now, 120, original.expiresAt)
                        check(prepared.request.request.hash() == original.request.request.hash()) { "Reviewed migration changed" }
                        coroutine.ensureActive()
                        journal.reserve(prepared, entry, prior, probe, now)
                    }
                    val (saved, restored) = journal.restore(entry, prior, probe, result, backup,
                        System.currentTimeMillis() / 1000)
                    val requiredPhase = if (readSigned) network.tos.wallet.data.account.pq.QuantumMigrationSigningRecord.Phase.SIGNED
                        else network.tos.wallet.data.account.pq.QuantumMigrationSigningRecord.Phase.RESERVED
                    check(saved.phase == requiredPhase) {
                        "Migration signing already started or uncertain"
                    }
                    if (readSigned) {
                        val signature = saved.signature()
                        try { restored.requireSignature(signature) } finally { signature.fill(0) }
                    }
                    if (reviewed != null) check(restored.request.request.hash() == reviewed.request.request.hash()) { "Reviewed migration differs from reservation" }
                    if (sign) {
                        coroutine.ensureActive()
                        // Any cancellation/exception from this point retains SIGNING. Never reset
                        // the slot even when the native signer may not have returned a result.
                        store.signOnce(saved, signer = {
                            coroutine.ensureActive()
                            val inspection = quantumCustody.inspectRecoveryMaterial(record.id, record.identity,
                                record.encryptedDigest, record.profile)
                            coroutine.ensureActive()
                            restored.signProtected(quantumCustody, inspection, System.currentTimeMillis() / 1000)
                        }, verify = { signature ->
                            restored.requireSignature(signature)
                            restored.requireFresh(System.currentTimeMillis() / 1000)
                            coroutine.ensureActive()
                        })
                    }
                    restored
                }
                plan.also {
                    it.requireRecords(entry, prior, probe, backup)
                    it.requireFresh(System.currentTimeMillis() / 1000)
                    coroutine.ensureActive()
                }
            }
        }
    }

    /** Fresh local simulation of the original signed message only. Caller confirms fees separately. */
    suspend fun estimateFundedQuantumSuccessorMigration(wallet: WalletEntity, maximumEstimatedFee: BigInteger):
        network.tos.wallet.data.account.pq.QuantumMigrationQuote = withContext(Dispatchers.IO) {
        require(maximumEstimatedFee.signum() > 0 && maximumEstimatedFee.bitLength() <= 120)
        val plan = fundedQuantumSuccessorMigration(wallet, reviewed = null, readSigned = true)
        val coroutine = currentCoroutineContext()
        quantumCreationMutex.withLock {
            val actual = checkNotNull(database.getAccount(wallet.id))
            require(actual.identity == wallet.identity && actual.type == Wallet.Type.Default)
            QuantumPaymentOutbox(context).withRecoveryControlIsolation(plan.identity) {
                coroutine.ensureActive()
                val transport = QuantumProofHttpTransport(quantumPaymentEndpoint(), quantumHttpClient)
                val fresh = QuantumAccountReader(context, transport).observe(plan.walletId, plan.identity, intArrayOf(8, 19))
                plan.requireRevalidated(fresh, System.currentTimeMillis() / 1000)
                val key = plan.identity.cell.hash().toByteArray().joinToString("") { "%02x".format(it) }
                val store = network.tos.wallet.data.account.pq.QuantumMigrationSigningStore(
                    network.tos.wallet.data.account.pq.QuantumMigrationSigningAndroidIO(context, key))
                val saved = checkNotNull(store.load())
                check(saved.phase == network.tos.wallet.data.account.pq.QuantumMigrationSigningRecord.Phase.SIGNED)
                val entry = checkNotNull(network.tos.wallet.data.account.pq.QuantumProbeFundingJournal(context).pending(plan.identity))
                val prior = checkNotNull(network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal(context).pending(plan.identity))
                val probe = checkNotNull(network.tos.wallet.data.account.pq.QuantumRecoveryProbeJournal(context).pending(plan.identity))
                val snapshot = network.tos.wallet.data.account.pq.QuantumMigrationSigningSnapshot.create(plan, entry, prior, probe,
                    System.currentTimeMillis() / 1000)
                check(saved.binding.contentEquals(snapshot.encode())) { "Migration estimate binding changed" }
                val signature = saved.signature()
                val message = try { plan.signedMessage(signature, System.currentTimeMillis() / 1000) }
                    finally { signature.fill(0) }
                val request = org.json.JSONObject().put("mode", "live").put("account", QuantumAccountReader.rawAddress(plan.identity))
                    .put("max_age_seconds", 300).toString().toByteArray()
                val simulation = network.tos.security.pq.QuantumProofSession(context, plan.walletId, plan.profile.anchor())
                    .simulate(request, System.currentTimeMillis() / 1000, message, transport)
                coroutine.ensureActive()
                val quote = network.tos.wallet.data.account.pq.QuantumMigrationQuote(plan, saved, message, request,
                    simulation, System.currentTimeMillis() / 1000, maximumEstimatedFee)
                quote.requireFresh(checkNotNull(store.load()), System.currentTimeMillis() / 1000)
                quote
            }
        }
    }

    /** Caller explicitly confirms this native quote. One transport attempt; never an inclusion claim. */
    suspend fun submitFundedQuantumSuccessorMigration(quote: network.tos.wallet.data.account.pq.QuantumMigrationQuote) =
        withContext(Dispatchers.IO) {
            val plan = quote.plan
            val wallet = checkNotNull(database.getAccount(plan.walletId.toString()))
            require(wallet.quantumIdentity == plan.identity && wallet.type == Wallet.Type.Default)
            val stage = inspectFundedQuantumSuccessorMaterial(wallet)
            val result = readQuantumProbeFundingOutcome(wallet, plan.fundingOperation)
            val coroutine = currentCoroutineContext()
            quantumCreationMutex.withLock {
                val actual = checkNotNull(database.getAccount(wallet.id))
                require(actual.identity == wallet.identity && actual.type == Wallet.Type.Default)
                runInterruptible { QuantumPaymentOutbox(context).withRecoveryControlIsolation(plan.identity) {
                    coroutine.ensureActive()
                    val (_, prior, binding) = successorBackupBinding(actual, stage)
                    val backup = quantumCustody.requireSuccessorBackup(binding)
                    val entry = checkNotNull(network.tos.wallet.data.account.pq.QuantumProbeFundingJournal(context).pending(plan.identity))
                    val probe = checkNotNull(network.tos.wallet.data.account.pq.QuantumRecoveryProbeJournal(context).pending(plan.identity))
                    val now = System.currentTimeMillis() / 1000
                    val restored = result.prepareMigration(entry, prior, probe, backup, now, 120, plan.expiresAt)
                    plan.requireRecords(entry, prior, probe, backup)
                    check(restored.request.request.hash() == plan.request.request.hash() &&
                        restored.boundStateBOC.contentEquals(plan.boundStateBOC)) { "Migration changed after fee review" }
                    val key = plan.identity.cell.hash().toByteArray().joinToString("") { "%02x".format(it) }
                    val store = network.tos.wallet.data.account.pq.QuantumMigrationSigningStore(
                        network.tos.wallet.data.account.pq.QuantumMigrationSigningAndroidIO(context, key))
                    val saved = checkNotNull(store.load())
                    val snapshot = network.tos.wallet.data.account.pq.QuantumMigrationSigningSnapshot.create(restored, entry, prior, probe, now)
                    check(saved.binding.contentEquals(snapshot.encode())) { "Migration reservation changed before dispatch" }
                    quote.requireFresh(saved, now)
                    val signature = saved.signature()
                    val message = try { restored.signedMessage(signature, now) } finally { signature.fill(0) }
                    val approval = quote.approvalRecord()
                    network.tos.wallet.data.account.pq.QuantumMigrationApproval.decode(approval).requireBound(saved.operation,
                        QuantumPaymentOutbox.digest(message), restored.request.digest, restored.expiresAt)
                    coroutine.ensureActive()
                        store.submitOnce(saved, approval, validate = {
                            coroutine.ensureActive()
                            restored.requireFresh(System.currentTimeMillis() / 1000)
                            quote.requireSubmitting(checkNotNull(store.load()), System.currentTimeMillis() / 1000)
                        }, dispatch = {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException("Migration submission cancelled")
                            network.tos.wallet.data.account.pq.QuantumPaymentHttpTransport(quantumPaymentEndpoint(), quantumHttpClient).send(message)
                        })
                    }
                }
            }
        }

    private fun observeCompletedRecoveryLock(wallet: WalletEntity): QuantumAccountReader.Observation {
        val identity = requireNotNull(wallet.quantumIdentity)
        check(QuantumPaymentOutbox(context).requiresRecovery(identity))
        val completed = checkNotNull(network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal(context).pending(identity))
        check(completed.walletId.toString() == wallet.id &&
            completed.phase == network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal.Phase.COMPLETED &&
            completed.kind == network.tos.wallet.data.account.pq.QuantumPreparedRecoveryControl.Kind.LOCK_PRIMARY)
        val reader = QuantumAccountReader(context, QuantumProofHttpTransport(quantumPaymentEndpoint(), quantumHttpClient))
        val observed = reader.observe(UUID.fromString(wallet.id), identity, intArrayOf(8, 19))
        val now = System.currentTimeMillis() / 1000
        require(sameProfile(observed.profile, completed.profile))
        network.tos.wallet.data.account.pq.QuantumSelfPayNetwork.requireRecoveryBase(observed.network, observed.profile.globalId, now)
        val state = (network.tos.wallet.data.account.pq.QuantumSelfPayAccount.bind(identity, observed.proof, now, 300)
            .snapshot(now).status as? network.tos.wallet.data.account.pq.QuantumSelfPayAccount.Status.Active)?.state
            ?: error("Recovery wallet no longer active")
        check(state.cell.hash() == completed.expectedControlState().cell.hash())
        return observed
    }

    /** Unsigned random challenge only. Durable signing and funded verification follow separately. */
    suspend fun prepareQuantumRecoveryProbe(wallet: WalletEntity,
        successor: network.tos.blockchain.ton.contract.QuantumSelfPayAuthority,
    ): network.tos.wallet.data.account.pq.QuantumPreparedRecoveryProbe = runInterruptible(Dispatchers.IO) {
        val observed = observeCompletedRecoveryLock(wallet)
        val now = System.currentTimeMillis() / 1000
        val challenge = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        network.tos.wallet.data.account.pq.QuantumPreparedRecoveryProbe(observed, successor, challenge, now, 120)
    }

    data class RecoveryProbeStatus(val operation: UUID, val phase: String, val address: String,
        val digest: String, val materialDigest: String, val deadline: Long, val bodyDigest: String?)

    private fun probeStatus(entry: network.tos.wallet.data.account.pq.QuantumRecoveryProbeJournal.Entry): RecoveryProbeStatus {
        fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it) }
        return RecoveryProbeStatus(entry.operation, entry.phase.name, QuantumAccountReader.rawAddressFor(entry.probe.address),
            hex(entry.probe.digest), hex(entry.materialDigest), entry.deadline,
            if (entry.phase == network.tos.wallet.data.account.pq.QuantumRecoveryProbeJournal.Phase.SIGNED)
                hex(QuantumPaymentOutbox.digest(entry.signedBody())) else null)
    }

    suspend fun pendingQuantumRecoveryProbe(wallet: WalletEntity): RecoveryProbeStatus? = runInterruptible(Dispatchers.IO) {
        network.tos.wallet.data.account.pq.QuantumRecoveryProbeJournal(context).pending(requireNotNull(wallet.quantumIdentity))?.let {
            require(it.walletId.toString() == wallet.id); probeStatus(it)
        }
    }

    private fun requireProbePreparationHandoff(observed: QuantumAccountReader.Observation, now: Long) {
        QuantumPaymentOutbox(context).withRecoveryControlIsolation(observed.identity) {
            val prior = checkNotNull(network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal(context).pending(observed.identity))
            network.tos.wallet.data.account.pq.QuantumRecoveryControlHandoff.acquire(prior, observed, now)
        }
    }

    /** Read-only preflight before product PIN. No challenge lifetime starts while the prior authorization is live. */
    suspend fun requireQuantumProbePreparationReady(wallet: WalletEntity) = withContext(Dispatchers.IO) {
        quantumCreationMutex.withLock {
            val actual = checkNotNull(database.getAccount(wallet.id))
            require(actual.identity == wallet.identity && actual.type == Wallet.Type.Default)
            val observed = observeCompletedRecoveryLock(actual)
            currentCoroutineContext().ensureActive()
            check(network.tos.wallet.data.account.pq.QuantumRecoveryProbeJournal(context).pending(observed.identity) == null) {
                "A possession challenge is already saved"
            }
            requireProbePreparationHandoff(observed, System.currentTimeMillis() / 1000)
        }
    }

    /** Caller explicitly reviews preparation. No signature or funded probe is produced. */
    suspend fun reserveQuantumRecoveryProbe(wallet: WalletEntity): RecoveryProbeStatus = withContext(Dispatchers.IO) {
        quantumCreationMutex.withLock {
            val actual = checkNotNull(database.getAccount(wallet.id))
            require(actual.identity == wallet.identity && actual.type == Wallet.Type.Default)
            val observed = observeCompletedRecoveryLock(actual)
            val identity = requireNotNull(actual.quantumIdentity); val now = System.currentTimeMillis() / 1000
            val state = (network.tos.wallet.data.account.pq.QuantumSelfPayAccount.bind(identity, observed.proof, now, 300)
                .snapshot(now).status as network.tos.wallet.data.account.pq.QuantumSelfPayAccount.Status.Active).state
            requireProbePreparationHandoff(observed, now)
            val staged = quantumCustody.successorMaterial(UUID.fromString(actual.id), identity, state, observed.profile, true)
            currentCoroutineContext().ensureActive()
            val challenge = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val plan = network.tos.wallet.data.account.pq.QuantumPreparedRecoveryProbe(observed, staged.inspection.snapshot,
                challenge, System.currentTimeMillis() / 1000, 120)
            currentCoroutineContext().ensureActive()
            probeStatus(network.tos.wallet.data.account.pq.QuantumRecoveryProbeJournal(context)
                .reserve(plan, staged.inspection.encryptedDigest, System.currentTimeMillis() / 1000))
        }
    }

    /** Caller authenticates. SIGNING is durable before protected staged material can sign. */
    suspend fun signQuantumRecoveryProbe(wallet: WalletEntity, operation: UUID) = withContext(Dispatchers.IO) {
        quantumCreationMutex.withLock {
            val coroutine = currentCoroutineContext(); coroutine.ensureActive()
            val actual = checkNotNull(database.getAccount(wallet.id))
            require(actual.identity == wallet.identity && actual.type == Wallet.Type.Default)
            val observed = observeCompletedRecoveryLock(actual)
            val identity = requireNotNull(actual.quantumIdentity); val now = System.currentTimeMillis() / 1000
            val state = (network.tos.wallet.data.account.pq.QuantumSelfPayAccount.bind(identity, observed.proof, now, 300)
                .snapshot(now).status as network.tos.wallet.data.account.pq.QuantumSelfPayAccount.Status.Active).state
            coroutine.ensureActive()
            network.tos.wallet.data.account.pq.QuantumRecoveryProbeJournal(context).sign(identity, operation, observed, now) { entry ->
                coroutine.ensureActive()
                val signingNow = System.currentTimeMillis() / 1000
                entry.requireCurrent(observed, signingNow)
                quantumCustody.signSuccessorProbe(UUID.fromString(actual.id), identity, state, observed.profile,
                    entry.materialDigest, entry.successor, entry.challenge, entry.deadline, signingNow)
            }
        }
    }

    /** Bounded single-probe intent only; no current RESCUE signature, quote, send or unlock. */
    suspend fun prepareQuantumProbeFunding(wallet: WalletEntity, amount: BigInteger):
        network.tos.wallet.data.account.pq.QuantumPreparedProbeFunding = runInterruptible(Dispatchers.IO) {
        network.tos.wallet.data.account.pq.QuantumPreparedProbeFunding.requireAmount(amount)
        val observed = observeCompletedRecoveryLock(wallet)
        val entry = checkNotNull(network.tos.wallet.data.account.pq.QuantumRecoveryProbeJournal(context)
            .pending(requireNotNull(wallet.quantumIdentity)))
        val prior = checkNotNull(network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal(context)
            .pending(requireNotNull(wallet.quantumIdentity)))
        val now = System.currentTimeMillis() / 1000
        val handoff = network.tos.wallet.data.account.pq.QuantumRecoveryControlHandoff.acquire(prior, observed, now)
        network.tos.wallet.data.account.pq.QuantumPreparedProbeFunding(entry, observed, amount, now, 120, handoff).also {
            it.requireHandoff(prior, System.currentTimeMillis() / 1000)
        }
    }

    /** Explicit reservation of the reviewed intent only. No signature, quote, send or unlock. */
    suspend fun reserveQuantumProbeFunding(plan: network.tos.wallet.data.account.pq.QuantumPreparedProbeFunding): UUID =
        withContext(Dispatchers.IO) {
            quantumCreationMutex.withLock {
                val actual = checkNotNull(database.getAccount(plan.walletId.toString()))
                require(actual.type == Wallet.Type.Default && actual.quantumIdentity == plan.identity)
                val observed = observeCompletedRecoveryLock(actual)
                currentCoroutineContext().ensureActive()
                plan.requireRevalidated(observed, System.currentTimeMillis() / 1000)
                currentCoroutineContext().ensureActive()
                network.tos.wallet.data.account.pq.QuantumProbeFundingJournal(context)
                    .reserve(plan, System.currentTimeMillis() / 1000).operation
            }
        }

    /** Public lookup handle only; the returned UUID establishes no authorization. */
    data class RecoveryProbeFundingStatus(val operation: UUID, val phase: String, val signatureDigest: String?)
    /** Saved local phase only, never chain outcome, permission to sign or proof of inclusion. */
    suspend fun pendingQuantumProbeFunding(wallet: WalletEntity): RecoveryProbeFundingStatus? = withContext(Dispatchers.IO) {
        val actual = checkNotNull(database.getAccount(wallet.id))
        require(actual.identity == wallet.identity && actual.type == Wallet.Type.Default)
        network.tos.wallet.data.account.pq.QuantumProbeFundingJournal(context)
            .pending(requireNotNull(actual.quantumIdentity))?.let {
                require(it.walletId.toString() == actual.id)
                RecoveryProbeFundingStatus(it.operation, it.phase.name, it.signatureDigest)
            }
    }

    /** Public lookup handle only; the returned UUID establishes no authorization. */
    suspend fun pendingQuantumProbeFundingOperation(wallet: WalletEntity): UUID? = withContext(Dispatchers.IO) {
        val actual = checkNotNull(database.getAccount(wallet.id))
        require(actual.identity == wallet.identity && actual.type == Wallet.Type.Default)
        currentCoroutineContext().ensureActive()
        network.tos.wallet.data.account.pq.QuantumProbeFundingJournal(context)
            .pending(requireNotNull(actual.quantumIdentity))?.operation
    }

    /** Resume the exact reservation with fresh proofs; no deadline extension or secret access. */
    suspend fun resumeQuantumProbeFunding(wallet: WalletEntity, operation: UUID):
        network.tos.wallet.data.account.pq.QuantumPreparedProbeFunding = withContext(Dispatchers.IO) {
        quantumCreationMutex.withLock {
            val actual = checkNotNull(database.getAccount(wallet.id))
            require(actual.identity == wallet.identity && actual.type == Wallet.Type.Default)
            val observed = observeCompletedRecoveryLock(actual)
            currentCoroutineContext().ensureActive()
            network.tos.wallet.data.account.pq.QuantumProbeFundingJournal(context).resume(
                requireNotNull(actual.quantumIdentity), operation, observed, System.currentTimeMillis() / 1000)
        }
    }

    data class RecoveryProbeVerification(val address: String, val exists: Boolean, val verified: Boolean,
                                         val checkpointTime: Long, val balance: BigInteger?)
    /** Historical funding outcome only; leaves the journal and payment isolation unchanged. */
    suspend fun readQuantumProbeFundingOutcome(wallet: WalletEntity, operation: UUID):
        network.tos.wallet.data.account.pq.QuantumProbeFundingOutcomeReader.Result = withContext(Dispatchers.IO) {
        val actual = checkNotNull(database.getAccount(wallet.id))
        require(actual.type == Wallet.Type.Default && actual.identity == wallet.identity)
        val identity = requireNotNull(actual.quantumIdentity)
        val record = checkNotNull(QuantumRecoveryRecord.findIdentity(context, identity))
        require(record.id.toString() == actual.id && record.identity == identity)
        val endpoint = quantumPaymentEndpoint()
        val transport = QuantumProofHttpTransport(endpoint, quantumHttpClient)
        val history = QuantumHistoryReader(QuantumAccountReader(context, transport), QuantumHistoryHttpTransport(endpoint, quantumHttpClient))
        network.tos.wallet.data.account.pq.QuantumProbeFundingOutcomeReader(context, history, transport)
            .read(record.id, identity, operation)
    }
    /** Record a privately authenticated result; no key installation, retry or recovery unlock. */
    suspend fun completeQuantumProbeFunding(wallet: WalletEntity,
        result: network.tos.wallet.data.account.pq.QuantumProbeFundingOutcomeReader.Result) = withContext(Dispatchers.IO) {
        quantumCreationMutex.withLock {
            val actual = checkNotNull(database.getAccount(wallet.id))
            require(actual.type == Wallet.Type.Default && actual.identity == wallet.identity)
            val identity = requireNotNull(actual.quantumIdentity)
            val record = checkNotNull(QuantumRecoveryRecord.findIdentity(context, identity))
            require(record.id.toString() == actual.id && record.identity == identity)
            val journal = network.tos.wallet.data.account.pq.QuantumProbeFundingJournal(context)
            require(checkNotNull(journal.pending(identity)).walletId == record.id)
            currentCoroutineContext().ensureActive()
            runInterruptible {
                journal.complete(identity, result, System.currentTimeMillis() / 1000)
            }
        }
    }

    /** Fresh same-checkpoint observation only. This does not install keys or permit migration/payment. */
    suspend fun readQuantumRecoveryProbeVerification(wallet: WalletEntity): RecoveryProbeVerification = withContext(Dispatchers.IO) {
        val actual = checkNotNull(database.getAccount(wallet.id))
        require(actual.type == Wallet.Type.Default && actual.identity == wallet.identity)
        runInterruptible {
        val identity = requireNotNull(actual.quantumIdentity)
        val record = checkNotNull(QuantumRecoveryRecord.findIdentity(context, identity))
        require(record.id.toString() == actual.id && record.identity == identity)
        val journal = network.tos.wallet.data.account.pq.QuantumRecoveryProbeJournal(context)
        val entry = checkNotNull(journal.pending(identity))
        require(entry.walletId == record.id && sameProfile(entry.profile, record.profile))
        check(entry.phase == network.tos.wallet.data.account.pq.QuantumRecoveryProbeJournal.Phase.SIGNED)
        check(QuantumPaymentOutbox(context).requiresRecovery(identity))
        val transport = QuantumProofHttpTransport(quantumPaymentEndpoint(), quantumHttpClient)
        val observation = QuantumAccountReader(context, transport).observe(record.id, identity)
        require(sameProfile(observation.profile, record.profile))
        val address = QuantumAccountReader.rawAddressFor(entry.probe.address)
        val proof = network.tos.security.pq.QuantumProofSession(context, record.id, record.profile.anchor())
            .readBound(observation.network.requestAtCheckpoint(address, maximumAge = 300), System.currentTimeMillis() / 1000, transport)
        observation.network.requireSameCheckpoint(proof)
        val now = System.currentTimeMillis() / 1000
        observation.requireFresh(now); proof.requireLive(now, 300)
        network.tos.wallet.data.account.pq.QuantumSelfPayAccount.validateTime(proof.masterchainTime,
            proof.provenAccountTime(address), now, 300)
        val state = proof.provenAccountState(address)
        val active = if (state != null && proof.provenAccountIsActive(address))
            network.tos.blockchain.ton.contract.TosQuantumAccountState.snapshot(
                proof.accountState(address, network.tos.wallet.data.account.pq.QuantumPaymentQuote.hex(entry.probe.code.hash().toByteArray())),
                entry.probe.address, entry.probe.code) else null
        val verified = active?.let { entry.probe.isVerified(entry.probe.address, entry.probe.code, it.data) } ?: false
        check(checkNotNull(journal.pending(identity)).encode().contentEquals(entry.encode()))
        check(QuantumPaymentOutbox(context).requiresRecovery(identity))
        RecoveryProbeVerification(address, state != null, verified, proof.masterchainTime,
            active?.balance ?: if (state == null) BigInteger.ZERO else null)
        }
    }

    /** Native local estimate of the saved signature only; separate explicit confirmation is still required. */
    suspend fun estimateQuantumProbeFunding(wallet: WalletEntity, operation: UUID, maximumEstimatedFee: BigInteger):
        network.tos.wallet.data.account.pq.QuantumProbeFundingQuote = withContext(Dispatchers.IO) {
        quantumCreationMutex.withLock {
            require(maximumEstimatedFee.signum() > 0 && maximumEstimatedFee.bitLength() <= 120)
            val actual = checkNotNull(database.getAccount(wallet.id))
            require(actual.identity == wallet.identity && actual.type == Wallet.Type.Default)
            val identity = requireNotNull(actual.quantumIdentity)
            val record = checkNotNull(QuantumRecoveryRecord.findIdentity(context, identity))
            require(record.id.toString() == actual.id && record.identity == identity)
            val observed = observeCompletedRecoveryLock(actual)
            require(sameProfile(record.profile, observed.profile))
            val journal = network.tos.wallet.data.account.pq.QuantumProbeFundingJournal(context)
            val (plan, message) = journal.signedForEstimate(identity, operation, observed, System.currentTimeMillis() / 1000)
            currentCoroutineContext().ensureActive()
            val request = org.json.JSONObject().put("mode", "live").put("account", QuantumAccountReader.rawAddress(identity))
                .put("max_age_seconds", 300).toString().toByteArray()
            val transport = QuantumProofHttpTransport(quantumPaymentEndpoint(), quantumHttpClient)
            val simulation = network.tos.security.pq.QuantumProofSession(context, record.id, record.profile.anchor())
                .simulate(request, System.currentTimeMillis() / 1000, message, transport)
            currentCoroutineContext().ensureActive()
            val fresh = observeCompletedRecoveryLock(actual)
            val now = System.currentTimeMillis() / 1000
            val (currentPlan, currentMessage) = journal.signedForEstimate(identity, operation, fresh, now)
            currentCoroutineContext().ensureActive()
            network.tos.wallet.data.account.pq.QuantumProbeFundingQuote(operation, plan, message, simulation, request,
                now, maximumEstimatedFee).also { it.requireFresh(operation, currentPlan, currentMessage, now) }
        }
    }

    /** Caller explicitly confirms the displayed native estimate. One dispatch, never an inclusion claim. */
    suspend fun submitQuantumProbeFunding(quote: network.tos.wallet.data.account.pq.QuantumProbeFundingQuote) =
        withContext(Dispatchers.IO) {
            quantumCreationMutex.withLock {
                val plan = quote.plan
                val actual = checkNotNull(database.getAccount(plan.walletId.toString()))
                require(actual.type == Wallet.Type.Default && actual.quantumIdentity == plan.identity)
                val record = checkNotNull(QuantumRecoveryRecord.findIdentity(context, plan.identity))
                require(record.id == plan.walletId && record.identity == plan.identity && sameProfile(record.profile, plan.profile))
                val observed = observeCompletedRecoveryLock(actual)
                require(sameProfile(record.profile, observed.profile))
                currentCoroutineContext().ensureActive()
                runInterruptible {
                    val journal = network.tos.wallet.data.account.pq.QuantumProbeFundingJournal(context)
                    val (currentPlan, message) = journal.beginSubmission(quote, observed, System.currentTimeMillis() / 1000)
                    try {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("Funding submission cancelled")
                        quote.requireFresh(quote.operation, currentPlan, message, System.currentTimeMillis() / 1000)
                        network.tos.wallet.data.account.pq.QuantumPaymentHttpTransport(quantumPaymentEndpoint(), quantumHttpClient).send(message)
                    } finally {
                        // A response, rejection or exception cannot establish non-delivery or inclusion.
                        // A failed uncertainty write conservatively leaves durable SUBMITTING.
                        journal.unknown(plan.identity, quote.operation)
                    }
                }
            }
        }

    /** Caller authenticates this exact operation. No broadcast or recovery unlock. */
    suspend fun signQuantumProbeFunding(wallet: WalletEntity, operation: UUID, reviewedDigest: ByteArray) {
        require(reviewedDigest.size == 32)
        val reviewed = reviewedDigest.copyOf()
        withContext(Dispatchers.IO) {
        quantumCreationMutex.withLock {
            val coroutine = currentCoroutineContext(); coroutine.ensureActive()
            val actual = checkNotNull(database.getAccount(wallet.id))
            require(actual.identity == wallet.identity && actual.type == Wallet.Type.Default)
            val identity = requireNotNull(actual.quantumIdentity)
            val record = checkNotNull(QuantumRecoveryRecord.findIdentity(context, identity))
            require(record.id.toString() == actual.id && record.identity == identity)
            val fresh = observeCompletedRecoveryLock(actual)
            require(sameProfile(record.profile, fresh.profile))
            val now = System.currentTimeMillis() / 1000
            val state = (network.tos.wallet.data.account.pq.QuantumSelfPayAccount.bind(identity, fresh.proof, now, 300)
                .snapshot(now).status as? network.tos.wallet.data.account.pq.QuantumSelfPayAccount.Status.Active)?.state
                ?: error("Probe funding wallet no longer active")
            coroutine.ensureActive()
            network.tos.wallet.data.account.pq.QuantumProbeFundingJournal(context).sign(identity, operation, fresh, now, reviewed) { plan ->
                coroutine.ensureActive()
                require(plan.walletId == record.id && plan.identity == identity && sameProfile(plan.profile, record.profile))
                plan.requireRevalidated(fresh, System.currentTimeMillis() / 1000)
                val staged = quantumCustody.successorMaterial(record.id, identity, state, fresh.profile, true)
                require(staged.walletId == record.id)
                plan.requireStagedMaterial(staged.inspection.snapshot, staged.inspection.encryptedDigest)
                val inspection = quantumCustody.inspectRecoveryMaterial(record.id, record.identity, record.encryptedDigest, record.profile)
                coroutine.ensureActive()
                plan.signProtected(quantumCustody, inspection, state, System.currentTimeMillis() / 1000)
            }
        }
    }
    }

    /** Preparation only: no secret access, signature, fee approval, broadcast or recovery unlock. */
    suspend fun prepareQuantumRecoveryControl(wallet: WalletEntity,
        kind: network.tos.wallet.data.account.pq.QuantumPreparedRecoveryControl.Kind,
    ): network.tos.wallet.data.account.pq.QuantumPreparedRecoveryControl = runInterruptible(Dispatchers.IO) {
        val identity = requireNotNull(wallet.quantumIdentity)
        check(QuantumPaymentOutbox(context).requiresRecovery(identity)) { "Recovery isolation required" }
        val recovery = checkNotNull(QuantumRecoveryRecord.findIdentity(context, identity)) {
            "Recovery control requires retained encrypted-backup material"
        }
        require(recovery.id.toString() == wallet.id)
        val reader = QuantumAccountReader(context, QuantumProofHttpTransport(quantumPaymentEndpoint(), quantumHttpClient))
        val observed = reader.observe(UUID.fromString(wallet.id), identity, intArrayOf(8, 19))
        network.tos.wallet.data.account.pq.QuantumPreparedRecoveryControl(observed, kind, System.currentTimeMillis() / 1000, 120)
    }

    data class RecoveryControlStatus(val operation: UUID, val phase: String, val requestHash: String, val deadline: Long,
                                     val messageRootHash: String?)

    /** Public journal status only; reading SIGNED revalidates its stored native signature. */
    suspend fun pendingQuantumRecoveryControl(wallet: WalletEntity): RecoveryControlStatus? = runInterruptible(Dispatchers.IO) {
        val identity = requireNotNull(wallet.quantumIdentity)
        network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal(context).pending(identity)?.let { entry ->
            require(entry.walletId.toString() == wallet.id && entry.identity == identity)
            RecoveryControlStatus(entry.operation, entry.phase.name,
                entry.requestHash.joinToString("") { "%02x".format(it) }, entry.deadline,
                if (entry.phase in setOf(network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal.Phase.SIGNED,
                        network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal.Phase.SUBMITTING,
                        network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal.Phase.UNKNOWN,
                        network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal.Phase.COMPLETED))
                    BagOfCells(entry.signedMessage()).roots.single().hash().toByteArray().joinToString("") { "%02x".format(it) } else null)
        }
    }

    suspend fun completeQuantumRecoveryControl(wallet: WalletEntity,
        result: network.tos.wallet.data.account.pq.QuantumRecoveryControlOutcomeReader.Result,
    ) = runInterruptible(Dispatchers.IO) {
        val identity = requireNotNull(wallet.quantumIdentity)
        val journal = network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal(context)
        require(checkNotNull(journal.pending(identity)).walletId.toString() == wallet.id)
        journal.complete(identity, result, System.currentTimeMillis() / 1000)
    }

    suspend fun reserveQuantumRecoveryControl(plan: network.tos.wallet.data.account.pq.QuantumPreparedRecoveryControl): UUID =
        runInterruptible(Dispatchers.IO) {
            network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal(context)
                .reserve(plan, System.currentTimeMillis() / 1000).operation
        }

    /** Caller must explicitly authenticate this control. No broadcast or recovery unlock.
     * SIGNING is durable before the protected vault can produce a signature. */
    suspend fun signQuantumRecoveryControl(plan: network.tos.wallet.data.account.pq.QuantumPreparedRecoveryControl,
        operation: UUID,
    ) = withContext(Dispatchers.IO) {
        quantumCreationMutex.withLock {
            val coroutine = currentCoroutineContext()
            coroutine.ensureActive()
            val wallet = checkNotNull(database.getAccount(plan.walletId.toString()))
            check(wallet.quantumIdentity == plan.identity && wallet.type == Wallet.Type.Default)
            val record = checkNotNull(QuantumRecoveryRecord.findIdentity(context, plan.identity))
            check(record.id == plan.walletId && sameProfile(record.profile, plan.profile))
            val reader = QuantumAccountReader(context, QuantumProofHttpTransport(quantumPaymentEndpoint(), quantumHttpClient))
            val fresh = reader.observe(plan.walletId, plan.identity, intArrayOf(8, 19))
            val now = System.currentTimeMillis() / 1000
            plan.requireRevalidated(fresh, now)
            val state = (network.tos.wallet.data.account.pq.QuantumSelfPayAccount.bind(plan.identity, fresh.proof, now, 300)
                .snapshot(now).status as? network.tos.wallet.data.account.pq.QuantumSelfPayAccount.Status.Active)?.state
                ?: error("Recovery control wallet no longer active")
            network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal(context).sign(plan, fresh, now, operation) {
                coroutine.ensureActive()
                val inspection = quantumCustody.inspectRecoveryMaterial(record.id, record.identity, record.encryptedDigest, record.profile)
                coroutine.ensureActive()
                quantumCustody.signRecoveryControl(record.id, inspection, state,
                    BagOfCells(plan.request.request).toByteArray(), System.currentTimeMillis() / 1000)
            }
        }
    }

    /** Native local estimate only. Caller must separately confirm before any future broadcast. */
    suspend fun estimateQuantumRecoveryControl(wallet: WalletEntity, maximumEstimatedFee: BigInteger):
        network.tos.wallet.data.account.pq.QuantumRecoveryControlQuote = runInterruptible(Dispatchers.IO) {
        require(maximumEstimatedFee.signum() > 0 && maximumEstimatedFee.bitLength() <= 120)
        val identity = requireNotNull(wallet.quantumIdentity)
        val record = checkNotNull(QuantumRecoveryRecord.findIdentity(context, identity))
        check(record.id.toString() == wallet.id)
        val journal = network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal(context)
        val entry = checkNotNull(journal.pending(identity))
        check(entry.walletId == record.id && sameProfile(entry.profile, record.profile))
        check(QuantumPaymentOutbox(context).requiresRecovery(identity))
        val transport = QuantumProofHttpTransport(quantumPaymentEndpoint(), quantumHttpClient)
        val observation = QuantumAccountReader(context, transport).observe(record.id, identity, intArrayOf(8, 19))
        entry.requireCurrent(observation, System.currentTimeMillis() / 1000)
        val request = org.json.JSONObject().put("mode", "live").put("account", "0:" + identity.address.address.toByteArray()
            .joinToString("") { "%02x".format(it) }).put("max_age_seconds", 300).toString().toByteArray()
        val simulation = network.tos.security.pq.QuantumProofSession(context, record.id, record.profile.anchor())
            .simulate(request, System.currentTimeMillis() / 1000, entry.signedMessage(), transport)
        network.tos.wallet.data.account.pq.QuantumRecoveryControlQuote(entry, simulation, request,
            System.currentTimeMillis() / 1000, maximumEstimatedFee).also {
            it.requireFresh(checkNotNull(journal.pending(identity)), System.currentTimeMillis() / 1000)
        }
    }

    /** Caller explicitly confirms the displayed quote. Transport success never means inclusion. */
    suspend fun submitQuantumRecoveryControl(quote: network.tos.wallet.data.account.pq.QuantumRecoveryControlQuote) =
        withContext(Dispatchers.IO) {
            val entry = quote.entry
            val wallet = checkNotNull(database.getAccount(entry.walletId.toString()))
            check(wallet.quantumIdentity == entry.identity && wallet.type == Wallet.Type.Default)
            val record = checkNotNull(QuantumRecoveryRecord.findIdentity(context, entry.identity))
            check(record.id == entry.walletId && sameProfile(record.profile, entry.profile))
            runInterruptible {
                val journal = network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal(context)
                val bytes = journal.beginSubmission(quote, System.currentTimeMillis() / 1000)
                try {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Recovery submission cancelled")
                    quote.requireFresh(checkNotNull(journal.pending(entry.identity)), System.currentTimeMillis() / 1000)
                    val singleAttempt = quantumHttpClient.newBuilder().retryOnConnectionFailure(false).build()
                    network.tos.wallet.data.account.pq.QuantumPaymentHttpTransport(quantumPaymentEndpoint(), singleAttempt).send(bytes)
                } finally {
                    journal.unknown(entry.identity, entry.operation)
                }
            }
        }

    private fun quantumPaymentEndpoint() = TosRpcEndpoint.jsonRpcUrl(api.tosRpcEndpoint(false)).toHttpUrl()

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
        val recipient = intent.tokenTransfer?.recipient ?: intent.recipient
        val raw = "${recipient.workchainId}:" + recipient.address.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
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

    /** Validated recovery snapshot; completed housekeeping never touches a canonical authorization. */
    suspend fun quantumRetirementEntry(wallet: WalletEntity): network.tos.wallet.data.account.pq.QuantumPaymentOutbox.Entry? = runInterruptible(Dispatchers.IO) {
        val identity = requireNotNull(wallet.quantumIdentity)
        val record = QuantumCreationRecord.load(context, UUID.fromString(wallet.id))
        check(record.identity == identity)
        val outbox = network.tos.wallet.data.account.pq.QuantumPaymentOutbox(context)
        var state = outbox.retirementStatus(identity)
        if (state.completedCleanupOnly) { outbox.cleanupCompletedRetirement(); state = outbox.retirementStatus(identity) }
        val entry = if (state.recovering) outbox.retirementSnapshot(identity, state.operationId) else outbox.pending(identity)
        entry?.also { check(sameProfile(it.profile, record.profile)) }
    }
    suspend fun quantumLocalPaymentHistory(wallet: WalletEntity) = runInterruptible(Dispatchers.IO) {
        network.tos.wallet.data.account.pq.QuantumPaymentOutbox(context).localHistory(requireNotNull(wallet.quantumIdentity))
    }
    suspend fun quantumRetirementStatus(wallet: WalletEntity) = runInterruptible(Dispatchers.IO) {
        network.tos.wallet.data.account.pq.QuantumPaymentOutbox(context).retirementStatus(requireNotNull(wallet.quantumIdentity))
    }
    suspend fun readQuantumRetirementOutcome(wallet: WalletEntity, cursor: network.tos.wallet.data.account.pq.QuantumPaymentOutcomeReader.Cursor? = null): network.tos.wallet.data.account.pq.QuantumPaymentOutcomeReader.Page {
        val endpoint = quantumPaymentEndpoint()
        return network.tos.wallet.data.account.pq.QuantumPaymentOutcomeReader(context,
            QuantumAccountReader(context, QuantumProofHttpTransport(endpoint, quantumHttpClient)),
            QuantumHistoryHttpTransport(endpoint, quantumHttpClient)).retirementOutcome(UUID.fromString(wallet.id), requireNotNull(wallet.quantumIdentity), cursor)
    }
    /** Explicit user action only: fresh proof beyond original deadline, no signing or broadcasting. */
    suspend fun endExpiredQuantumAuthorization(wallet: WalletEntity, operation: UUID,
        receipt: network.tos.wallet.data.account.pq.QuantumPaymentOutcomeReader.Receipt? = null): network.tos.wallet.data.account.pq.QuantumAuthorizationRetirement.Summary {
        val endpoint = quantumPaymentEndpoint()
        val expiry = network.tos.wallet.data.account.pq.QuantumPaymentOutcomeReader(context,
            QuantumAccountReader(context, QuantumProofHttpTransport(endpoint, quantumHttpClient)),
            QuantumHistoryHttpTransport(endpoint, quantumHttpClient)).retirementExpiry(UUID.fromString(wallet.id), requireNotNull(wallet.quantumIdentity))
        check(expiry.operationId == operation)
        return runInterruptible(Dispatchers.IO) {
            network.tos.wallet.data.account.pq.QuantumPaymentOutbox(context).retire(expiry, receipt) { System.currentTimeMillis() / 1000 }
        }
    }

    private fun quantumCreator(resuming: Boolean, ordinaryRestore: Boolean = false,
        onEnrollment: (UUID) -> Unit = {}): QuantumWalletCreation {
        val endpoint = TosRpcEndpoint.jsonRpcUrl(api.tosRpcEndpoint(false)).toHttpUrl()
        return QuantumWalletCreation(
            beforeStore = { record ->
                if (ordinaryRestore) {
                    val outbox = QuantumPaymentOutbox(context)
                    check(!outbox.requiresRecovery(record.identity)) { "An existing recovery operation must be completed; its saved state cannot be replaced" }
                    val observed = QuantumAccountReader(context, QuantumProofHttpTransport(endpoint, quantumHttpClient))
                        .observeEnrolled(record.id, record.identity, record.profile, intArrayOf(8, 19, 48))
                    val time = System.currentTimeMillis() / 1000
                    observed.requireFresh(time)
                    QuantumSelfPayNetwork.bind(observed.network, record.profile.globalId, time, 300)
                        .requireSameCheckpoint(observed.proof, time)
                    network.tos.wallet.data.account.pq.QuantumOrdinaryRestore.requireUsable(record.identity, observed.snapshot.status)
                }
            },
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
        val wallet = database.registerQuantumAccount(WalletEntity(id = record.id.toString(),
            identity = WalletIdentity.Quantum(record.identity), type = Wallet.Type.Default,
            label = label.create(0), initialized = false))
        // A main-row commit followed by a mirror failure is resumed with the same UUID.
        // Do not delete custody or attempt an unsafe rollback of a possibly committed row.
        addWalletToRN(wallet)
        return wallet
    }

    /** Original local creation only. Caller authenticates; no plaintext material crosses this API.
     * Imported/rotated records must use their retained per-role provenance, not this original 0/0 route. */
    suspend fun exportQuantumCreationBackup(id: String, password: CharArray): ByteArray {
        var acquired = false
        var ownedPassword = CharArray(0)
        var output = ByteArray(0)
        try {
            require(password.size in 12..1024)
            require(Character.codePointCount(password, 0, password.size) >= 12)
            check(quantumBackupImport.tryLock()) { "Another backup operation is in progress" }
            acquired = true
            ownedPassword = password.copyOf()
            return withContext(Dispatchers.IO) {
                quantumCreationMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    val uuid = UUID.fromString(id)
                    require(uuid.toString() == id)
                    val wallet = checkNotNull(database.getAccount(id))
                    val record = QuantumCreationRecord.load(context, uuid)
                    require(wallet.type == Wallet.Type.Default && wallet.quantumIdentity == record.identity)
                    check(QuantumRecoveryRecord.findIdentity(context, record.identity) == null) {
                        "Recovered material requires its original provenance export route"
                    }
                    val reader = QuantumAccountReader(context, QuantumProofHttpTransport(quantumPaymentEndpoint(), quantumHttpClient))
                    fun approved(observation: QuantumAccountReader.Observation): network.tos.blockchain.ton.contract.QuantumSelfPayAuthority {
                        val now = System.currentTimeMillis() / 1000
                        observation.requireFresh(now)
                        require(observation.id == uuid && observation.identity == record.identity && sameProfile(observation.profile, record.profile))
                        val namespace = QuantumSelfPayNetwork.recoveryNamespace(observation.network, record.profile.globalId, now)
                        require(namespace.contentEquals(record.identity.wallet.initialAuthority.namespace))
                        return when (val status = observation.snapshot.status) {
                            is network.tos.wallet.data.account.pq.QuantumSelfPayAccount.Status.Active -> status.state.authority
                            network.tos.wallet.data.account.pq.QuantumSelfPayAccount.Status.Absent,
                            network.tos.wallet.data.account.pq.QuantumSelfPayAccount.Status.Uninitialized -> record.identity.wallet.initialAuthority
                            is network.tos.wallet.data.account.pq.QuantumSelfPayAccount.Status.Frozen -> error("Frozen authority unavailable")
                        }
                    }
                    val before = reader.observe(uuid, record.identity, intArrayOf(8, 19, 48))
                    val authority = approved(before)
                    currentCoroutineContext().ensureActive()
                    output = quantumCustody.exportCreationMaterial(record, authority, ownedPassword.copyOf())
                    val after = reader.observe(uuid, record.identity, intArrayOf(8, 19, 48))
                    check(approved(after).hash.contentEquals(authority.hash)) { "Authority changed during backup export" }
                    before.requireFresh(System.currentTimeMillis() / 1000)
                    currentCoroutineContext().ensureActive()
                    output
                }
            }
        } catch (error: Throwable) { output.fill(0); throw error }
        finally {
            password.fill('\u0000'); ownedPassword.fill('\u0000')
            if (acquired) quantumBackupImport.unlock()
        }
    }

    suspend fun hasOriginalQuantumCreation(id: String): Boolean = withContext(Dispatchers.IO) {
        val wallet = database.getAccount(id) ?: return@withContext false
        if (!wallet.isQuantum || wallet.type != Wallet.Type.Default) return@withContext false
        val record = try { QuantumCreationRecord.load(context, UUID.fromString(id)) }
        catch (error: android.system.ErrnoException) {
            if (error.errno == android.system.OsConstants.ENOENT) return@withContext false else throw error
        }
        wallet.quantumIdentity == record.identity && QuantumRecoveryRecord.findIdentity(context, record.identity) == null
    }

    /** Encrypted-file recovery preserves the birth identity and publishes with ordinary payment isolated. */
    data class QuantumBackupPreview(val address: String, val containsPrimary: Boolean, val containsRescue: Boolean)

    /** Public historical metadata only. The caller's password is consumed on every exit. */
    suspend fun inspectQuantumBackup(encrypted: ByteArray, password: CharArray, profile: QuantumNetworkProfile,
                                    expectedNamespace: ByteArray): QuantumBackupPreview {
        var acquired = false
        var ownedPassword = CharArray(0)
        try {
            require(encrypted.size in 58..262201 && expectedNamespace.size == 32 && password.size in 12..1024)
            check(quantumBackupImport.tryLock()) { "Another backup import is in progress" }
            acquired = true
            val input = encrypted.copyOf(); val namespace = expectedNamespace.copyOf()
            ownedPassword = password.copyOf()
            return withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val backup = QuantumBackupInspection.inspect(input, ownedPassword.copyOf(), profile.globalId,
                    hex(profile.rootHash), hex(profile.fileHash), namespace)
                currentCoroutineContext().ensureActive()
                QuantumBackupPreview(QuantumAccountReader.rawAddress(backup.birth),
                    backup.roles.any { it.role == QuantumRole.PRIMARY }, backup.roles.any { it.role == QuantumRole.RESCUE })
            }
        } finally {
            ownedPassword.fill('\u0000'); password.fill('\u0000')
            if (acquired) quantumBackupImport.unlock()
        }
    }

    suspend fun importQuantumBackup(encrypted: ByteArray, password: CharArray, profile: QuantumNetworkProfile,
        expectedNamespace: ByteArray, label: Wallet.NewLabel): WalletEntity {
        var ownedPassword = CharArray(0)
        var acquired = false
        try {
            require(encrypted.size in 58..262201 && expectedNamespace.size == 32 && password.size in 12..1024)
            check(quantumBackupImport.tryLock()) { "Another backup import is in progress" }
            acquired = true
            val input = encrypted.copyOf(); val namespace = expectedNamespace.copyOf()
            ownedPassword = password.copyOf()
            return withContext(Dispatchers.IO) {
                quantumCreationMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    val backup = QuantumBackupInspection.inspect(input, ownedPassword.copyOf(), profile.globalId,
                        hex(profile.rootHash), hex(profile.fileHash), namespace)
                    val matches = database.getAccounts().filter { it.quantumIdentity == backup.birth }
                    check(matches.size <= 1) { "Ambiguous existing wallet" }
                    matches.singleOrNull()?.let { existing ->
                        val record = checkNotNull(QuantumRecoveryRecord.findIdentity(context, backup.birth))
                        check(record.id.toString() == existing.id && sameProfile(record.profile, profile) &&
                            record.encryptedDigest.contentEquals(backup.encryptedDigest)) { "Existing wallet cannot be replaced" }
                    }
                    val outbox = QuantumPaymentOutbox(context)
                    val reader = QuantumAccountReader(context, QuantumProofHttpTransport(quantumPaymentEndpoint(), quantumHttpClient))
                    val checkpoint = outbox.prepareBackupImport(backup, profile)
                    val observed = reader.observeRecoveryImport(checkpoint, backup, profile)
                    outbox.storeBackupImport(input, ownedPassword.copyOf(), profile, namespace, observed) { System.currentTimeMillis() / 1000 }
                    val durable = outbox.retainBackupImportRecord(backup, profile)
                    val fresh = reader.observeRecoveryImport(durable, backup, profile)
                    outbox.publishBackupImport(backup, fresh, { System.currentTimeMillis() / 1000 }) { record ->
                        val wallet = database.registerQuantumAccount(WalletEntity(record.id.toString(),
                            WalletIdentity.Quantum(record.identity), Wallet.Type.Default, label.create(0), initialized = false))
                        addWalletToRN(wallet)
                        wallet
                    }
                }
            }
        } finally {
            ownedPassword.fill('\u0000'); password.fill('\u0000')
            if (acquired) quantumBackupImport.unlock()
        }
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
