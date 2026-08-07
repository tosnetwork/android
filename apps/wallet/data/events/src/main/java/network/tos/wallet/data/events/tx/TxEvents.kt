package network.tos.wallet.data.events.tx

import android.os.Parcelable
import network.tos.wallet.data.events.tx.model.TxEvent
import kotlinx.parcelize.Parcelize

@Parcelize
data class TxEvents(
    val events: List<TxEvent>
): Parcelable