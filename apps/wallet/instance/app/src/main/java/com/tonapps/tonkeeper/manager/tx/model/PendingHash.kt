package com.tonapps.tonkeeper.manager.tx.model

data class PendingHash(
    val accountId: String,
    val testnet: Boolean,
    val hash: String
)