package network.tos.wallet.app.manager.tx

import android.util.Log
import network.tos.blockchain.ton.extensions.base64
import network.tos.blockchain.ton.extensions.cellFromBase64
import network.tos.blockchain.ton.contract.TosWalletV5R1Contract
import network.tos.extensions.MutableEffectFlow
import network.tos.wallet.app.App
import network.tos.wallet.app.worker.WidgetUpdaterWorker
import network.tos.wallet.api.API
import network.tos.wallet.api.SendBlockchainState
import network.tos.wallet.api.entity.AccountEventEntity
import network.tos.wallet.api.entity.ConfigEntity
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.battery.BatteryRepository
import network.tos.wallet.data.settings.SettingsRepository
import network.tos.wallet.data.token.TokenRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ton.bitstring.BitString
import org.ton.cell.Cell
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionManager(
    private val accountRepository: AccountRepository,
    private val api: API,
    private val batteryRepository: BatteryRepository,
    private val tokenRepository: TokenRepository,
    private val settingsRepository: SettingsRepository,
): BaseTransactionManager(api) {

    private val _sendingTransactionFlow = MutableSharedFlow<SendingTransaction>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val sendingTransactionFlow = _sendingTransactionFlow.asSharedFlow()

    private val _transactionFlow = MutableSharedFlow<AccountEventEntity>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val transactionFlow = _transactionFlow.asSharedFlow()

    private val _tronUpdatedFlow = MutableEffectFlow<Unit>()
    val tronUpdatedFlow = _tronUpdatedFlow.asSharedFlow()

    private var tronRefreshJob: Job? = null

    init {
        _tronUpdatedFlow.tryEmit(Unit)
        sendingTransactionFlow.mapNotNull { getTransaction(it.wallet, it.hash) }
            .onEach { transaction ->
                _transactionFlow.tryEmit(transaction)
            }.launchIn(scope)

        combine(
            api.configFlow.filter { !it.empty },
            accountRepository.selectedWalletFlow
        ) { config, wallet ->
            realtime(config, wallet)
        }.flatMapLatest { it }.filterNotNull().onEach { transaction ->
            _transactionFlow.tryEmit(transaction)
        }.launchIn(scope)

        sendingTransactionFlow.onEach {
            delay(5000)
            WidgetUpdaterWorker.update(App.instance)
        }.launchIn(scope)

        combine(
            accountRepository.selectedWalletFlow,
            tronUpdatedFlow,
            settingsRepository.tokenPrefsChangedFlow
        ) { wallet, _, _ ->
            val tronEnabled = settingsRepository.getTronUsdtEnabled(wallet.id)
            val tronAddress = accountRepository.getTronAddress(wallet.id)
            if (tronEnabled && tronAddress != null && wallet.hasPrivateKey && !wallet.testnet && !api.config.flags.disableBattery) {
                Pair(wallet, tronAddress)
            } else {
                null
            }
        }.filterNotNull().onEach { (wallet, tronAddress) ->
            tronRefreshJob?.cancel()
            tronRefreshJob = scope.launch {
                delay(30.seconds)
                tokenRepository.refreshTron(wallet.accountId, wallet.testnet, tronAddress)

                _tronUpdatedFlow.tryEmit(Unit)
            }
        }.launchIn(scope)
    }

    fun eventsFlow(wallet: WalletEntity) = transactionFlow.filter {
        it.accountId == wallet.accountId && it.testnet == wallet.testnet
    }

    private fun realtime(config: ConfigEntity, wallet: WalletEntity) = api.realtime(
        accountId = wallet.accountId,
        testnet = wallet.testnet,
        config = config,
        onFailure = null
    ).map { it.data }.map { getTransaction(wallet, it) }

    private suspend fun getTransaction(
        wallet: WalletEntity,
        hash: String
    ): AccountEventEntity? = withContext(Dispatchers.IO) {
        api.getTransactionByHash(wallet.accountId, wallet.testnet, hash)
    }

    private suspend fun sendWithBattery(
        wallet: WalletEntity,
        boc: String,
        source: String,
        confirmationTime: Double,
    ): SendBlockchainState {
        val tonProofToken = accountRepository.requestTonProofToken(wallet)
            ?: return SendBlockchainState.UNKNOWN_ERROR
        val state = api.sendToBlockchainWithBattery(
            boc = boc,
            tonProofToken = tonProofToken,
            testnet = wallet.testnet,
            source = source,
            confirmationTime = confirmationTime
        )
        if (state == SendBlockchainState.SUCCESS) {
            batteryRepository.refreshBalanceDelay(
                publicKey = wallet.publicKey,
                tonProofToken = tonProofToken,
                testnet = wallet.testnet,
            )
        }
        return state
    }

    suspend fun send(
        wallet: WalletEntity,
        boc: String,
        withBattery: Boolean,
        source: String,
        normalizedHash: BitString,
        confirmationTime: Double,
        boundNode: network.tos.wallet.api.tos.TosSource? = null,
    ): SendBlockchainState {
        val node = boundNode ?: api.tos.snapshot(wallet.testnet)
        wallet.networkGlobalId?.let { node.requireNetwork(it, wallet.testnet) }
        val initialSeqno = if (withBattery) null else {
            val native = wallet.contract as? TosWalletV5R1Contract
            native?.signedTransferSeqno(boc.cellFromBase64())
                ?: node.getSeqno(wallet.accountId, wallet.testnet)
        }
        return send(wallet, boc, withBattery, source, confirmationTime, normalizedHash, initialSeqno, 0, node)
    }

    private suspend fun send(
        wallet: WalletEntity,
        boc: String,
        withBattery: Boolean,
        source: String,
        confirmationTime: Double,
        normalizedHash: BitString,
        initialSeqno: Int?,
        attempt: Int,
        node: network.tos.wallet.api.tos.TosSource,
    ): SendBlockchainState {
        fun reconcile(): SendBlockchainState? {
            if (initialSeqno == null) return null
            val receipt = runCatching { node.reconcileSend(wallet.accountId, boc, initialSeqno, wallet.testnet) }
                .getOrDefault(network.tos.wallet.api.tos.TosSendReconciliation.AMBIGUOUS)
            return when (receipt) {
                network.tos.wallet.api.tos.TosSendReconciliation.CONFIRMED -> {
                    _sendingTransactionFlow.tryEmit(SendingTransaction(wallet.copy(), boc))
                    SendBlockchainState.SUCCESS
                }
                network.tos.wallet.api.tos.TosSendReconciliation.AMBIGUOUS -> SendBlockchainState.UNKNOWN_ERROR
                network.tos.wallet.api.tos.TosSendReconciliation.RETRYABLE -> null
            }
        }
        // Native requests are also checked before their first broadcast: another
        // device may consume the signed sequence while the user enters a passcode.
        // Legacy initial-send behavior stays unchanged.
        if (attempt > 0 || wallet.contract is TosWalletV5R1Contract) reconcile()?.let { return it }
        val state = if (withBattery) {
            sendWithBattery(wallet, boc, source, confirmationTime)
        } else {
            api.sendToBlockchain(boc, wallet.testnet, source, confirmationTime, wallet.networkGlobalId, node)
        }
        if (state == SendBlockchainState.SUCCESS) {
            // addPendingHash(wallet.accountId, wallet.testnet, normalizedHash.toHex())
            _sendingTransactionFlow.tryEmit(SendingTransaction(wallet.copy(), boc))
            return state
        }

        // A transport error can occur after acceptance. A nonce advance from a
        // different device does not prove delivery and must stop automatic replay.
        reconcile()?.let { return it }

        return if (attempt > 3) {
            state
        } else {
            delay(10.seconds)
            send(wallet, boc, withBattery, source, confirmationTime, normalizedHash, initialSeqno, attempt + 1, node)
        }
    }

    suspend fun send(
        wallet: WalletEntity,
        boc: Cell,
        withBattery: Boolean,
        source: String,
        confirmationTime: Double,
        boundNode: network.tos.wallet.api.tos.TosSource? = null,
    ) = send(
        wallet = wallet,
        boc = boc.base64(),
        withBattery = withBattery,
        source = source,
        normalizedHash = wallet.contract.normalizedHashFromSignedBody(boc) ?: boc.hash(),
        confirmationTime = confirmationTime,
        boundNode = boundNode,
    )
}
