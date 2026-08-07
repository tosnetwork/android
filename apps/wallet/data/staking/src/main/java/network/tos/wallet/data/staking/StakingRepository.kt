package network.tos.wallet.data.staking

import android.content.Context
import network.tos.wallet.api.API
import network.tos.wallet.data.staking.entities.PoolInfoEntity
import network.tos.wallet.data.staking.entities.StakingEntity
import network.tos.wallet.data.staking.entities.StakingInfoEntity
import network.tos.wallet.data.staking.source.LocalDataSource
import network.tos.wallet.data.staking.source.RemoteDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StakingRepository(context: Context, api: API) {

    private val localDataSource = LocalDataSource(context)
    private val remoteDataSource = RemoteDataSource(api)

    suspend fun get(
        accountId: String,
        testnet: Boolean,
        ignoreCache: Boolean = false,
        initializedAccount: Boolean = true
    ): StakingEntity = withContext(Dispatchers.IO) {
        val cacheKey = cacheKey(accountId, testnet)
        val local: StakingEntity? = if (ignoreCache) null else localDataSource.getCache(cacheKey)
        if (local == null) {
            val remote = remoteDataSource.load(accountId, testnet, initializedAccount)
            localDataSource.setCache(cacheKey, remote)
            return@withContext remote
        }
        return@withContext local
    }

    private fun cacheKey(accountId: String, testnet: Boolean): String {
        if (!testnet) {
            return accountId
        }
        return "${accountId}_testnet_2"
    }
}