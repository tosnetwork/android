package com.tonapps.tonkeeper.ui.screen.send.main

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tonapps.blockchain.ton.TonAddressTags
import com.tonapps.blockchain.ton.contract.WalletFeature
import com.tonapps.blockchain.ton.extensions.equalsAddress
import com.tonapps.blockchain.ton.extensions.isValidTonAddress
import com.tonapps.blockchain.tron.TronTransfer
import com.tonapps.blockchain.tron.isValidTronAddress
import com.tonapps.extensions.MutableEffectFlow
import com.tonapps.extensions.filterList
import com.tonapps.extensions.singleValue
import com.tonapps.extensions.state
import com.tonapps.icu.Coins
import com.tonapps.icu.CurrencyFormatter
import com.tonapps.tonkeeper.core.Amount
import com.tonapps.tonkeeper.core.AnalyticsHelper
import com.tonapps.tonkeeper.core.Fee
import com.tonapps.tonkeeper.core.SendBlockchainException
import com.tonapps.tonkeeper.core.entities.SendMetadataEntity
import com.tonapps.tonkeeper.core.entities.TransferEntity
import com.tonapps.tonkeeper.extensions.isPrintableAscii
import com.tonapps.tonkeeper.extensions.isSafeModeEnabled
import com.tonapps.tonkeeper.extensions.with
import com.tonapps.tonkeeper.manager.tx.TransactionManager
import com.tonapps.tonkeeper.ui.base.BaseWalletVM
import com.tonapps.tonkeeper.ui.screen.send.main.SendScreen.Companion.Type
import com.tonapps.tonkeeper.ui.screen.send.main.helper.InsufficientBalanceType
import com.tonapps.tonkeeper.ui.screen.send.main.helper.isEmptyBalance
import com.tonapps.tonkeeper.ui.screen.send.main.helper.isTON
import com.tonapps.tonkeeper.ui.screen.send.main.state.SendAmountState
import com.tonapps.tonkeeper.ui.screen.send.main.state.SendDestination
import com.tonapps.tonkeeper.ui.screen.send.main.state.SendFee
import com.tonapps.tonkeeper.ui.screen.send.main.state.SendTransaction
import com.tonapps.tonkeeper.ui.screen.send.main.state.TonTransaction
import com.tonapps.tonkeeper.usecase.emulation.EmulationUseCase
import com.tonapps.tonkeeper.usecase.emulation.InsufficientBalanceError
import com.tonapps.tonkeeper.usecase.sign.SignUseCase
import com.tonapps.wallet.api.API
import com.tonapps.wallet.api.SendBlockchainState
import com.tonapps.wallet.api.entity.TokenEntity
import com.tonapps.wallet.api.entity.value.Blockchain
import com.tonapps.wallet.api.tron.entity.TronResourcesEntity
import com.tonapps.wallet.data.account.AccountRepository
import com.tonapps.wallet.data.account.Wallet
import com.tonapps.wallet.data.account.entities.WalletEntity
import com.tonapps.wallet.data.battery.BatteryMapper
import com.tonapps.wallet.data.battery.BatteryRepository
import com.tonapps.wallet.data.battery.entity.BatteryBalanceEntity
import com.tonapps.wallet.data.collectibles.CollectiblesRepository
import com.tonapps.wallet.data.collectibles.entities.NftEntity
import com.tonapps.wallet.data.rates.RatesRepository
import com.tonapps.wallet.data.settings.BatteryTransaction
import com.tonapps.wallet.data.settings.SettingsRepository
import com.tonapps.wallet.data.settings.entities.PreferredFeeMethod
import com.tonapps.wallet.data.token.TokenRepository
import com.tonapps.wallet.data.token.entities.AccountTokenEntity
import com.tonapps.wallet.localization.Localization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ton.block.AddrStd
import org.ton.cell.Cell
import uikit.extensions.collectFlow
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

