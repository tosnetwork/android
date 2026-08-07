package network.tos.wallet.app.ui.screen.events.compose.history.state

import ui.components.events.UiEvent

data class TxNewestItemsState(
    val loading: Boolean = false,
    val items: List<UiEvent.Item> = emptyList(),
)