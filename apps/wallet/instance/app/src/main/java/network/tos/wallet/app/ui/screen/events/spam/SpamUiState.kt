package network.tos.wallet.app.ui.screen.events.spam

import network.tos.wallet.app.core.history.list.item.HistoryItem

data class SpamUiState(
    val uiItems: List<HistoryItem> = listOf(HistoryItem.Loader(0, 0L)),
    val loading: Boolean = true
) {
}