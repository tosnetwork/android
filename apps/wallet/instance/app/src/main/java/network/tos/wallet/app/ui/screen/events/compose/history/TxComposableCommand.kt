package network.tos.wallet.app.ui.screen.events.compose.history

sealed interface TxComposableCommand {
    data object Refresh : TxComposableCommand
    data object ScrollUp : TxComposableCommand
}