package network.tos.wallet.data.events.tx

import network.tos.wallet.api.entity.value.Timestamp
import network.tos.wallet.data.events.tx.model.TxEvent

data class TxPage(
    val source: Source,
    val events: List<TxEvent>,
    val beforeTimestamp: Timestamp?,
    val afterTimestamp: Timestamp?,
    val limit: Int
) {

    enum class Source {
        LOCAL, REMOTE
    }

    val isEmpty: Boolean
        get() = events.isEmpty()

    val isCached: Boolean
        get() = source == Source.LOCAL

    val nextKey: TxCursor?
        get() {
            val tosEvent = events
                .filter { it.blockchain == network.tos.wallet.api.entity.value.Blockchain.TON }
                .minByOrNull { it.lt }
            val beforeTimestamp = events.minByOrNull { it.timestamp.value }?.timestamp
            if (tosEvent == null && beforeTimestamp == null) return null
            return TxCursor(
                beforeLt = tosEvent?.lt,
                beforeHash = tosEvent?.hash,
                beforeTimestamp = beforeTimestamp,
            )
        }
}
