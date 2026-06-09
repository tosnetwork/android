package com.tonapps.wallet.data.collectibles

import android.content.Context
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tonapps.blockchain.ton.extensions.equalsAddress
import com.tonapps.wallet.api.API
import com.tonapps.wallet.api.withRetry
import com.tonapps.wallet.data.collectibles.entities.DnsExpiringEntity
import com.tonapps.wallet.data.collectibles.entities.NftEntity
import com.tonapps.wallet.data.collectibles.entities.NftListResult
import com.tonapps.wallet.data.collectibles.source.LocalDataSource
import io.extensions.renderType
import io.tonapi.models.TrustType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

class CollectiblesRepository(
    private val context: Context,
    private val api: API
) {

    private val localDataSource: LocalDataSource by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LocalDataSource(context)
    }

    suspend fun getDnsExpiring(accountId: String, testnet: Boolean, period: Int) = api.getDnsExpiring(accountId, testnet, period).map { model ->
        DnsExpiringEntity(
            expiringAt = model.expiringAt,
            name = model.name,
            dnsItem = model.dnsItem?.let { NftEntity(it, testnet) }
        )
    }.sortedBy { it.daysUntilExpiration }

    suspend fun getDnsSoonExpiring(accountId: String, testnet: Boolean, period: Int = 30) = getDnsExpiring(accountId, testnet, period)

    suspend fun getDnsNftExpiring(
        accountId: String,
        testnet: Boolean,
        nftAddress: String
    ) = getDnsExpiring(accountId, testnet, 366).firstOrNull {
        it.dnsItem?.address?.equalsAddress(nftAddress) == true
    }

    fun getNft(accountId: String, testnet: Boolean, address: String): NftEntity? {
        val nft = localDataSource.getSingle(accountId, testnet, address)
        if (nft != null) {
            return nft
        }
        return api.getNft(address, testnet)?.let { NftEntity(it, testnet) }
    }

    fun get(address: String, testnet: Boolean): List<NftEntity>? {
        val local = localDataSource.get(address, testnet)
        if (local.isEmpty()) {
            return getRemoteNftItems(address, testnet)
        }
        return local
    }

    fun getFlow(address: String, testnet: Boolean, isOnline: Boolean) = flow {
        try {
            val local = getLocalNftItems(address, testnet)
            if (local.isNotEmpty()) {
                emit(NftListResult(cache = true, list = local))
            }

            if (isOnline) {
                val remote = getRemoteNftItems(address, testnet) ?: return@flow
                emit(NftListResult(cache = false, list = remote))
            }
        } catch (e: Throwable) {
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }.cancellable()

    private fun getLocalNftItems(
        address: String,
        testnet: Boolean
    ): List<NftEntity> {
        return localDataSource.get(address, testnet)
    }

    private fun getRemoteNftItems(
        address: String,
        testnet: Boolean
    ): List<NftEntity>? {
        val nftItems = api.getNftItems(address, testnet) ?: return null
        val items = nftItems.filter {
            it.trust != TrustType.blacklist && it.renderType != "hidden"
        }.map { NftEntity(it, testnet) }

        localDataSource.save(address, testnet, items.toList())
        return items
    }
}