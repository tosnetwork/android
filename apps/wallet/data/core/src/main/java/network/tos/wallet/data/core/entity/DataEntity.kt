package network.tos.wallet.data.core.entity

data class DataEntity<T>(
    val cache: Boolean,
    val data: T
)