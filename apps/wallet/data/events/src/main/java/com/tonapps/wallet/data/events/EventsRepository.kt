package com.tonapps.wallet.data.events

import android.content.Context
import android.util.Log
import com.tonapps.wallet.api.API
import com.tonapps.wallet.api.entity.TokenEntity
import com.tonapps.wallet.api.entity.value.BlockchainAddress
import com.tonapps.wallet.api.entity.value.Timestamp
import com.tonapps.wallet.api.tron.entity.TronEventEntity
import com.tonapps.wallet.data.collectibles.CollectiblesRepository
import com.tonapps.wallet.data.events.entities.LatestRecipientEntity
import com.tonapps.wallet.data.events.source.LocalDataSource
import com.tonapps.wallet.data.events.source.RemoteDataSource
import com.tonapps.wallet.data.events.tx.TxActionMapper
import com.tonapps.wallet.data.events.tx.TxFetchQuery
import com.tonapps.wallet.data.events.tx.TxPage
import com.tonapps.wallet.data.events.tx.db.TxDatabase
import com.tonapps.wallet.data.rates.RatesRepository
import io.tonapi.models.AccountEvent
import io.tonapi.models.AccountEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.collections.emptyList

class EventsRepository(
    scope: CoroutineScope,
    context: Context,
    private val api: API,
    private val collectiblesRepository: CollectiblesRepository,
    private val ratesRepository: RatesRepository
) {

    private val txDatabase = TxDatabase.instance(context)

    private val localDataSource: LocalDataSource by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LocalDataSource(scope, context)
    }

    private val txActionMapper = TxActionMapper(collectiblesRepository, ratesRepository, api)
    private val remoteDataSource = RemoteDataSource(api, txActionMapper)

    val decryptedCommentFlow: Flow<Map<String, String>>
        get() = localDataSource.decryptedCommentFlow

    private val _hiddenTxIdsFlow = MutableStateFlow<Set<String>>(emptySet())
    val hiddenTxIdsFlow = _hiddenTxIdsFlow.stateIn(scope, SharingStarted.WhileSubscribed(), emptySet())

    fun getDecryptedComment(txId: String) = localDataSource.getDecryptedComment(txId)

    fun saveDecryptedComment(txId: String, comment: String) {
        localDataSource.saveDecryptedComment(txId, comment)
    }

    fun clearTxEvents(account: BlockchainAddress) {
        localDataSource.clearTxEvents(account)
    }

    suspend fun fetch(query: TxFetchQuery): TxPage {
        val events = remoteDataSource.events(query)
        return TxPage(
            source = TxPage.Source.REMOTE,
            events = events,
            beforeTimestamp = query.beforeTimestamp,
            afterTimestamp = query.afterTimestamp,
            limit = query.limit
        )
    }

    suspend fun tronLatestSentTransactions(
        tronWalletAddress: String, tonProofToken: String
    ): List<TronEventEntity> {
        val events = loadTronEvents(tronWalletAddress, tonProofToken) ?: return emptyList()

        val sentTransactions =
            events.filter { it.from == tronWalletAddress && it.to != tronWalletAddress }
                .distinctBy { it.to }

        return sentTransactions.take(6)
    }

    fun latestRecipientsFlow(accountId: String, testnet: Boolean) = flow {
        localDataSource.getLatestRecipients(cacheLatestRecipientsKey(accountId, testnet))?.let {
            emit(it)
        }

        val remote = loadLatestRecipients(accountId, testnet)
        emit(remote)
    }.flowOn(Dispatchers.IO)

    private fun loadLatestRecipients(accountId: String, testnet: Boolean): List<LatestRecipientEntity> {
        val list = remoteDataSource.getLatestRecipients(accountId, testnet)
        localDataSource.setLatestRecipients(cacheLatestRecipientsKey(accountId, testnet), list)
        return list
    }

    suspend fun getSingle(eventId: String, testnet: Boolean) = remoteDataSource.getSingle(eventId, testnet)

    suspend fun loadForToken(
        tokenAddress: String,
        accountId: String,
        testnet: Boolean,
        beforeLt: Long? = null
    ): AccountEvents? = withContext(Dispatchers.IO) {
        if (tokenAddress == TokenEntity.TON.address) {
            getRemote(accountId, testnet, beforeLt)
        } else {
            try {
                api.getTokenEvents(tokenAddress, accountId, testnet, beforeLt)
            } catch (e: Throwable) {
                null
            }
        }
    }

    suspend fun loadTronEvents(
        tronWalletAddress: String,
        tonProofToken: String,
        maxTimestamp: Long? = null,
        limit: Int = 30
    ) = withContext(Dispatchers.IO) {
        try {
            val events = api.tron.getTronHistory(tronWalletAddress, tonProofToken, limit, maxTimestamp?.let { Timestamp.from(it) })

            if (maxTimestamp == null) {
                localDataSource.setTronEvents(tronWalletAddress, events)
            }

            events
        } catch (e: Throwable) {
            null
        }
    }

    suspend fun get(
        accountId: String,
        testnet: Boolean
    ) = getLocal(accountId, testnet) ?: getRemote(accountId, testnet)

    suspend fun getRemote(
        accountId: String,
        testnet: Boolean,
        beforeLt: Long? = null,
        limit: Int = 10
    ): AccountEvents? = withContext(Dispatchers.IO) {
        try {
            val accountEvents = if (beforeLt != null) {
                remoteDataSource.get(accountId, testnet, beforeLt, limit)
            } else {
                val events = remoteDataSource.get(accountId, testnet, null, limit)?.also {
                    localDataSource.setEvents(cacheEventsKey(accountId, testnet), it)
                }
                events
            } ?: return@withContext null

            localDataSource.addSpam(accountId, testnet, accountEvents.events.filter {
                it.isScam
            })

            accountEvents
        } catch (e: Throwable) {
            null
        }
    }

    suspend fun getLocalSpam(accountId: String, testnet: Boolean) = withContext(Dispatchers.IO) {
        localDataSource.getSpam(accountId, testnet)
    }

    suspend fun markAsSpam(
        accountId: String,
        testnet: Boolean,
        eventId: String,
    ) = withContext(Dispatchers.IO) {
        val events = getSingle(eventId, testnet) ?: return@withContext
        localDataSource.addSpam(accountId, testnet, events)
        _hiddenTxIdsFlow.update {
            it.plus(eventId)
        }
    }

    suspend fun removeSpam(
        accountId: String,
        testnet: Boolean,
        eventId: String,
    ) = withContext(Dispatchers.IO) {
        localDataSource.removeSpam(accountId, testnet, eventId)
        _hiddenTxIdsFlow.update {
            it.minus(eventId)
        }
    }

    suspend fun getRemoteSpam(
        accountId: String,
        testnet: Boolean,
        startBeforeLt: Long? = null
    ) = withContext(Dispatchers.IO) {
        val list = mutableListOf<AccountEvent>()
        var beforeLt: Long? = startBeforeLt
        for (i in 0 until 10) {
            val events = remoteDataSource.get(
                accountId = accountId,
                testnet = testnet,
                beforeLt = beforeLt,
                limit = 50
            )?.events ?: emptyList()

            if (events.isEmpty() || events.size >= 500) {
                break
            }

            list.addAll(events)
            beforeLt = events.lastOrNull()?.lt ?: break
        }
        val spamList = list.filter { it.isScam }
        localDataSource.addSpam(accountId, testnet, spamList)
        spamList
    }

    suspend fun getLocal(
        accountId: String,
        testnet: Boolean
    ): AccountEvents? = withContext(Dispatchers.IO) {
        localDataSource.getEvents(cacheEventsKey(accountId, testnet))
    }

    private fun cacheEventsKey(accountId: String, testnet: Boolean): String {
        if (!testnet) {
            return accountId
        }
        return "${accountId}_testnet"
    }

    private fun cacheLatestRecipientsKey(accountId: String, testnet: Boolean): String {
        if (!testnet) {
            return accountId
        }
        return "${accountId}_testnet"
    }

}