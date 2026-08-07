package network.tos.wallet.app.ui.screen.init

sealed class InitEvent {
    data class Loading(val loading: Boolean): InitEvent()
    data object Back: InitEvent()
}