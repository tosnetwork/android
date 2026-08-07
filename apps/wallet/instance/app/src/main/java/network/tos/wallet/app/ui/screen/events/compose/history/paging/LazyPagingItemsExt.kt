package network.tos.wallet.app.ui.screen.events.compose.history.paging

import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import network.tos.wallet.app.ui.screen.events.compose.history.state.TxFilter
import network.tos.wallet.app.ui.screen.events.compose.history.state.TxScreenState
import ui.components.events.UiEvent

fun LazyPagingItems<UiEvent>.screenState(selectedFilterId: Int): TxScreenState {
    val refreshState = loadState.refresh
    val isEmpty = itemCount == 0
    val isFilterActive = selectedFilterId != TxFilter.All.id

    return when {
        refreshState is LoadState.Error -> TxScreenState.Error
        refreshState is LoadState.Loading && isEmpty -> TxScreenState.Placeholder
        isEmpty && !isFilterActive -> TxScreenState.Empty
        else -> TxScreenState.List
    }
}