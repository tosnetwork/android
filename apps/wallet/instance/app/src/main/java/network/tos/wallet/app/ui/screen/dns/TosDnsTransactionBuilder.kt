package network.tos.wallet.app.ui.screen.dns

import android.net.Uri
import network.tos.blockchain.ton.dns.TosDnsOperation
import network.tos.blockchain.ton.extensions.equalsAddress
import network.tos.extensions.currentTimeSeconds
import network.tos.wallet.app.core.entities.TransferEntity
import network.tos.wallet.api.tos.TosDnsDomainState
import network.tos.wallet.api.tos.TosDnsLifecycle
import network.tos.wallet.data.account.entities.WalletEntity
import network.tos.wallet.data.core.entity.RawMessageEntity
import network.tos.wallet.data.core.entity.SignRequestEntity
import org.ton.cell.Cell
import java.math.BigInteger

/**
 * Converts a freshly inspected, checkpoint-bound Domain state into one wallet
 * request. State/target/amount checks are centralized here so UI entry points
 * cannot accidentally send a registration to an Item or a bid to a Collection.
 */
object TosDnsTransactionBuilder {
    private val RENEWAL_VALUE = TosDnsOperation.ONE_TOS

    sealed interface Action {
        data class Register(val bid: BigInteger) : Action
        data class Bid(val bid: BigInteger) : Action
        data object FinishAuction : Action
        data class Renew(val amount: BigInteger = BigInteger("1000000000")) : Action
        data class Release(val bid: BigInteger) : Action
        data class ChangeRecord(val category: BigInteger, val value: Cell?) : Action
    }

    fun createMessage(
        state: TosDnsDomainState,
        walletAddress: String,
        action: Action,
        now: Long = currentTimeSeconds(),
        queryId: BigInteger = TransferEntity.newWalletQueryId(),
    ): RawMessageEntity {
        val (target, amount, body) = when (action) {
            is Action.Register -> {
                require(state.lifecycle == TosDnsLifecycle.AVAILABLE) { "domain is already registered" }
                require(now > TosDnsOperation.AUCTION_START_TIME) { "DNS registration has not launched" }
                val minimum = TosDnsOperation.minimumPrice(state.label.encodeToByteArray().size, now)
                require(action.bid >= minimum) { "registration bid is below the on-chain minimum" }
                Triple(state.collectionAddress, action.bid, TosDnsOperation.registerBody(state.label))
            }
            is Action.Bid -> {
                require(state.lifecycle == TosDnsLifecycle.AUCTION) { "domain has no active auction" }
                val minimum = TosDnsOperation.minimumNextBid(state.maximumBid)
                require(action.bid >= minimum) { "bid is below the 105% replacement threshold" }
                Triple(state.itemAddress, action.bid, TosDnsOperation.bidOrTopUpBody())
            }
            Action.FinishAuction -> {
                require(state.lifecycle == TosDnsLifecycle.AUCTION_ENDED) { "auction is not ready to finalize" }
                Triple(state.itemAddress, TosDnsOperation.CONTRACT_ACTION_VALUE, TosDnsOperation.finishAuctionBody(queryId))
            }
            is Action.Renew -> {
                require(state.lifecycle == TosDnsLifecycle.LEASED) { "only an active lease can be renewed" }
                require(state.ownerAddress?.equalsAddress(walletAddress) == true) { "only the Domain owner can renew" }
                require(action.amount >= RENEWAL_VALUE) { "renewal must attach at least 1 TOS" }
                Triple(state.itemAddress, action.amount, TosDnsOperation.bidOrTopUpBody())
            }
            is Action.Release -> {
                require(state.lifecycle == TosDnsLifecycle.RELEASABLE) { "domain is not releasable" }
                val minimum = TosDnsOperation.minimumPrice(state.label.encodeToByteArray().size, now)
                require(action.bid >= minimum) { "release bid is below the on-chain minimum" }
                Triple(state.itemAddress, action.bid, TosDnsOperation.releaseBody(queryId))
            }
            is Action.ChangeRecord -> {
                require(state.lifecycle == TosDnsLifecycle.LEASED) { "records cannot change during auction or expiry" }
                require(state.ownerAddress?.equalsAddress(walletAddress) == true) { "only the Domain owner can change records" }
                Triple(
                    state.itemAddress,
                    TosDnsOperation.CONTRACT_ACTION_VALUE,
                    TosDnsOperation.changeRecordBody(action.category, action.value, queryId),
                )
            }
        }
        return RawMessageEntity.of(amount = amount, address = target, payload = body)
    }

    fun createSignRequest(
        wallet: WalletEntity,
        state: TosDnsDomainState,
        action: Action,
        seqNo: Int? = null,
        now: Long = currentTimeSeconds(),
    ): SignRequestEntity = SignRequestEntity.Builder()
        .setValidUntil(now + 10 * 60)
        .setTestnet(wallet.testnet)
        .setFrom(wallet.contract.address)
        .apply { if (seqNo != null) setSeqNo(seqNo) }
        .addMessage(createMessage(state, wallet.address, action, now))
        .build(Uri.EMPTY)
}
