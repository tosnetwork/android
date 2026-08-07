package network.tos.wallet.app.ui.screen.battery.recharge.entity

import androidx.annotation.StringRes
import network.tos.icu.Coins
import network.tos.wallet.data.core.entity.SignRequestEntity

sealed class BatteryRechargeEvent {
    data class Sign(val request: SignRequestEntity, val forceRelayer: Boolean) : BatteryRechargeEvent()
    data object Error : BatteryRechargeEvent()
    data class MaxAmountError(val maxAmount: Coins, val currency: String) : BatteryRechargeEvent()
}
