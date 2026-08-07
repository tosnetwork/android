package network.tos.wallet.app.ui.screen.events.compose.history

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.insertSeparators
import androidx.paging.map
import network.tos.extensions.MutableEffectFlow
import network.tos.wallet.app.helper.DateHelper
import network.tos.wallet.app.manager.tx.TransactionManager
import network.tos.wallet.data.events.tx.model.TxEvent
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.screen.events.compose.TxScope.decryptComment
import network.tos.wallet.app.ui.screen.events.compose.details.TxDetailsScreen
import network.tos.wallet.app.ui.screen.events.compose.history.paging.TxPagingSource
import network.tos.wallet.app.ui.screen.events.compose.history.state.TxFilter
import network.tos.wallet.app.ui.screen.events.spam.SpamEventsScreen
import network.tos.wallet.app.ui.screen.nft.NftScreen
import network.tos.wallet.app.ui.screen.onramp.main.OnRampScreen
import network.tos.wallet.app.ui.screen.qr.QRScreen
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.collectibles.CollectiblesRepository
import network.tos.wallet.data.events.EventsRepository
import network.tos.wallet.data.events.tx.model.TxActionBody
import network.tos.wallet.data.passcode.PasscodeManager
import network.tos.wallet.data.settings.SettingsRepository
import network.tos.wallet.localization.Localization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okio.IOException
import ui.components.events.EventItemClickPart
import ui.components.events.UiEvent
import uikit.extensions.collectFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class TxEventsViewModel(
    app: Application,
    private val wallet: WalletEntity,
    private val accountRepository: AccountRepository,
    private val eventsRepository: EventsRepository,
    private val settingsRepository: SettingsRepository,
    private val collectiblesRepository: CollectiblesRepository,
    private val passcodeManager: PasscodeManager,
    private val transactionManager: TransactionManager,
) : BaseWalletVM(app) {

    private companion object {
        private val monthYearFormatter = SimpleDateFormat("MMMM_yyyy", Locale.US)
        private val dayMonthFormatter = SimpleDateFormat("d_MMMM", Locale.US)
    }

    private val _uiCommandFlow = MutableEffectFlow<TxComposableCommand>()
    val uiCommandFlow = _uiCommandFlow.asSharedFlow()

    private val uiMapper = TxUiMapper(context, wallet, eventsRepository, settingsRepository)
    private val locale = settingsRepository.getLocale()

    private val _selectedFilterIdFlow = MutableStateFlow(TxFilter.All.id)
    val selectedFilterIdFlow = _selectedFilterIdFlow.asStateFlow()

    val selectedFilterId: Int
        get() = _selectedFilterIdFlow.value

    val hiddenBalances: Boolean
        get() = settingsRepository.hiddenBalances

    val hiddenBalancesFlow = settingsRepository.hiddenBalancesFlow

    private val pager = Pager(
        config = PagingConfig(
            initialLoadSize = 5,
            prefetchDistance = 2,
            pageSize = 5,
            enablePlaceholders = false,
            maxSize = PagingConfig.MAX_SIZE_UNBOUNDED,
            jumpThreshold = Int.MIN_VALUE,
        ),
        pagingSourceFactory = { createPagingSource() }
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val uiItemsPagingFlow = pager.flow.cachedIn(viewModelScope)

    val uiItemsFlow = combine(
        uiItemsPagingFlow,
        selectedFilterIdFlow,
        eventsRepository.decryptedCommentFlow,
        eventsRepository.hiddenTxIdsFlow,
    ) { paging, filterId, decryptedComment, hiddenTxIds ->
        paging.filter { item ->
            !hiddenTxIds.contains(item.id) && !item.spam && (filterId == TxFilter.All.id || item.isMatch(filterId))
        }.map { item ->
            val decrypted = decryptedComment[item.id]
            if (decrypted.isNullOrEmpty()) {
                item
            } else {
                uiMapper.changeText(item, decrypted)
            }
        }.insertSeparators { before: UiEvent.Item?, after: UiEvent.Item? ->
            if (after == null) {
                null
            } else {
                val beforeKey = before?.timestamp?.let(::headerKeyCalendar)
                val afterKey = headerKeyCalendar(after.timestamp)
                if (beforeKey == null || beforeKey != afterKey) {
                    UiEvent.Header(afterKey, headerFormatDate(after.timestamp))
                } else {
                    null
                }
            }
        }
    }.distinctUntilChanged().cachedIn(viewModelScope)

    init {
        transactionManager.eventsFlow(wallet).collectFlow {
            requestRefresh()
            selectFilterById()
        }

        combine(
            settingsRepository.tokenPrefsChangedFlow.drop(1),
            settingsRepository.walletPrefsChangedFlow.drop(1)
        ) { _, _ ->
            requestRefresh()
            selectFilterById()
        }.launch()

        selectedFilterIdFlow.collectFlow { requestScrollUp() }
    }

    private fun requestScrollUp() {
        _uiCommandFlow.tryEmit(TxComposableCommand.ScrollUp)
    }

    private fun requestRefresh() {
        _uiCommandFlow.tryEmit(TxComposableCommand.Refresh)
    }

    fun dispatch(action: TxEventsAction) {
        when (action) {
            is TxEventsAction.BuyTon -> openBuyTon()
            is TxEventsAction.OpenQR -> openReceive()
            is TxEventsAction.Details -> onClick(action.id, action.part)
            is TxEventsAction.SelectFilter -> selectFilterById(action.id)
        }
    }

    fun selectFilterById(filterId: Int = TxFilter.All.id) {
        val filter = if (_selectedFilterIdFlow.value == filterId) {
            TxFilter.All
        } else {
            TxFilter.entries.find { it.id == filterId } ?: TxFilter.All
        }
        if (filter == TxFilter.Spam) {
            viewModelScope.launch {
                openScreen(SpamEventsScreen.newInstance(wallet))
            }
        } else {
            _selectedFilterIdFlow.value = filter.id
        }
    }

    fun headerKeyCalendar(timestamp: Long): String {
        try {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = timestamp
            val now = Calendar.getInstance()
            val yearDiff = now.get(Calendar.YEAR) - calendar.get(Calendar.YEAR)
            val monthDiff = yearDiff * 12 + now.get(Calendar.MONTH) - calendar.get(Calendar.MONTH)

            return if (monthDiff < 1) {
                dayMonthFormatter.format(calendar.time)
            } else {
                monthYearFormatter.format(calendar.time)
            }
        } catch (ignored: Throwable) {
            return "zero"
        }
    }

    private fun headerFormatDate(timestamp: Long): String {
        return DateHelper.formatTransactionsGroupDate(context, timestamp, locale)
    }

    private fun onClick(id: String, part: EventItemClickPart) {
        val tx = TxPagingSource.get(id) ?: return
        viewModelScope.launch {
            if (part is EventItemClickPart.Product) {
                val product = tx.actions[part.index].product ?: return@launch
                if (product.type == TxActionBody.Product.Type.Nft) {
                    openNft(product.id)
                }
            } else if (part is EventItemClickPart.Encrypted) {
                decryptComment(
                    wallet = wallet,
                    tx = tx,
                    actionIndex = part.index,
                    accountRepository = accountRepository,
                    settingsRepository = settingsRepository,
                    passcodeManager = passcodeManager,
                    eventsRepository = eventsRepository
                )
            } else if (part is EventItemClickPart.Action) {
                openDetails(tx, part.index)
            }
        }
    }

    private suspend fun openNft(address: String) {
        try {
            val nftItem = collectiblesRepository.getNft(
                accountId = wallet.accountId,
                testnet = wallet.testnet,
                address = address
            ) ?: throw IOException()
            openScreen(NftScreen.newInstance(wallet, nftItem))
        } catch (ignored: Throwable) {
            toast(Localization.unknown_error)
        }
    }

    private suspend fun openDetails(tx: TxEvent, actionIndex: Int) {
        openScreen(TxDetailsScreen.newInstance(wallet, tx, actionIndex))
    }

    fun openBuyTon() {
        viewModelScope.launch {
            openScreen(OnRampScreen.newInstance(context, wallet, "history"))
        }
    }

    fun openReceive() {
        viewModelScope.launch {
            openScreen(QRScreen.newInstance(wallet))
        }
    }

    private fun createPagingSource() = TxPagingSource(
        wallet = wallet,
        accountRepository = accountRepository,
        eventsRepository = eventsRepository,
        settingsRepository = settingsRepository,
        uiMapper = uiMapper
    )

}
