package network.tos.wallet.api.internal

import androidx.core.net.toUri
import network.tos.network.get
import network.tos.network.sse
import network.tos.wallet.api.SwapAssetParam
import network.tos.wallet.api.entity.SwapEntity
import network.tos.wallet.api.withRetry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient

internal class SwapApi(
    private val okHttpClient: OkHttpClient
) {

    fun getSwapAssets(prefix: String) = withRetry {
        okHttpClient.get("$prefix/v2/swap/assets")
    }

    fun stream(
        prefix: String,
        from: SwapAssetParam,
        to: SwapAssetParam,
        userAddress: String
    ): Flow<SwapEntity.Messages?> {
        if (from.isEmpty && to.isEmpty) {
            return emptyFlow()
        }
        val builder = "$prefix/v2/swap/omniston/stream".toUri().buildUpon()
        from.apply("from", builder)
        to.apply("to", builder)
        builder.appendQueryParameter("userAddress", userAddress)
        val url = builder.build().toString()
        return okHttpClient.sse(url) { }.map {
            SwapEntity.parse(it.data)
        }
    }

}