package network.tos.wallet.data.token.source

import android.content.Context
import network.tos.extensions.prefs
import network.tos.extensions.toByteArray
import network.tos.extensions.toListParcel
import network.tos.wallet.api.entity.BalanceEntity
import network.tos.wallet.data.core.BlobDataSource

internal class LocalDataSource(context: Context): BlobDataSource<List<BalanceEntity>>(
    context = context,
    path = "wallet"
) {

    private val prefs = context.prefs("tokens")

    override fun onMarshall(data: List<BalanceEntity>) = data.toByteArray()

    override fun onUnmarshall(bytes: ByteArray) = bytes.toListParcel<BalanceEntity>()
}