@OptIn(FlowPreview::class)
class SendViewModel(
    app: Application,
    private val wallet: WalletEntity,
    private val nftAddress: String,
    private val accountRepository: AccountRepository,
    private val api: API,
    private val settingsRepository: SettingsRepository,
    private val tokenRepository: TokenRepository,
    private val ratesRepository: RatesRepository,
    private val collectiblesRepository: CollectiblesRepository,
    private val batteryRepository: BatteryRepository,
    private val transactionManager: TransactionManager,
    private val emulationUseCase: EmulationUseCase,
    private val signUseCase: SignUseCase,
    private val analytics: AnalyticsHelper
) : BaseWalletVM(app) {

    private val isNft: Boolean
        get() = nftAddress.isNotBlank()

    val installId: String
        get() = settingsRepository.installId

    val isTronDisabled: Boolean
        get() = api.config.flags.disableTron

    val isBatteryDisabled: Boolean
        get() = api.config.flags.disableBattery

    data class UserInput(
        val address: String = "",
        val amount: Coins = Coins.ZERO,
        val token: TokenEntity = TokenEntity.TON,
        val comment: String? = null,
        val nft: NftEntity? = null,
        val encryptedComment: Boolean = false,
        val max: Boolean = false,
        val amountCurrency: Boolean = false,
        val bin: Cell? = null,
        val type: Type = Type.Default
    )

    val currency = settingsRepository.currency
    private val queryId: BigInteger by lazy { TransferEntity.newWalletQueryId() }

    private val _userInputFlow = MutableStateFlow(UserInput())
    private val userInputFlow = _userInputFlow.asStateFlow()

    private var lastTransferEntity: TransferEntity? = null
    private var tokenCustomPayload: TokenEntity.TransferPayload? = null

    private val userInputAddressFlow = userInputFlow.map { it.address }.distinctUntilChanged()
        .debounce { if (it.isEmpty()) 0 else 600 }

    private val _tokensFlow = MutableStateFlow<List<AccountTokenEntity>?>(null)
    private val tokensFlow = _tokensFlow.asStateFlow().filterNotNull()

    val tronAvailableFlow = tokensFlow.map { tokens ->
        tokens.any { it.isTrc20 } && settingsRepository.getTronUsdtEnabled(wallet.id)
    }.flowOn(Dispatchers.IO).state(viewModelScope)

    private val selectedTokenFlow = combine(
        tokensFlow, userInputFlow.map { it.token }.distinctUntilChanged()
    ) { tokens, selectedToken ->
        tokens.find { it.address == selectedToken.address } ?: AccountTokenEntity.createEmpty(
            selectedToken, wallet.address
        )
    }.distinctUntilChanged().flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, AccountTokenEntity.EMPTY)

    val destinationFlow = combine(
        userInputAddressFlow,
        tronAvailableFlow,
        selectedTokenFlow
    ) { userInput, isTronAvailable, selectedToken ->
        if (userInput.isEmpty()) {
            SendDestination.Empty
        } else if (isTronAvailable && userInput.isValidTronAddress()) {
            if (selectedToken.isTrc20) {
                SendDestination.TronAccount(userInput)
            } else {
                SendDestination.TokenError(
                    addressBlockchain = Blockchain.TRON,
                    selectedToken = selectedToken.token
                )
            }
        } else {
            val destination = getDestinationAccount(userInput)

            if (destination is SendDestination.TonAccount && selectedToken.isTrc20) {
                SendDestination.TokenError(
                    addressBlockchain = Blockchain.TON,
                    selectedToken = selectedToken.token
                )
            } else {
                destination
            }
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, SendDestination.Empty)

    private var tonFee: SendFee.Ton? = null
    private var gaslessFee: SendFee.Gasless? = null
    private var batteryFee: SendFee.Battery? = null

    val feeOptions: List<SendFee>
        get() = listOfNotNull(
            batteryFee,
            tonFee,
            gaslessFee,
        )

    private val ratesTokenFlow = selectedTokenFlow.map { token ->
        ratesRepository.getRates(currency, token.address)
    }.state(viewModelScope)

    val uiInputAddressErrorFlow =
        destinationFlow.map { it is SendDestination.NotFound || it is SendDestination.Scam || it is SendDestination.TokenError }

    private val _uiInputAmountFlow = MutableEffectFlow<Coins>()
    val uiInputAmountFlow = _uiInputAmountFlow.asSharedFlow()

    val uiInputTokenFlow = userInputFlow.map { it.token }.filter { !isNft }.distinctUntilChanged()

    val uiInputNftFlow = userInputFlow.map { it.nft }.distinctUntilChanged().filterNotNull()

    val uiRequiredMemoFlow =
        destinationFlow.map { it as? SendDestination.TonAccount }.map { it?.memoRequired == true }

    val uiExistingTargetFlow =
        destinationFlow.map { it as? SendDestination.TonAccount }.map { it?.existing == true }

    val uiEncryptedCommentAvailableFlow = combine(
        uiRequiredMemoFlow,
        uiExistingTargetFlow,
    ) { requiredMemo, existingTarget ->
        existingTarget && !requiredMemo && (wallet.type == Wallet.Type.Default || wallet.type == Wallet.Type.Testnet || wallet.type == Wallet.Type.Lockup)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val uiInputEncryptedComment = combine(
        userInputFlow.map { it.encryptedComment }.distinctUntilChanged(),
        uiEncryptedCommentAvailableFlow,
    ) { encryptedComment, available ->
        encryptedComment && available
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val uiCommentAvailable = selectedTokenFlow.map { !it.isTrc20 }

    private val uiInputComment = userInputFlow.map { it.comment }

    private val uiInputAmountCurrency =
        userInputFlow.map { it.amountCurrency }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val inputAmountFlow = userInputFlow.map { it.amount }.distinctUntilChanged()

    val uiInputCommentErrorFlow = uiInputComment.map { comment ->
        if (wallet.isLedger && !comment.isNullOrEmpty() && !comment.isPrintableAscii()) {
            Localization.ledger_comment_error
        } else {
            null
        }
    }

    private val _uiEventFlow = MutableEffectFlow<SendEvent>()
    val uiEventFlow = _uiEventFlow.asSharedFlow()

    private val _uiInsufficientBalanceFlow = MutableEffectFlow<SendEvent.InsufficientBalance>()
    val uiInsufficientBalanceFlow = _uiInsufficientBalanceFlow.asSharedFlow()

    private val _uiFeeFlow = MutableEffectFlow<SendEvent.Fee>()
    val uiFeeFlow = _uiFeeFlow.asSharedFlow()

    val uiBalanceFlow = combine(
        selectedTokenFlow,
        inputAmountFlow,
        ratesTokenFlow,
        uiInputAmountCurrency,
    ) { token, amount, rates, amountCurrency ->
        val (balance, currencyCode) = if (amountCurrency) {
            Pair(token.fiat, currency.code)
        } else {
            Pair(token.balance.value, token.symbol)
        }

        val remaining = balance - amount

        val convertedCode = if (amountCurrency) token.symbol else currency.code
        val converted = if (amountCurrency) {
            rates.convertFromFiat(token.address, amount)
        } else {
            rates.convert(token.address, amount)
        }

        val remainingToken = if (!amountCurrency) {
            token.balance.value - amount
        } else {
            rates.convertFromFiat(token.address, token.fiat - amount)
        }

        val remainingFormat = CurrencyFormatter.format(
            currency = token.symbol,
            value = remainingToken,
            roundingMode = RoundingMode.DOWN,
            replaceSymbol = false
        )

        SendAmountState(
            remainingFormat = getString(Localization.remaining_balance, remainingFormat),
            converted = converted.stripTrailingZeros(),
            convertedFormat = CurrencyFormatter.format(
                currency = convertedCode,
                value = converted,
                roundingMode = RoundingMode.DOWN,
                replaceSymbol = false
            ),
            insufficientBalance = if (remaining.isZero) false else remaining.isNegative,
            currencyCode = if (amountCurrency) currencyCode else "",
            amountCurrency = amountCurrency,
            hiddenBalance = settingsRepository.hiddenBalances
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SendAmountState())

    val uiButtonEnabledFlow = combine(
        destinationFlow,
        uiBalanceFlow,
        inputAmountFlow,
        uiInputComment,
        uiInputCommentErrorFlow,
    ) { recipient, balance, amount, comment, commentError ->
        if (recipient !is SendDestination.TonAccount && recipient !is SendDestination.TronAccount) {
            false
        } else if (recipient is SendDestination.TonAccount && recipient.memoRequired && comment.isNullOrEmpty()) {
            false
        } else if (commentError != null) {
            false
        } else if (isNft || (!balance.insufficientBalance && amount.isPositive)) {
            true
        } else if (balance.insufficientBalance) {
            false
        } else {
            false
        }
    }

    private val amountTokenFlow = combine(
        selectedTokenFlow,
        inputAmountFlow,
        ratesTokenFlow,
        uiInputAmountCurrency,
    ) { token, amount, rates, amountCurrency ->
        if (!amountCurrency) {
            amount
        } else {
            rates.convertFromFiat(token.address, amount)
        }
    }

    private val transferAmountFlow = combine(
        amountTokenFlow,
        selectedTokenFlow,
        ratesTokenFlow,
    ) { amount, token, rates ->
        TonTransaction.Amount(
            value = amount,
            converted = rates.convert(token.address, amount),
            format = CurrencyFormatter.format(
                token.symbol, amount, RoundingMode.UP, false
            ),
            convertedFormat = CurrencyFormatter.format(
                currency.code,
                rates.convert(token.address, amount),
                RoundingMode.UP,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TonTransaction.Amount())

    private val preferredFeeMethodFlow =
        MutableStateFlow(settingsRepository.getPreferredFeeMethod(wallet.id))

    private val _tronResourcesFlow = MutableStateFlow<TronResourcesEntity?>(null)
    private val tronResourcesFlow =
        _tronResourcesFlow.shareIn(viewModelScope, SharingStarted.Eagerly, 1).filterNotNull()

    private val _tronTransferFlow = MutableStateFlow<TronTransfer?>(null)
    private val tronTransferFlow = _tronTransferFlow.asStateFlow()

    private val _tonTransferFlow = MutableStateFlow<TransferEntity?>(null)
    private val tonTransferFlow = _tonTransferFlow.asStateFlow()

    private val _feeFlow = MutableStateFlow<SendFee?>(null)
    val feeFlow = _feeFlow.asStateFlow()

    // Using only for UI
    private val uiTransferAmountFlow = combine(
        userInputFlow.map { it.max },
        amountTokenFlow,
        selectedTokenFlow,
        ratesTokenFlow,
        feeFlow,
    ) { max, amount, token, rates, fee ->
        var value = when {
            fee is SendFee.Gasless && max -> amount - fee.amount.value
            token.isTon && fee is SendFee.Ton && amount >= token.balance.value -> amount - fee.amount.value
            else -> amount
        }
        if (value.isNegative) {
            value = Coins.ZERO
        }
        val fiat = rates.convert(token.address, value)

        SendTransaction.Amount(
            value = value,
            converted = fiat,
            format = CurrencyFormatter.formatFull(token.symbol, value, token.decimals),
            convertedFormat = CurrencyFormatter.formatFiat(
                currency.code,
                fiat
            ),
        )
    }

    val uiTransactionFlow = combine(
        destinationFlow,
        selectedTokenFlow,
        uiTransferAmountFlow,
        tonTransferFlow,
        tronTransferFlow,
    ) { destination, token, amount, tonTransfer, tronTransfer ->
        val max = tonTransfer?.max ?: false
        SendTransaction(
            fromWallet = wallet,
            destination = destination,
            token = token.balance,
            comment = tonTransfer?.comment,
            encryptedComment = tonTransfer?.commentEncrypted == true,
            amount = amount,
            max = max
        )
    }

    val userInputMaxFlow = combine(
        userInputFlow,
        selectedTokenFlow,
        uiInputAmountCurrency,
    ) { input, selected, amountCurrency ->
        val tokenAddress = selected.address
        val amount = if (amountCurrency) {
            val rates = ratesRepository.getRates(currency, tokenAddress)
            rates.convertFromFiat(tokenAddress, input.amount)
        } else {
            input.amount
        }
        amount >= selected.balance.value
    }.flowOn(Dispatchers.IO)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _tokensFlow.value = tokenRepository.get(currency, wallet.accountId, wallet.testnet)
        }

        if (isNft) {
            loadNft()
        }
    }

    fun initializeTokenAndAmount(
        tokenAddress: String?, amount: Coins?, type: Type
    ) {
        tokensFlow.take(1).filter {
            it.isNotEmpty()
        }.filterList {
            if (tokenAddress != null) {
                it.address.equalsAddress(tokenAddress)
            } else {
                it.address.equalsAddress(TokenEntity.TON.address)
            }
        }.map { it.firstOrNull()?.balance?.token }.map { token ->
            token ?: tokenAddress?.let { tokenRepository.getToken(tokenAddress, wallet.testnet) }
            ?: TokenEntity.TON
        }.flowOn(Dispatchers.IO).onEach { token ->
            userInputToken(token)
            applyAmount(token, amount)
        }.launchIn(viewModelScope)

        _userInputFlow.update {
            it.copy(type = type)
        }
    }

    suspend fun isNeedMemoAddress(targetAddress: String): Boolean = withContext(Dispatchers.IO) {
        api.resolveAccount(targetAddress, wallet.testnet)?.memoRequired == true
    }

    private fun applyAmount(token: TokenEntity, amount: Coins?) {
        amount?.let {
            _uiInputAmountFlow.tryEmit(it)
        }
    }

    private suspend fun getDestinationAccount(userInput: String) = withContext(Dispatchers.IO) {
        val tonAddressTags = TonAddressTags.of(userInput)
        if (tonAddressTags.userFriendly && tonAddressTags.isTestnet != wallet.testnet) {
            return@withContext SendDestination.NotFound
        }

        val accountDeferred = async { api.resolveAccount(userInput, wallet.testnet) }
        val publicKeyDeferred = async { api.safeGetPublicKey(userInput, wallet.testnet) }

        val account = accountDeferred.await() ?: return@withContext SendDestination.NotFound
        val publicKey = publicKeyDeferred.await()

        if (account.isScam == true) {
            return@withContext SendDestination.Scam
        }

        SendDestination.TonAccount(
            userInput = userInput,
            isUserInputAddress = userInput.isValidTonAddress(),
            publicKey = publicKey,
            account = account,
            testnet = wallet.testnet,
            tonAddressTags = tonAddressTags
        )
    }

    private fun showIfInsufficientBalance(onContinue: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main) {
            val fee = _feeFlow.value!!
            val transfer = tonTransferFlow.value!!

            if (fee is SendFee.Ton && fee.error is InsufficientBalanceError) {
                showInsufficientBalance(
                    type = InsufficientBalanceType.InsufficientTONBalance,
                    amount = Amount(value = fee.error.totalAmount),
                    balance = Amount(value = fee.error.accountBalance),
                )
            } else if (fee is SendFee.Gasless && !transfer.max && fee.amount.value + transfer.amount > transfer.token.value) {
                showInsufficientBalance(
                    type = InsufficientBalanceType.InsufficientGaslessBalance,
                    amount = Amount(value = fee.amount.value + transfer.amount, token = transfer.token.token),
                    balance = Amount(value = transfer.token.value, token = transfer.token.token),
                    gaslessFee = fee.amount.value
                )
            } else if (!transfer.isTon && !transfer.isNft && transfer.amount > transfer.token.value) {
                showInsufficientBalance(
                    type = InsufficientBalanceType.InsufficientJettonBalance,
                    amount = Amount(value = transfer.amount, token = transfer.token.token),
                    balance = Amount(value = transfer.token.value, token = transfer.token.token),
                )
            } else {
                onContinue()
            }
        }
    }

    private suspend fun getBatteryCharges(): Int = withContext(Dispatchers.IO) {
        accountRepository.requestTonProofToken(wallet)?.let {
            batteryRepository.getCharges(it, wallet.publicKey, wallet.testnet, true)
        } ?: 0
    }

    private suspend fun getBatteryBalance(): BatteryBalanceEntity = withContext(Dispatchers.IO) {
        accountRepository.requestTonProofToken(wallet)?.let {
            batteryRepository.getBalance(it, wallet.publicKey, wallet.testnet, true)
        } ?: BatteryBalanceEntity.Empty
    }

    private suspend fun showInsufficientBalance(
        balance: Amount,
        amount: Amount,
        gaslessFee: Coins = Coins.ZERO,
        type: InsufficientBalanceType,
    ) {
        val txType = when {
            nftAddress.isNotBlank() -> BatteryTransaction.NFT
            !type.isTON() -> BatteryTransaction.JETTON
            else -> BatteryTransaction.UNKNOWN
        }
        val batteryBalance = getBatteryBalance()
        val batteryEnabled = !isBatteryDisabled && settingsRepository.batteryIsEnabledTx(
            wallet.accountId, txType
        )

        var withRechargeBattery = batteryEnabled && batteryBalance.balance.value == BigDecimal.ZERO
        if (withRechargeBattery && type.isEmptyBalance()) {
            withRechargeBattery = false
        }
        if (gaslessFee.isZero && type == InsufficientBalanceType.InsufficientJettonBalance) {
            withRechargeBattery = false
        }

        _uiInsufficientBalanceFlow.tryEmit(
            SendEvent.InsufficientBalance(
                balance = balance,
                required = amount,
                withRechargeBattery = withRechargeBattery,
                singleWallet = 1 >= getWalletCount(),
                type = type
            )
        )
    }

    private suspend fun getWalletCount(): Int = withContext(Dispatchers.IO) {
        accountRepository.getWallets().size
    }

    private suspend fun getTokenAmount(): Coins = withContext(Dispatchers.IO) {
        val amount = userInputFlow.value.amount
        val token = selectedTokenFlow.value
        if (!userInputFlow.value.amountCurrency) {
            amount
        } else {
            val rates = ratesRepository.getRates(currency, token.address)
            rates.convertFromFiat(token.address, amount)
        }
    }

    private suspend fun getTONBalance(): Coins = withContext(Dispatchers.IO) {
        if (selectedTokenFlow.value.isTon) {
            selectedTokenFlow.value.balance.value
        } else {
            tokenRepository.getTON(currency, wallet.accountId, wallet.testnet)?.balance?.value
                ?: Coins.ZERO
        }
    }

    private suspend fun checkTonFee(transfer: TransferEntity) {
        val fee = calculateFee(transfer)
        _feeFlow.tryEmit(fee)
        eventFee(transfer, fee)?.let(::showPreview)
    }

    private suspend fun checkTronFee(transfer: TronTransfer) {
        val batteryCharges = getBatteryCharges()
        val estimation = api.tron.estimateBatteryCharges(transfer)
        _tronResourcesFlow.value = estimation.resources

        val fee = SendFee.Battery(
            charges = estimation.charges,
            chargesBalance = batteryCharges,
            // not used in this case
            excessesAddress = AddrStd(wallet.address),
            extra = 0L
        )
        _feeFlow.tryEmit(fee)
        _uiFeeFlow.tryEmit(
            SendEvent.Fee(
                fee = fee,
                failed = false
            )
        )

        if (estimation.charges > batteryCharges) {
            _uiInsufficientBalanceFlow.tryEmit(
                SendEvent.InsufficientBalance(
                    balance = Amount(Coins.of(batteryCharges.toBigDecimal())),
                    required = Amount(Coins.of(estimation.charges.toBigDecimal())),
                    withRechargeBattery = true,
                    singleWallet = 1 >= getWalletCount(),
                    type = InsufficientBalanceType.InsufficientBatteryChargesForFee
                )
            )
            throw SendException.InsufficientBalance()
        }
    }

    private suspend fun buildTronTransfer(): TronTransfer {
        val token = selectedTokenFlow.value
        val transferAmount = transferAmountFlow.value
        val destination = destinationFlow.value as? SendDestination.TronAccount
            ?: throw IllegalStateException("Destination is not TronAccount")
        val tronAddress = accountRepository.getTronAddress(wallet.id)
            ?: throw IllegalStateException("Tron address not found")
        return TronTransfer(
            from = tronAddress,
            to = destination.address,
            amount = transferAmount.value.toBigInteger(),
            contractAddress = token.address
        )
    }

    private suspend fun buildTonTransfer(): TransferEntity {
        val amount = transferAmountFlow.value
        if (amount.isEmpty && !isNft) {
            throw IllegalStateException("Amount is empty")
        }

        val userInput = userInputFlow.value
        val comment = userInput.comment?.ifBlank { null }
        val destination = destinationFlow.filter { it is SendDestination.TonAccount }
            .singleValue(3.seconds) as? SendDestination.TonAccount
            ?: throw IllegalStateException("Destination is not TonAccount")
        val token = selectedTokenFlow.value
        val customPayload = getTokenCustomPayload(token.balance.token)
        val sendMetadata = getSendParams(wallet)
        val builder = TransferEntity.Builder(wallet)
        if (!customPayload.isEmpty) {
            builder.setTokenPayload(customPayload)
        }
        builder.setToken(token.balance)
        builder.setDestination(destination.address, destination.publicKey)
        builder.setSeqno(sendMetadata.seqno)
        builder.setQueryId(queryId)
        comment?.let {
            builder.setComment(it, userInput.encryptedComment)
        }
        builder.setValidUntil(sendMetadata.validUntil)
        if (isNft) {
            builder.setNftAddress(nftAddress)
            builder.setBounceable(true)
            builder.setAmount(Coins.ZERO)
            builder.setMax(false)
        } else if (!token.isTon) {
            builder.setMax(amount.value == token.balance.value)
            builder.setBounceable(true)
            builder.setAmount(amount.value)
        } else {
            builder.setMax(amount.value == getTONBalance())
            builder.setAmount(amount.value)
            builder.setBounceable(destination.isBounce)
        }
        return builder.build()
    }

    private suspend fun nextTon() {
        val transfer = buildTonTransfer()
        _tonTransferFlow.value = transfer
        checkTonFee(transfer)
    }

    private suspend fun nextTron() {
        val transfer = buildTronTransfer()
        _tronTransferFlow.value = transfer
        checkTronFee(transfer)
        delay(100)
        _uiEventFlow.tryEmit(SendEvent.Confirm)
    }

    fun next() {
        Log.d("SendViewModelLog", "next() called with: ")
        _tonTransferFlow.value = null
        _tronTransferFlow.value = null
        viewModelScope.launch(Dispatchers.IO) {
            Log.d("SendViewModelLog", "next: start")
            try {
                if (selectedTokenFlow.value.isTrc20) {
                    nextTron()
                } else {
                    nextTon()
                }
                Log.d("SendViewModelLog", "next: success")
            } catch (e: Throwable) {
                Log.e("SendViewModelLog", "next error", e)
                delay(100)
                _uiEventFlow.tryEmit(SendEvent.Failed(e))
            }
        }
    }

    private fun showPreview(fee: SendEvent.Fee) {
        showIfInsufficientBalance {
            _uiEventFlow.tryEmit(SendEvent.Confirm)
        }
        _uiFeeFlow.tryEmit(fee)
    }

    private fun loadNft() {
        viewModelScope.launch(Dispatchers.IO) {
            val nft = collectiblesRepository.getNft(
                accountId = wallet.accountId, testnet = wallet.testnet, address = nftAddress
            ) ?: return@launch
            val pref = settingsRepository.getTokenPrefs(wallet.id, nftAddress)
            userInputNft(nft.with(pref))
        }
    }

    private fun shouldAttemptWithRelayer(transfer: TransferEntity): Boolean {
        if ((transfer.isTon && !transfer.isNft) || transfer.wallet.isExternal) {
            return false
        }

        val transactionType = if (transfer.isNft) {
            BatteryTransaction.NFT
        } else {
            BatteryTransaction.JETTON
        }

        return settingsRepository.batteryIsEnabledTx(transfer.wallet.accountId, transactionType)
    }

    private suspend fun calculateFee(
        transfer: TransferEntity,
    ): SendFee = withContext(Dispatchers.IO) {
        val wallet = transfer.wallet
        val withRelayer = shouldAttemptWithRelayer(transfer)
        val tonProofToken = accountRepository.requestTonProofToken(wallet)
        val batteryConfig = batteryRepository.getConfig(wallet.testnet)
        val tokenAddress = transfer.token.token.address
        val excessesAddress = batteryConfig.excessesAddress
        val isGaslessToken = !transfer.token.isTon && batteryConfig.rechargeMethods.any {
            it.supportGasless && it.jettonMaster == tokenAddress
        }

        val isSupportsGasless =
            wallet.isSupportedFeature(WalletFeature.GASLESS) && tonProofToken != null && excessesAddress != null && isGaslessToken

        val tonDeferred = async { calculateFeeDefault(transfer) }
        val gaslessDeferred = async {
            if (isSupportsGasless) {
                calculateFeeGasless(
                    transfer,
                    excessesAddress,
                    tonProofToken,
                    tokenAddress,
                )
            } else null
        }
        val batteryDeferred = async {
            if (withRelayer && tonProofToken != null && excessesAddress != null) {
                calculateFeeBattery(transfer, excessesAddress, tonProofToken)
            } else null
        }

        val tonFeeResult = tonDeferred.await()
        gaslessFee = gaslessDeferred.await()
        batteryFee = batteryDeferred.await()

        if (tonFeeResult.error is InsufficientBalanceError) {
            tonFee = null
        } else {
            tonFee = tonFeeResult
        }

        // preferred fee method logic
        val preferredFeeMethod = settingsRepository.getPreferredFeeMethod(wallet.id)
        if (preferredFeeMethod == PreferredFeeMethod.BATTERY && batteryFee != null) {
            return@withContext batteryFee!!
        }
        if (preferredFeeMethod == PreferredFeeMethod.GASLESS && gaslessFee != null) {
            return@withContext gaslessFee!!
        }
        if (preferredFeeMethod == PreferredFeeMethod.TON && tonFee != null) {
            return@withContext tonFee!!
        }

        // unspecified preferred fee method logic
        if (batteryFee != null) {
            return@withContext batteryFee!!
        } else if (gaslessFee != null && tonFee == null) {
            return@withContext gaslessFee!!
        }

        return@withContext tonFeeResult
    }

    private suspend fun calculateFeeBattery(
        transfer: TransferEntity,
        excessesAddress: AddrStd,
        tonProofToken: String,
    ): SendFee.Battery? {
        if (api.config.batterySendDisabled) {
            return null
        }

        val message = transfer.signForEstimation(
            internalMessage = true,
            excessesAddress = excessesAddress,
            jettonTransferAmount = TransferEntity.BASE_FORWARD_AMOUNT,
        )

        try {
            val (consequences, withBattery) = batteryRepository.emulate(
                tonProofToken = tonProofToken,
                publicKey = wallet.publicKey,
                testnet = wallet.testnet,
                boc = message,
                safeModeEnabled = settingsRepository.isSafeModeEnabled(api)
            ) ?: return null

            if (!withBattery) {
                return null
            }

            val extra = consequences.event.extra

            val chargesBalance = getBatteryCharges()
            val batteryConfig = batteryRepository.getConfig(wallet.testnet)
            val charges = BatteryMapper.calculateChargesAmount(
                Coins.of(abs(extra)).value,
                batteryConfig.chargeCost
            )

            if (charges > chargesBalance) {
                return null
            }

            return SendFee.Battery(
                charges = charges,
                chargesBalance = chargesBalance,
                extra = extra,
                excessesAddress = excessesAddress,
            )
        } catch (_: Exception) {
            return null
        }
    }

    private suspend fun calculateFeeGasless(
        transfer: TransferEntity,
        excessesAddress: AddrStd,
        tonProofToken: String,
        tokenAddress: String,
    ): SendFee.Gasless? {
        try {
            if (api.config.flags.disableGasless) {
                Log.d("SendViewModel", "Gasless fee calculation disabled by config")
                return null
            }

            val message = transfer.signForEstimation(
                internalMessage = true,
                jettonAmount = if (transfer.max) {
                    Coins.of(1, transfer.token.decimals)
                } else {
                    null
                },
                additionalGifts = listOf(
                    transfer.gaslessInternalGift(
                        jettonAmount = Coins.of(1, transfer.token.decimals),
                        batteryAddress = excessesAddress
                    )
                ),
                excessesAddress = excessesAddress,
                jettonTransferAmount = TransferEntity.BASE_FORWARD_AMOUNT
            )

            val commission = api.estimateGaslessCost(
                tonProofToken = tonProofToken,
                jettonMaster = tokenAddress,
                cell = message,
                testnet = wallet.testnet,
            ) ?: throw IllegalStateException("Can't estimate gasless cost")

            val gaslessFee = Coins.ofNano(commission, transfer.token.decimals)

            if (transfer.max && gaslessFee > transfer.token.value) {
                throw IllegalStateException("Insufficient gasless balance")
            }
            if (!transfer.max && gaslessFee + transfer.amount > transfer.token.value) {
                throw IllegalStateException("Insufficient gasless balance")
            }

            val fee = Fee(
                value = gaslessFee,
                isRefund = false,
                token = transfer.token.token,
            )

            val rates = ratesRepository.getRates(currency, fee.token.address)
            val converted = rates.convert(fee.token.address, fee.value)

            return SendFee.Gasless(
                amount = fee,
                fiatAmount = converted,
                fiatCurrency = currency,
                excessesAddress = excessesAddress
            )
        } catch (e: Exception) {
            Log.d("SendViewModel", "Gasless fee calculation failed: ${e.message}")
            return null
        }
    }

    private suspend fun getJettonTransferAmount(
        transfer: TransferEntity,
    ): Coins {
        try {
            if (transfer.token.isRequestMinting || transfer.token.customPayloadApiUri != null) {
                return TransferEntity.POINT_ONE_TON
            }

            val message = transfer.signForEstimation(
                internalMessage = false, jettonTransferAmount = TransferEntity.ONE_TON
            )
            // Emulate with higher balance to calculate fair amount to send
            val emulated = api.emulate(
                cell = message,
                testnet = transfer.wallet.testnet,
                address = transfer.wallet.accountId,
                balance = (Coins.ONE + Coins.ONE).toLong(),
                safeModeEnabled = settingsRepository.isSafeModeEnabled(api)
            )
            val fee = Fee(emulated?.event?.extra ?: 0L)

            return if (fee.isRefund) {
                TransferEntity.BASE_FORWARD_AMOUNT
            } else {
                fee.value + TransferEntity.BASE_FORWARD_AMOUNT
            }
        } catch (_: Throwable) {
            return TransferEntity.POINT_ONE_TON
        }
    }

    private suspend fun calculateFeeDefault(
        transfer: TransferEntity,
    ): SendFee.Ton {
        val jettonTransferAmount = getJettonTransferAmount(transfer)
        val emulated = emulationUseCase(
            message = transfer.getEmulationBody(jettonTransferAmount),
            params = true,
            checkTonBalance = !transfer.isTon || !transfer.max,
        )

        val fee = Fee( emulated.extra.value,  emulated.extra.isRefund)

        return SendFee.Ton(
            amount = fee,
            fiatAmount = emulated.extra.fiat,
            fiatCurrency = currency,
            error = emulated.error
        )
    }

    private suspend fun eventFee(
        transfer: TransferEntity,
        fee: SendFee,
    ): SendEvent.Fee? {
        return try {
            val showToggle = feeOptions.size > 1

            SendEvent.Fee(
                fee = fee,
                format = if (fee is SendFee.TokenFee) {
                    CurrencyFormatter.format(
                        fee.amount.token.symbol,
                        fee.amount.value
                    )
                } else "",
                convertedFormat = if (fee is SendFee.TokenFee) {
                    val rates = ratesRepository.getRates(currency, fee.amount.token.address)
                    val converted = rates.convert(fee.amount.token.address, fee.amount.value)
                    CurrencyFormatter.format(
                        currency.code, converted
                    )
                } else "",
                showToggle = showToggle,
                insufficientFunds = false,
                failed = false
            )
        } catch (e: Throwable) {
            null
        }
    }

    fun userInputBin(bin: Cell?) {
        _userInputFlow.update {
            it.copy(bin = bin)
        }
    }

    fun userInputEncryptedComment(encrypted: Boolean) {
        _userInputFlow.update {
            it.copy(encryptedComment = encrypted)
        }
    }

    fun userInputAmount(amount: Coins) {
        _userInputFlow.update {
            it.copy(amount = amount)
        }
    }

    fun userInputToken(token: TokenEntity) {
        _userInputFlow.update {
            it.copy(token = token)
        }
    }

    private fun userInputNft(nft: NftEntity) {
        _userInputFlow.update {
            it.copy(nft = nft)
        }
    }

    private fun userInputTokenByAddress(tokenAddress: String) {
        tokensFlow.take(1).filter {
            it.isNotEmpty()
        }.filterList {
            it.address.equalsAddress(tokenAddress)
        }.map { it.firstOrNull()?.balance?.token }.map { token ->
            token ?: tokenRepository.getToken(tokenAddress, wallet.testnet) ?: TokenEntity.TON
        }.flowOn(Dispatchers.IO).onEach { token ->
            userInputToken(token)
        }.launchIn(viewModelScope)
    }

    fun userInputAddress(address: String) {
        _userInputFlow.update {
            it.copy(address = address)
        }
    }

    fun userInputComment(comment: String?) {
        _userInputFlow.update {
            it.copy(comment = comment?.trim())
        }
    }

    fun swap() {
        val balance = uiBalanceFlow.value.copy()
        val amountCurrency = _userInputFlow.updateAndGet {
            it.copy(amountCurrency = !it.amountCurrency)
        }.amountCurrency

        if (amountCurrency != balance.amountCurrency) {
            _uiInputAmountFlow.tryEmit(balance.converted)
        }
    }

    fun setMax() {
        collectFlow(uiInputAmountCurrency.take(1)) { amountCurrency ->
            val token = selectedTokenFlow.value
            val coins = if (amountCurrency) {
                token.fiat
            } else {
                token.balance.value
            }
            _uiInputAmountFlow.tryEmit(coins)
        }
    }

    fun setFeeMethod(fee: SendFee) {
        val preferredMethod = when (fee) {
            is SendFee.Ton -> PreferredFeeMethod.TON
            is SendFee.Battery -> PreferredFeeMethod.BATTERY
            is SendFee.Gasless -> PreferredFeeMethod.GASLESS
        }
        settingsRepository.setPreferredFeeMethod(wallet.id, preferredMethod)
        preferredFeeMethodFlow.value = preferredMethod

        val tonTx = _tonTransferFlow.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _feeFlow.tryEmit(fee)
            eventFee(tonTx, fee)?.let {
                _uiFeeFlow.tryEmit(it)
            }
        }
    }

    private suspend fun getSendParams(
        wallet: WalletEntity,
    ): SendMetadataEntity = withContext(Dispatchers.IO) {
        val seqnoDeferred = async { accountRepository.getSeqno(wallet) }
        val validUntilDeferred = async { accountRepository.getValidUntil(wallet.testnet) }

        val seqno = seqnoDeferred.await()
        val validUntil = validUntilDeferred.await()

        SendMetadataEntity(
            seqno = seqno,
            validUntil = validUntil,
        )
    }

    private suspend fun signTon() {
        val transfer = tonTransferFlow.value ?: throw IllegalStateException("Ton transfer is null")
        val fee = feeFlow.value ?: throw IllegalStateException("Fee is null")

        _uiEventFlow.tryEmit(SendEvent.Loading)

        if (fee is SendFee.Battery) {
            val batteryCharges = getBatteryCharges()
            val batteryConfig = batteryRepository.getConfig(wallet.testnet)
            val txCharges = BatteryMapper.calculateChargesAmount(
                Coins.of(abs(fee.extra)).value,
                batteryConfig.chargeCost
            )
            if (txCharges > batteryCharges) {
                _uiInsufficientBalanceFlow.tryEmit(
                    SendEvent.InsufficientBalance(
                        balance = Amount(Coins.of(batteryCharges.toBigDecimal())),
                        required = Amount(Coins.of(txCharges.toBigDecimal())),
                        withRechargeBattery = true,
                        singleWallet = 1 >= getWalletCount(),
                        type = InsufficientBalanceType.InsufficientBatteryChargesForFee
                    )
                )
                throw SendException.InsufficientBalance()
            }
        }

        lastTransferEntity = transfer

        val excessesAddress = if (fee is SendFee.RelayerFee) {
            fee.excessesAddress
        } else {
            null
        }

        val additionalGifts = if (fee is SendFee.Gasless) {
            listOf(
                transfer.gaslessInternalGift(
                    jettonAmount = fee.amount.value,
                    batteryAddress = fee.excessesAddress
                )
            )
        } else {
            emptyList()
        }

        val privateKey = if (transfer.commentEncrypted) {
            accountRepository.getPrivateKey(wallet.id)
        } else {
            null
        }

        val internalMessage = excessesAddress != null

        val jettonTransferAmount = when (fee) {
            is SendFee.Gasless -> TransferEntity.BASE_FORWARD_AMOUNT
            is SendFee.Extra -> {
                val extra = Coins.of(fee.extra)
                when {
                    transfer.token.isRequestMinting || transfer.token.customPayloadApiUri != null -> TransferEntity.POINT_ONE_TON
                    extra.isPositive -> TransferEntity.BASE_FORWARD_AMOUNT
                    extra.isZero -> TransferEntity.POINT_ONE_TON
                    else -> Coins.of(abs(fee.extra)) + TransferEntity.BASE_FORWARD_AMOUNT
                }
            }
            is SendFee.Ton -> when {
                transfer.token.isRequestMinting || transfer.token.customPayloadApiUri != null -> TransferEntity.POINT_ONE_TON
                fee.amount.isRefund -> TransferEntity.BASE_FORWARD_AMOUNT
                else -> fee.amount.value + TransferEntity.BASE_FORWARD_AMOUNT
            }
            else -> TransferEntity.POINT_ONE_TON
        }

        val boc = signUseCase(
            context = context,
            wallet = wallet,
            unsignedBody = transfer.getUnsignedBody(
                privateKey = privateKey,
                internalMessage = internalMessage,
                additionalGifts = additionalGifts,
                excessesAddress = excessesAddress,
                jettonAmount = if (transfer.max && fee is SendFee.Gasless) {
                    transfer.amount - fee.amount.value
                } else {
                    null
                },
                jettonTransferAmount = jettonTransferAmount,
            ),
            seqNo = transfer.seqno,
            ledgerTransaction = transfer.getLedgerTransaction(jettonTransferAmount)
        )
        _uiEventFlow.tryEmit(SendEvent.Loading)
        Triple(boc, transfer.wallet, internalMessage)

        send(boc, wallet, internalMessage)
        analytics.simpleTrackEvent("send_success")
    }

    fun sign() {
        _uiEventFlow.tryEmit(SendEvent.Loading)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (selectedTokenFlow.value.isTrc20) {
                    signTron()
                } else {
                    signTon()
                }
                _uiEventFlow.tryEmit(SendEvent.Success)
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    _uiEventFlow.tryEmit(SendEvent.Canceled)
                } else {
                    FirebaseCrashlytics.getInstance().recordException(e)
                    _uiEventFlow.tryEmit(SendEvent.Failed(e))
                }
            }
        }
    }

    private suspend fun signTron() {
        val transfer =
            tronTransferFlow.value ?: throw IllegalStateException("Tron transfer is null")
        val transaction = api.tron.buildSmartContractTransaction(transfer).extendExpiration()
        val resources =
            _tronResourcesFlow.value ?: throw IllegalStateException("Tron resources is null")

        val signedTransaction = signUseCase(
            context = context,
            wallet = wallet,
            transaction = transaction,
        )

        val tonProofToken = accountRepository.requestTonProofToken(wallet)
            ?: throw IllegalStateException("TonProofToken is null")

        api.tron.sendTransaction(
            transaction = signedTransaction,
            resources = resources,
            tronAddress = transfer.from,
            tonProofToken = tonProofToken,
        )
        analytics.simpleTrackEvent("send_success")
        getBatteryBalance()
    }

    private suspend fun send(
        message: Cell,
        wallet: WalletEntity,
        withBattery: Boolean,
    ) {
        val state = transactionManager.send(
            wallet = wallet,
            boc = message,
            withBattery = withBattery,
            source = "",
            confirmationTime = 0.0,
        )
        if (state != SendBlockchainState.SUCCESS) {
            throw SendBlockchainException.fromState(state)
        }
    }

    private fun getTokenCustomPayload(
        token: TokenEntity
    ): TokenEntity.TransferPayload {
        if (token.isTon) {
            return TokenEntity.TransferPayload.empty("TON")
        } else if (!token.isRequestMinting) {
            return TokenEntity.TransferPayload.empty(token.address)
        }
        if (tokenCustomPayload != null && tokenCustomPayload!!.tokenAddress.equalsAddress(token.address)) {
            return tokenCustomPayload!!
        }

        if (tokenCustomPayload == null) {
            tokenCustomPayload =
                api.getJettonCustomPayload(wallet.accountId, wallet.testnet, token.address)
        }
        return tokenCustomPayload ?: TokenEntity.TransferPayload.empty(token.address)
    }
}
