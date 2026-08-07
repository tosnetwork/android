package network.tos.wallet.data.browser.source

import android.content.Context
import network.tos.extensions.toByteArray
import network.tos.extensions.toParcel
import network.tos.wallet.data.browser.entities.BrowserDataEntity
import network.tos.wallet.data.core.BlobDataSource

internal class LocalDataSource(context: Context): BlobDataSource<BrowserDataEntity>(
    context = context,
    path = "browser_data"
) {
    override fun onMarshall(data: BrowserDataEntity) = data.toByteArray()

    override fun onUnmarshall(bytes: ByteArray) = bytes.toParcel<BrowserDataEntity>()
}