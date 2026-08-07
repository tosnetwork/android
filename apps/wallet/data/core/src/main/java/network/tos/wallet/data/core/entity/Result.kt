package network.tos.wallet.data.core.entity

data class Result<T>(
    val loading: Boolean,
    val data: T
)