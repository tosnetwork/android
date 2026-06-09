package com.tonapps.tonkeeper.manager.tx

import com.tonapps.extensions.MutableEffectFlow
import com.tonapps.tonkeeper.manager.tx.model.PendingHash
import com.tonapps.tonkeeper.manager.tx.model.PendingWrapEvent
import com.tonapps.wallet.api.API
import com.tonapps.wallet.api.withRetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext

open class BaseTransactionManager(
    private val api: API
) {

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _pendingHashFlow = MutableEffectFlow<PendingHash>()
    private val pendingTxFlow = _pendingHashFlow.mapNotNull(::fetchPendingTx).flowOn(Dispatchers.IO)


    fun addPendingHash(accountId: String, testnet: Boolean, hash: String) {
        _pendingHashFlow.tryEmit(PendingHash(accountId, testnet, hash))
    }

    private suspend fun fetchTx(
        accountId: String,
        testnet: Boolean,
        hash: String
    ) = withContext(Dispatchers.IO) {
        withRetry {
            api.accounts(testnet).getAccountEvent(accountId, hash)
        }
    }

    private suspend fun fetchPendingTx(hash: PendingHash): PendingWrapEvent? {
        val event = fetchTx(
            accountId = hash.accountId,
            testnet = hash.testnet,
            hash = hash.hash
        ) ?: return null
        return PendingWrapEvent(hash, event)
    }


}