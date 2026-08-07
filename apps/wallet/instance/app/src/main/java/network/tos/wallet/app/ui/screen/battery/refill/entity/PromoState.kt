package network.tos.wallet.app.ui.screen.battery.refill.entity

sealed class PromoState {
    data object Default: PromoState()
    data object Error: PromoState()
    data object Loading: PromoState()
    data class Applied(
        val appliedPromo: String
    ): PromoState()
}
