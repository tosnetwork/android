package network.tos.wallet.data.events.tx.db

import androidx.room.TypeConverter
import network.tos.extensions.toByteArray
import network.tos.extensions.toParcel
import network.tos.wallet.data.events.tx.model.TxEvent

object TxConverters {

    @TypeConverter
    @JvmStatic
    fun fromEvent(event: TxEvent) = event.toByteArray()

    @TypeConverter
    @JvmStatic
    fun toEvent(bytes: ByteArray) = bytes.toParcel<TxEvent>()
}