package network.tos.wallet.data.events.tx

import network.tos.wallet.api.entity.value.BlockchainAddress
import network.tos.wallet.api.entity.value.Timestamp

data class TxFetchQuery(
    val tonAddress: BlockchainAddress,
    val tronAddress: BlockchainAddress?,
    val tonProofToken: String?,
    val beforeTimestamp: Timestamp?,
    val afterTimestamp: Timestamp?,
    val limit: Int
)