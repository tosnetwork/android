package network.tos.wallet.data.swap

import android.content.Context
import network.tos.extensions.map
import network.tos.extensions.mapList
import network.tos.extensions.toByteArray
import network.tos.extensions.toListParcel
import network.tos.wallet.api.API
import network.tos.wallet.data.core.BlobDataSource
import network.tos.wallet.data.core.currency.WalletCurrency
import network.tos.wallet.data.swap.entity.SwapAssetEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SwapRepository(
    private val context: Context,
    private val api: API,
    private val scope: CoroutineScope,
) : BlobDataSource<List<SwapAssetEntity>>(
    context = context,
    path = "swap",
    timeout = TimeUnit.DAYS.toMillis(1)
) {

    private companion object {
        private const val ASSETS_KEY = "assets"
    }

    val assetsFlow = flow {
        emit(getAssets())
    }.mapList { it.currency }.stateIn(scope, SharingStarted.Lazily, null).filterNotNull()

    suspend fun getAssets(): List<SwapAssetEntity> = withContext(Dispatchers.IO) {
        getCache(ASSETS_KEY) ?: loadAssets()
    }

    private fun loadAssets(): List<SwapAssetEntity> {
        val list = api.getSwapAssets().map(::SwapAssetEntity)
        if (list.isEmpty()) {
            return emptyList()
        }
        setCache(ASSETS_KEY, list)
        return list
    }

    override fun onMarshall(data: List<SwapAssetEntity>) = data.toByteArray()

    override fun onUnmarshall(bytes: ByteArray) = bytes.toListParcel<SwapAssetEntity>()
}
