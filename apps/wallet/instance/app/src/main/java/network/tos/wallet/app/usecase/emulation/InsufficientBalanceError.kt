package network.tos.wallet.app.usecase.emulation

import network.tos.icu.Coins

data class InsufficientBalanceError(
    val accountBalance: Coins,
    val totalAmount: Coins
) : RuntimeException(
    "Insufficient balance: have $accountBalance, need $totalAmount"
)