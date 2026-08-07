package network.tos.wallet.data.collectibles.entities

data class NftListResult(
    val cache: Boolean = false,
    val list: List<NftEntity>
)