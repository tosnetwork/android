package network.tos.wallet.data.rates.entity

import android.os.Parcelable
import network.tos.icu.Coins
import network.tos.wallet.data.core.currency.WalletCurrency
import kotlinx.parcelize.Parcelize

@Parcelize
data class RateEntity(
    val tokenCode: String,
    val currency: WalletCurrency,
    val value: Coins,
    val diff: RateDiffEntity
): Parcelable