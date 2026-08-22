package network.tos.wallet.app.ui.screen.collectibles.main

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import network.tos.blockchain.ton.extensions.toRawAddress
import network.tos.extensions.flattenFirst
import network.tos.network.NetworkMonitor
import network.tos.wallet.app.extensions.isSafeModeEnabled
import network.tos.wallet.app.extensions.with
import network.tos.wallet.app.manager.tx.TransactionManager
import network.tos.wallet.app.ui.base.UiListState
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.screen.collectibles.main.list.Item
import network.tos.wallet.app.ui.screen.dns.TosDnsTransactionBuilder
import network.tos.wallet.app.ui.screen.send.transaction.SendTransactionScreen
import network.tos.blockchain.ton.dns.TosDnsOperation
import network.tos.wallet.api.API
import network.tos.wallet.api.tos.TosDnsLifecycle
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.collectibles.CollectiblesRepository
import network.tos.wallet.data.collectibles.entities.DnsExpiringEntity
import network.tos.wallet.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CollectiblesViewModel(
    app: Application,
    private val wallet: WalletEntity,
    private val collectiblesRepository: CollectiblesRepository,
    private val networkMonitor: NetworkMonitor,
    private val settingsRepository: SettingsRepository,
    private val transactionManager: TransactionManager,
    private val api: API
): BaseWalletVM(app) {

    data class DomainManagementPreview(
        val canonicalName: String,
        val lifecycle: TosDnsLifecycle,
        val amount: java.math.BigInteger,
        val targetAddress: String,
        val checkpointSequence: Int,
        val checkpointAge: Long,
        val testnet: Boolean,
    )

    data class DomainRegistrationPreview(
        val canonicalName: String,
        val amount: java.math.BigInteger,
        val collectionAddress: String,
        val checkpointSequence: Int,
        val checkpointAge: Long,
        val testnet: Boolean,
    )

    private val _ltFlow = MutableStateFlow(0L)
    private val ltFlow = _ltFlow.asStateFlow()

    val installId: String
        get() = settingsRepository.installId

    private val expiringDomainsFlow = flow {
        emit(collectiblesRepository.getDnsSoonExpiring(
            accountId = wallet.accountId,
            testnet = wallet.testnet
        ).associateBy { it.addressRaw })
    }

    private val triggerFlow = combine(
        settingsRepository.tokenPrefsChangedFlow,
        settingsRepository.safeModeStateFlow,
        ltFlow
    ) { _, _, _ -> }

    val uiListStateFlow = combine(
        networkMonitor.isOnlineFlow,
        settingsRepository.hiddenBalancesFlow,
        triggerFlow,
        expiringDomainsFlow
    ) { isOnline, hiddenBalances, _, expiringDomains ->
        stateFlow(
            wallet = wallet,
            hiddenBalances = hiddenBalances,
            isOnline = isOnline,
            expiringDomains = expiringDomains
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null).filterNotNull().flattenFirst()

    var hasNfts = false
        private set

    init {
        transactionManager.eventsFlow(wallet).collectFlow {
            _ltFlow.value = it.lt
        }
    }

    private fun stateFlow(
        wallet: WalletEntity,
        hiddenBalances: Boolean,
        isOnline: Boolean,
        expiringDomains: Map<String, DnsExpiringEntity>
    ): Flow<UiListState> = flow {
        emit(UiListState.Loading)
        emitAll(itemsFlow(wallet, hiddenBalances, isOnline, expiringDomains))
    }

    private fun itemsFlow(
        wallet: WalletEntity,
        hiddenBalances: Boolean,
        isOnline: Boolean,
        expiringDomains: Map<String, DnsExpiringEntity>
    ): Flow<UiListState> = collectiblesRepository.getFlow(wallet.address, wallet.testnet, isOnline).map { result ->
        hasNfts = result.list.isNotEmpty()
        val safeMode = settingsRepository.isSafeModeEnabled(api)
        val uiItems = mutableListOf<Item>()
        for (nft in result.list) {
            if (safeMode && !nft.verified) {
                continue
            }

            val isHiddenCollection = settingsRepository.getTokenPrefs(wallet.id, nft.collectionAddressOrNFTAddress).isHidden

            if (isHiddenCollection) {
                continue
            }

            val nftPref = settingsRepository.getTokenPrefs(wallet.id, nft.collectionAddressOrNFTAddress)
            if (nftPref.isHidden) {
                continue
            }
            val expiringDomain = expiringDomains[nft.address.toRawAddress()]
            uiItems.add(Item.Nft(
                wallet = wallet,
                entity = nft.with(nftPref),
                hiddenBalance = hiddenBalances,
                expiringDomainSoon = expiringDomain != null
            ))
        }

        if (uiItems.isEmpty() && !result.cache) {
            UiListState.Empty
        } else if (uiItems.isEmpty()) {
            UiListState.Loading
        } else {
            UiListState.Items(result.cache, uiItems.toList())
        }
    }.flowOn(Dispatchers.IO)

    fun refresh() {
        _ltFlow.value += 1
    }

    fun inspectDomainRegistration(input: String, completion: (DomainRegistrationPreview?) -> Unit) {
        viewModelScope.launch {
            val preview = try {
                val canonical = network.tos.wallet.api.tos.TosDnsResolver.canonicalName(input)
                require(canonical == input && canonical.count { it == '.' } == 1) {
                    "registration requires the exact lowercase second-level name"
                }
                val now = System.currentTimeMillis() / 1000
                require(now > TosDnsOperation.AUCTION_START_TIME) { "DNS registration has not launched" }
                val state = withContext(Dispatchers.IO) {
                    api.tos.inspectDnsDomain(canonical, wallet.testnet, now)
                }
                require(state.lifecycle == TosDnsLifecycle.AVAILABLE)
                val bid = TosDnsOperation.minimumPrice(state.label.encodeToByteArray().size, now)
                DomainRegistrationPreview(
                    canonical, bid, state.collectionAddress, state.checkpoint.seqno,
                    maxOf(0, now - state.observedAt), wallet.testnet,
                )
            } catch (_: Throwable) {
                null
            }
            completion(preview)
        }
    }

    fun registerDomain(preview: DomainRegistrationPreview, completion: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = try {
                val now = System.currentTimeMillis() / 1000
                val state = withContext(Dispatchers.IO) {
                    api.tos.inspectDnsDomain(preview.canonicalName, wallet.testnet, now)
                }
                val bid = TosDnsOperation.minimumPrice(state.label.encodeToByteArray().size, now)
                val request = TosDnsTransactionBuilder.createSignRequest(
                    wallet,
                    state,
                    TosDnsTransactionBuilder.Action.Register(bid),
                    now = now,
                )
                SendTransactionScreen.run(context, wallet, request)
                true
            } catch (_: Throwable) {
                false
            }
            completion(success)
            if (success) refresh()
        }
    }

    fun inspectDomainManagement(input: String, completion: (DomainManagementPreview?) -> Unit) {
        viewModelScope.launch {
            val preview = try {
                val canonical = network.tos.wallet.api.tos.TosDnsResolver.canonicalName(input)
                require(canonical == input && canonical.count { it == '.' } == 1)
                val now = System.currentTimeMillis() / 1000
                val state = withContext(Dispatchers.IO) {
                    api.tos.inspectDnsDomain(canonical, wallet.testnet, now)
                }
                DomainManagementPreview(
                    canonical, state.lifecycle, managementAmount(state, now), state.itemAddress,
                    state.checkpoint.seqno, maxOf(0, now - state.observedAt), wallet.testnet,
                )
            } catch (_: Throwable) {
                null
            }
            completion(preview)
        }
    }

    fun manageDomain(preview: DomainManagementPreview, completion: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = try {
                val now = System.currentTimeMillis() / 1000
                val state = withContext(Dispatchers.IO) {
                    api.tos.inspectDnsDomain(preview.canonicalName, wallet.testnet, now)
                }
                require(state.lifecycle == preview.lifecycle) { "DNS lifecycle changed" }
                val action = when (state.lifecycle) {
                    TosDnsLifecycle.AUCTION -> TosDnsTransactionBuilder.Action.Bid(
                        TosDnsOperation.minimumNextBid(state.maximumBid),
                    )
                    TosDnsLifecycle.AUCTION_ENDED -> TosDnsTransactionBuilder.Action.FinishAuction
                    TosDnsLifecycle.RELEASABLE -> TosDnsTransactionBuilder.Action.Release(
                        TosDnsOperation.minimumPrice(state.label.encodeToByteArray().size, now),
                    )
                    else -> error("domain has no public auction action")
                }
                SendTransactionScreen.run(
                    context,
                    wallet,
                    TosDnsTransactionBuilder.createSignRequest(wallet, state, action, now = now),
                )
                true
            } catch (_: Throwable) {
                false
            }
            completion(success)
            if (success) refresh()
        }
    }

    private fun managementAmount(
        state: network.tos.wallet.api.tos.TosDnsDomainState,
        now: Long,
    ): java.math.BigInteger = when (state.lifecycle) {
        TosDnsLifecycle.AUCTION -> TosDnsOperation.minimumNextBid(state.maximumBid)
        TosDnsLifecycle.AUCTION_ENDED -> TosDnsOperation.CONTRACT_ACTION_VALUE
        TosDnsLifecycle.RELEASABLE -> TosDnsOperation.minimumPrice(state.label.encodeToByteArray().size, now)
        else -> error("domain has no public auction action")
    }

}
