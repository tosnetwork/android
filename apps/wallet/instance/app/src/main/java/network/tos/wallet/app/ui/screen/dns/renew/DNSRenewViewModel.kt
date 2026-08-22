package network.tos.wallet.app.ui.screen.dns.renew

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import network.tos.blockchain.ton.extensions.equalsAddress
import network.tos.extensions.currentTimeSeconds
import network.tos.wallet.app.ui.base.BaseWalletVM
import network.tos.wallet.app.ui.screen.dns.TosDnsTransactionBuilder
import network.tos.wallet.app.ui.screen.dns.renew.list.Item
import network.tos.wallet.app.ui.screen.send.transaction.SendTransactionScreen
import network.tos.uikit.list.ListCell
import network.tos.wallet.api.API
import network.tos.wallet.data.account.AccountRepository
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.collectibles.CollectiblesRepository
import network.tos.wallet.data.collectibles.entities.DnsExpiringEntity
import network.tos.wallet.data.core.entity.SignRequestEntity
import network.tos.wallet.localization.Localization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DNSRenewViewModel(
    app: Application,
    private val wallet: WalletEntity,
    private val entities: List<DnsExpiringEntity>,
    private val collectiblesRepository: CollectiblesRepository,
    private val accountRepository: AccountRepository,
    private val api: API,
) : BaseWalletVM(app) {

    private val dnsExpiringFlow = flow {
        emit(collectiblesRepository.getDnsExpiring(wallet.accountId, wallet.testnet, 366))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, entities)

    val uiItemsFlow = dnsExpiringFlow.map {
        val items = collectiblesRepository.getDnsExpiring(wallet.accountId, wallet.testnet, 366)
        val uiItems = items.mapIndexed { index, dnsExpiringEntity ->
            Item(
                position = ListCell.getPosition(items.size, index),
                wallet = wallet,
                entity = dnsExpiringEntity
            )
        }
        uiItems
    }

    val showRenewAllButtonFlow = dnsExpiringFlow.map {
        if (wallet.isWatchOnly || it.isEmpty()) {
            false
        } else {
            wallet.maxMessages >= it.size
        }
    }

    fun renewAll(successCallback: () -> Unit) {
        viewModelScope.launch {
            if (wallet.isWatchOnly) {
                toast(Localization.sending_error)
                return@launch
            }
            val items = dnsExpiringFlow.value.filter { !it.inSale }
            if (items.isEmpty()) {
                openScreen(DNSOnSaleScreen.newInstance())
            } else {
                try {
                    val seqNo = accountRepository.getSeqno(wallet)
                    val signRequests = createSignRequests(items, seqNo)
                    if (sign(signRequests)) {
                        successCallback()
                    }
                } catch (_: Throwable) {
                    toast(Localization.sending_error)
                }
            }
        }
    }

    private suspend fun sign(signRequests: List<SignRequestEntity>): Boolean {
        for (signRequest in signRequests) {
            try {
                SendTransactionScreen.run(context, wallet, signRequest)
            } catch (e: Throwable) {
                return false
            }
        }
        return true
    }

    private suspend fun createSignRequests(
        entities: List<DnsExpiringEntity>,
        seqNo: Int,
    ): List<SignRequestEntity> {
        val now = currentTimeSeconds()
        val messages = entities.map { entity ->
            val dns = requireNotNull(entity.dnsItem) { "renewal item is missing" }
            val state = withContext(Dispatchers.IO) {
                api.tos.inspectDnsDomain(entity.name, wallet.testnet, now)
            }
            require(state.canonicalName == entity.name.lowercase()) { "DNS name changed" }
            require(state.itemAddress.equalsAddress(dns.address)) { "DNS item identity changed" }
            TosDnsTransactionBuilder.createMessage(
                state = state,
                walletAddress = wallet.address,
                action = TosDnsTransactionBuilder.Action.Renew(),
                now = now,
            )
        }
        return messages.chunked(wallet.maxMessages).mapIndexed { index, chunk ->
            SignRequestEntity.Builder()
                .setValidUntil(now + 10 * 60)
                .setTestnet(wallet.testnet)
                .setFrom(wallet.contract.address)
                .addMessages(chunk)
                .setSeqNo(seqNo + index)
                .build(Uri.EMPTY)
        }
    }

}
