package network.tos.blockchain.ton.connect

import android.os.Parcelable
import network.tos.extensions.toByteArray
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import java.nio.ByteOrder

@Parcelize
data class TCDomain(val value: String): TCSerializable, Parcelable {

    @IgnoredOnParcel
    private val lengthBytes: Int = value.toByteArray().size

    override fun toByteArray(order: ByteOrder): ByteArray {
        return lengthBytes.toByteArray(order) + value.toByteArray()
    }
}