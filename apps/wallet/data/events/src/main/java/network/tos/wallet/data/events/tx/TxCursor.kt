package network.tos.wallet.data.events.tx

import network.tos.wallet.api.entity.value.Timestamp

/** Cursor for the next combined TOS/TRON history page. TOS nodes require lt and hash together. */
data class TxCursor(
    val beforeLt: Long?,
    val beforeHash: String?,
    val beforeTimestamp: Timestamp?,
)
