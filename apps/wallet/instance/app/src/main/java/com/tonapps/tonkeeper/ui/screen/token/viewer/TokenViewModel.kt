package com.tonapps.tonkeeper.ui.screen.token.viewer

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.tonapps.blockchain.ton.contract.BaseWalletContract
import com.tonapps.blockchain.ton.contract.WalletVersion
import com.tonapps.blockchain.ton.extensions.toAccountId
import com.tonapps.icu.Coins
import com.tonapps.icu.CurrencyFormatter
import com.tonapps.icu.Formatter
import com.tonapps.tonkeeper.core.history.ActionOptions
import com.tonapps.tonkeeper.core.history.HistoryHelper
import com.tonapps.tonkeeper.core.history.list.item.HistoryItem
import com.tonapps.tonkeeper.extensions.isSafeModeEnabled
import com.tonapps.tonkeeper.manager.tx.TransactionManager
import com.tonapps.tonkeeper.ui.base.BaseWalletVM
import com.tonapps.tonkeeper.ui.screen.token.viewer.list.Item
import com.tonapps.uikit.list.ListCell
import com.tonapps.wallet.api.API
import com.tonapps.wallet.api.entity.value.Blockchain
import com.tonapps.wallet.api.entity.ChartEntity
import com.tonapps.wallet.api.entity.EthenaEntity
import com.tonapps.wallet.api.entity.TokenEntity
import com.tonapps.wallet.data.account.AccountRepository
import com.tonapps.wallet.data.account.Wallet
import com.tonapps.wallet.data.account.entities.WalletEntity
import com.tonapps.wallet.data.battery.BatteryRepository
import com.tonapps.wallet.data.core.currency.WalletCurrency
import com.tonapps.wallet.data.events.EventsRepository
import com.tonapps.wallet.data.purchase.PurchaseRepository
import com.tonapps.wallet.data.rates.RatesRepository
import com.tonapps.wallet.data.settings.ChartPeriod
import com.tonapps.wallet.data.settings.SettingsRepository
import com.tonapps.wallet.data.token.TokenRepository
import com.tonapps.wallet.data.token.entities.AccountTokenEntity
import com.tonapps.wallet.localization.Localization
import io.tonapi.models.AccountEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// TODO Refactor this class
class TokenViewModel(
    app: Application,
    private val wallet: WalletEntity,
    private val tokenAddress: String,
    private val rawUsde: Boolean,
    private val tokenRepository: TokenRepository,
    private val settingsRepository: SettingsRepository,
    private val accountRepository: AccountRepository,
    private val api: API,
    private val eventsRepository: EventsRepository,
    private val historyHelper: HistoryHelper,
    private val batteryRepository: BatteryRepository,
    private val purchaseRepository: PurchaseRepository,
    private val ratesRepository: RatesRepository,
    private val transactionManager: TransactionManager,
) : BaseWalletVM(app) {

    val burnAddress: String by lazy {
        api.getBurnAddress()
    }

    val installId: String
        get() = settingsRepository.installId

    val tronUsdtEnabled: Boolean
        get() = settingsRepository.getTronUsdtEnabled(wallet.id)

    var tronAddress: String? = null
        private set

    val usdeDisabled: Boolean
        get() = api.config.flags.disableUsde

    private val _tokensFlow = MutableStateFlow<List<AccountTokenEntity>?>(null)
    val tokensFlow = _tokensFlow.asStateFlow().filterNotNull()

    val tokenFlow =
        tokensFlow.map { it.firstOrNull { token -> token.address == tokenAddress } }.filterNotNull()

    private val _uiItemsFlow = MutableStateFlow<List<Item>?>(null)
    val uiItemsFlow = _uiItemsFlow.asStateFlow().filterNotNull()

    private val _uiHistoryFlow =
        MutableStateFlow<MutableList<HistoryItem>>(mutableListOf(HistoryItem.Loader(0, 0)))
    val uiHistoryFlow = _uiHistoryFlow.asStateFlow().filterNotNull()

    private val _chartFlow = MutableStateFlow<List<ChartEntity>?>(null)
    private val chartFlow = _chartFlow.asStateFlow().filterNotNull()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            getData()
        }
        transactionManager.eventsFlow(wallet).collectFlow {
            getData(true)
        }

        combine(
            tokenFlow, tokensFlow, chartFlow, settingsRepository.walletPrefsChangedFlow
        ) { token, list, chart, _ ->
            buildItems(token, list, chart, tokenRepository.getEthena(wallet.accountId))
        }.launchIn(viewModelScope)
    }

    private suspend fun getData(refresh: Boolean = false) {
        tronAddress = accountRepository.getTronAddress(wallet.id)

        val list = tokenRepository.get(
            settingsRepository.currency, wallet.accountId, wallet.testnet, refresh = refresh
        ) ?: return
        val token = list.firstOrNull { it.address == tokenAddress } ?: return

        val ethena = if (!rawUsde && token.isUSDe && !usdeDisabled) {
            tokenRepository.getEthena(wallet.accountId, true)
        } else if (token.isTsUSDe && !usdeDisabled) {
            tokenRepository.getEthena(wallet.accountId)
        } else {
            null
        }

        _tokensFlow.value = list
        buildItems(token, list, emptyList(), ethena)
        load(token)
    }

    fun setChartPeriod(period: ChartPeriod) {
        settingsRepository.chartPeriod = period
        tokenFlow.take(1).onEach {
            loadChartPeriod(it, period)
        }.launchIn(viewModelScope)
    }

    private suspend fun loadChartPeriod(token: AccountTokenEntity, period: ChartPeriod) {
        _chartFlow.value = emptyList()

        when (period) {
            ChartPeriod.hour -> loadHourChart(token)
            ChartPeriod.day -> loadDayChart(token)
            ChartPeriod.week -> loadWeekChart(token)
            ChartPeriod.month -> loadMonthChart(token)
            ChartPeriod.halfYear -> load6MonthChart(token)
            ChartPeriod.year -> loadYearChart(token)
        }
    }

    private suspend fun load(token: AccountTokenEntity) = withContext(Dispatchers.IO) {
        /*if (token.verified) {
            loadHistory(token)
            loadChartPeriod(token, settingsRepository.chartPeriod)
        } else {
            _chartFlow.value = emptyList()
            loadHistory(token)
        }*/
        loadHistory(token)
        loadChartPeriod(token, settingsRepository.chartPeriod)
    }

    fun loadMore() {
        if (isLoading()) {
            return
        }

        tokenFlow.take(1).collectFlow { token ->
            val lastLt = lastLt() ?: return@collectFlow
            val oldValues = _uiHistoryFlow.value
            _uiHistoryFlow.value = historyHelper.withLoadingItem(oldValues).toMutableList()
            loadHistory(token, lastLt)
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

    private suspend fun buildItems(
        token: AccountTokenEntity,
        tokens: List<AccountTokenEntity>,
        charts: List<ChartEntity>,
        ethena: EthenaEntity?,
    ) {
        val currency = settingsRepository.currency.code
        val items = mutableListOf<Item>()

        var headerBalance = token.balance.value

        val balanceItems = mutableListOf<Item.EthenaBalance>()

        val tokenTsUsde = tokens.firstOrNull { it.isTsUSDe }

        val rates = ratesRepository.getRates(
            settingsRepository.currency, listOfNotNull(token.address, tokenTsUsde?.address)
        )

        if (token.isUSDe && !rawUsde) {
            val stonfiBalance = tokenTsUsde?.let {
                rates.convert(
                    from = WalletCurrency.TS_USDE_TON_ETHENA,
                    value = it.balance.value,
                    to = WalletCurrency.USDE_TON_ETHENA
                )
            } ?: Coins.ZERO
            val stonfiFiat = rates.convert(token.address, stonfiBalance)

            headerBalance += stonfiBalance

            balanceItems.add(
                Item.EthenaBalance(
                    position = ListCell.Position.SINGLE,
                    wallet = wallet,
                    staked = false,
                    balance = token.balance.value,
                    balanceFormat = CurrencyFormatter.format(
                        value = token.balance.value,
                    ),
                    fiatFormat = CurrencyFormatter.format(currency, token.fiat),
                    fiatRate = CurrencyFormatter.format(currency, token.rateNow),
                    rateDiff24h = rates.getDiff7d(token.address),
                    verified = token.token.verification == TokenEntity.Verification.whitelist,
                    hiddenBalance = settingsRepository.hiddenBalances,
                )
            )

            val stonfiMethod =
                ethena?.methods?.firstOrNull { it.type == EthenaEntity.Method.Type.STONFI }

            if (stonfiMethod != null && ethena != null) {
                balanceItems.add(
                    Item.EthenaBalance(
                        position = ListCell.Position.SINGLE,
                        wallet = wallet,
                        staked = true,
                        methodType = stonfiMethod.type,
                        showApy = !usdeDisabled,
                        title = ethena.about.stakeTitle,
                        apyText = ethena.about.stakeDescription,
                        balance = stonfiBalance,
                        balanceFormat = CurrencyFormatter.format(
                            value = stonfiBalance,
                        ),
                        fiatFormat = CurrencyFormatter.format(currency, stonfiFiat),
                        hiddenBalance = settingsRepository.hiddenBalances,
                    )
                )
            }
        }

        val sortedBalanceItems = balanceItems.mapIndexed { index, item ->
            item.copy(position = ListCell.getPosition(balanceItems.size, index))
        }

        val headerFiat = rates.convert(token.address, headerBalance)

        items.add(
            Item.Balance(
                balance = CurrencyFormatter.formatFull(
                    token.symbol, headerBalance, token.decimals
                ),
                fiat = CurrencyFormatter.format(currency, headerFiat),
                iconUri = token.imageUri,
                hiddenBalance = settingsRepository.hiddenBalances,
                showNetwork = tronUsdtEnabled && (token.isUsdt || token.isTrc20),
                blockchain = token.token.blockchain,
            )
        )
        items.add(
            Item.Actions(
                swapUri = api.config.swapUri,
                tronSwapUrl = if (token.isTrc20) api.config.tronSwapUrl else null,
                swapDisabled = api.config.flags.disableSwap || ((token.isUSDe || token.isTsUSDe) && usdeDisabled),
                token = token.balance.token,
                wallet = wallet,
            )
        )

        if (balanceItems.isNotEmpty()) {
            items.addAll(sortedBalanceItems)
            if (!rawUsde && token.isUSDe && usdeDisabled) {
                items.add(Item.Space)
            }
        }

        if (token.isUSDe && !usdeDisabled && !rawUsde) {
            ethena?.methods?.let { methods ->
                items.add(Item.Space)
                for ((index, method) in methods.withIndex()) {
                    val position = ListCell.getPosition(methods.size, index)
                    items.add(
                        Item.EthenaMethod(
                            position = position,
                            wallet = wallet,
                            methodType = method.type,
                            url = method.depositUrl,
                            name = method.name,
                            apy = CurrencyFormatter.formatPercent(method.apy)
                        )
                    )
                }
            }

            ethena?.about?.let {
                items.add(Item.AboutEthena(description = it.description, url = it.aboutUrl))
            }
        }

        if (token.isUsdt && !wallet.isW5 && wallet.hasPrivateKey && !api.config.flags.disableGasless && settingsRepository.isUSDTW5(
                wallet.id
            )
        ) {
            items.add(
                Item.W5Banner(
                    wallet = wallet, addButton = !hasW5()
                )
            )
        }

        if (wallet.hasPrivateKey && token.isTrc20 && !api.config.flags.disableBattery) {
            val batteryCharges = getBatteryCharges()
            if (batteryCharges < 300) {
                items.add(
                    Item.BatteryBanner(
                        wallet = wallet, token = token.balance.token
                    )
                )
            }
        }

        if (!token.isUsdt && !token.isTrc20 && !token.isUSDe) {
            if ((charts.size == 1 && charts.first().isEmpty) || !token.hasRate) {
                _uiItemsFlow.value = items
                return
            }

            val period = settingsRepository.chartPeriod
            val fiatPrice: CharSequence
            val rateDiff24h: String
            val delta: CharSequence
            if (2 >= charts.size) {
                fiatPrice = CurrencyFormatter.format(currency, token.rateNow)
                rateDiff24h = token.rateDiff24h
                delta = ""
            } else {

                val maxPrice = charts.maxOf { it.price }
                val minPrice = charts.minOf { it.price }

                val firstFiatPrice = token.rateNow.toFloat()
                val lastFiatPrice = charts.first().price

                val priceDelta: Coins
                val growPercent: Float

                if (maxPrice == minPrice) {
                    priceDelta = Coins.ZERO
                    growPercent = 0f
                } else {
                    priceDelta = Coins.of(firstFiatPrice - lastFiatPrice, token.decimals)
                    growPercent = (firstFiatPrice - lastFiatPrice) / firstFiatPrice * 100
                }


                val priceDeltaFormat = CurrencyFormatter.formatFiat(currency, priceDelta)
                val growPercentFormat = Formatter.percent(growPercent)

                fiatPrice =
                    CurrencyFormatter.format(settingsRepository.currency.code, token.rateNow)
                rateDiff24h = growPercentFormat
                delta = priceDeltaFormat
            }

            items.add(
                Item.Chart(
                    data = charts,
                    square = period == ChartPeriod.hour,
                    period = period,
                    fiatPrice = fiatPrice,
                    rateDiff24h = rateDiff24h,
                    delta = delta,
                    currency = settingsRepository.currency,
                    rateNow = token.rateNow
                )
            )
        }

        _uiItemsFlow.value = items
    }

    private suspend fun loadHistory(
        token: AccountTokenEntity, beforeLt: Long? = null
    ) = withContext(Dispatchers.IO) {
        if (token.token.blockchain === Blockchain.TRON) {
            loadTronHistory(beforeLt)
        } else {
            loadTonHistory(token, beforeLt)
        }
    }

    private suspend fun loadTonHistory(
        token: AccountTokenEntity, beforeLt: Long? = null
    ) = withContext(Dispatchers.IO) {
        val accountEvents =
            eventsRepository.loadForToken(token.address, wallet.accountId, wallet.testnet, beforeLt)
                ?: return@withContext
        val walletEventItems = mapping(wallet, accountEvents.events)
        if (beforeLt == null) {
            if (walletEventItems.isNotEmpty()) {
                setEvents(walletEventItems)
            } else {
                setEmptyEvents()
            }
        } else {
            val events =
                _uiHistoryFlow.value.filter { it !is HistoryItem.Loader && it !is HistoryItem.Empty } + walletEventItems
            if (events.isNotEmpty()) {
                setEvents(events.distinctBy { it.uniqueId })
            } else {
                setEmptyEvents()
            }
        }
    }

    private suspend fun loadTronHistory(
        beforeLt: Long? = null
    ) = withContext(Dispatchers.IO) {
        if (tronAddress == null) {
            return@withContext
        }

        val tonProofToken = accountRepository.requestTonProofToken(wallet) ?: return@withContext
        val tronEvents = eventsRepository.loadTronEvents(tronAddress!!, tonProofToken, beforeLt)
            ?: return@withContext
        val walletEventItems = historyHelper.tronMapping(
            wallet = wallet,
            tronAddress = tronAddress!!,
            events = tronEvents,
            options = ActionOptions(
                safeMode = settingsRepository.isSafeModeEnabled(api),
                hiddenBalances = settingsRepository.hiddenBalances,
            )
        )

        if (beforeLt == null) {
            if (walletEventItems.isNotEmpty()) {
                setEvents(walletEventItems)
            } else {
                setEmptyEvents()
            }
        } else {
            val events =
                _uiHistoryFlow.value.filter { it !is HistoryItem.Loader && it !is HistoryItem.Empty } + walletEventItems
            if (events.isNotEmpty()) {
                setEvents(events.distinctBy { it.uniqueId })
            } else {
                setEmptyEvents()
            }
        }
    }

    private suspend fun mapping(
        wallet: WalletEntity, events: List<AccountEvent>
    ): List<HistoryItem> {
        return historyHelper.mapping(
            wallet = wallet, events = events, options = ActionOptions(
                safeMode = settingsRepository.isSafeModeEnabled(api),
                hiddenBalances = settingsRepository.hiddenBalances,
                tronEnabled = tronUsdtEnabled,
            )
        )
    }

    private fun setEvents(
        items: List<HistoryItem>
    ) {
        _uiHistoryFlow.value = historyHelper.groupByDate(items).toMutableList()
    }

    private fun setEmptyEvents() {
        _uiHistoryFlow.value = mutableListOf(HistoryItem.Empty(context, Localization.empty_history))
    }

    private fun foundLastItem(): HistoryItem.Event? {
        return _uiHistoryFlow.value.lastOrNull { it is HistoryItem.Event } as? HistoryItem.Event
    }

    private fun isLoading(): Boolean {
        return _uiHistoryFlow.value.lastOrNull { it is HistoryItem.Loader } != null
    }

    private fun lastLt(): Long? {
        val item = foundLastItem() ?: return null
        if (item.lt > 0) {
            return item.lt
        }
        return null
    }

    private suspend fun loadChart(
        token: AccountTokenEntity, startDateSeconds: Long, endDateSeconds: Long
    ) = withContext(Dispatchers.IO) {
        val chart = if (!token.isStable) {
            api.loadChart(
                token.address, settingsRepository.currency.code, startDateSeconds, endDateSeconds
            )
        } else {
            emptyList()
        }

        _chartFlow.value = chart.ifEmpty { listOf(ChartEntity.EMPTY) }
    }

    private suspend fun loadHourChart(token: AccountTokenEntity) {
        val endDateSeconds = System.currentTimeMillis() / 1000
        val startDateSeconds = endDateSeconds - 60 * 60
        loadChart(token, startDateSeconds, endDateSeconds)
    }

    private suspend fun loadDayChart(token: AccountTokenEntity) {
        val endDateSeconds = System.currentTimeMillis() / 1000
        val startDateSeconds = endDateSeconds - 60 * 60 * 24
        loadChart(token, startDateSeconds, endDateSeconds)
    }

    private suspend fun loadWeekChart(token: AccountTokenEntity) {
        val endDateSeconds = System.currentTimeMillis() / 1000
        val startDateSeconds = endDateSeconds - 60 * 60 * 24 * 7
        loadChart(token, startDateSeconds, endDateSeconds)
    }

    private suspend fun loadMonthChart(token: AccountTokenEntity) {
        val endDateSeconds = System.currentTimeMillis() / 1000
        val startDateSeconds = endDateSeconds - 60 * 60 * 24 * 30
        loadChart(token, startDateSeconds, endDateSeconds)
    }

    private suspend fun load6MonthChart(token: AccountTokenEntity) {
        val endDateSeconds = System.currentTimeMillis() / 1000
        val startDateSeconds = endDateSeconds - 60 * 60 * 24 * 30 * 6
        loadChart(token, startDateSeconds, endDateSeconds)
    }

    private suspend fun loadYearChart(token: AccountTokenEntity) {
        val endDateSeconds = System.currentTimeMillis() / 1000
        val startDateSeconds = endDateSeconds - 60 * 60 * 24 * 365
        loadChart(token, startDateSeconds, endDateSeconds)
    }

    private suspend fun getBatteryCharges(): Int = withContext(Dispatchers.IO) {
        accountRepository.requestTonProofToken(wallet)?.let {
            batteryRepository.getCharges(it, wallet.publicKey, wallet.testnet, true)
        } ?: 0
    }
}