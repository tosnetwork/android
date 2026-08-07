package network.tos.wallet.app.ui.screen.events.main

import network.tos.wallet.app.core.history.list.item.HistoryItem

data class EventsUiState(
    val uiItems: List<HistoryItem>,
    val loading: Boolean,
) {

    val isFooterLoading: Boolean = uiItems.lastOrNull()?.let { it is HistoryItem.Loader } ?: false

    var isFooterFailed: Boolean = uiItems.lastOrNull()?.let { it is HistoryItem.Failed } ?: false
}