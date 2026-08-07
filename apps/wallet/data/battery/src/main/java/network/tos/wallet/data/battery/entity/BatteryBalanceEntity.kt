package network.tos.wallet.data.battery.entity

import android.os.Parcelable
import network.tos.icu.Coins
import kotlinx.parcelize.Parcelize

@Parcelize
data class BatteryBalanceEntity(
    val balance: Coins,
    val reservedBalance: Coins,
) : Parcelable {

    companion object {
        val Empty = BatteryBalanceEntity(
            balance = Coins.ZERO,
            reservedBalance = Coins.ZERO
        )
    }
}