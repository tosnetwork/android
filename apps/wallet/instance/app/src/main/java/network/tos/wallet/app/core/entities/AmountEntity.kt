package network.tos.wallet.app.core.entities

import network.tos.icu.Coins

data class AmountEntity(
    val value: Value,
    val converted: Value
) {

    data class Value(
        val value: Coins,
        val format: CharSequence
    )
}