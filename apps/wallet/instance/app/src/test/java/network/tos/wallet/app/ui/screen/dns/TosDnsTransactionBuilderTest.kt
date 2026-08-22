package network.tos.wallet.app.ui.screen.dns

import network.tos.blockchain.ton.dns.TosDnsOperation
import network.tos.wallet.api.tos.TosBlockId
import network.tos.wallet.api.tos.TosDnsDomainState
import network.tos.wallet.api.tos.TosDnsLifecycle
import network.tos.wallet.data.account.Wallet
import network.tos.wallet.data.account.entities.WalletEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger

class TosDnsTransactionBuilderTest {
    private val owner = "0:" + "11".repeat(32)
    private val collection = "0:" + "22".repeat(32)
    private val item = "0:" + "33".repeat(32)
    private val checkpoint = TosBlockId("tos.blockIdExt", -1, "-9223372036854775808", 7, "r", "f")

    @Test
    fun registrationTargetsCollectionAndEnforcesLaunchPrice() {
        val now = TosDnsOperation.AUCTION_START_TIME + 1
        val state = state(TosDnsLifecycle.AVAILABLE)
        val minimum = TosDnsOperation.minimumPrice(5, now)
        val message = TosDnsTransactionBuilder.createMessage(
            state, owner, TosDnsTransactionBuilder.Action.Register(minimum), now, BigInteger.ZERO,
        )
        assertEquals(collection, message.addressValue)
        assertEquals(minimum, message.amount)
        assertEquals(BigInteger.ZERO, message.getPayload().beginParse().loadUInt(32))
        assertThrows(IllegalArgumentException::class.java) {
            TosDnsTransactionBuilder.createMessage(
                state, owner, TosDnsTransactionBuilder.Action.Register(minimum - BigInteger.ONE), now,
            )
        }
    }

    @Test
    fun bidTargetsItemAndEnforcesExact105PercentFloor() {
        val state = state(TosDnsLifecycle.AUCTION, maximumBid = BigInteger("100000000001"))
        val minimum = TosDnsOperation.minimumNextBid(state.maximumBid)
        val message = TosDnsTransactionBuilder.createMessage(
            state, owner, TosDnsTransactionBuilder.Action.Bid(minimum), queryId = BigInteger.ZERO,
        )
        assertEquals(item, message.addressValue)
        assertEquals(minimum, message.amount)
        assertEquals(0, message.getPayload().bits.size)
        assertThrows(IllegalArgumentException::class.java) {
            TosDnsTransactionBuilder.createMessage(state, owner, TosDnsTransactionBuilder.Action.Bid(minimum - BigInteger.ONE))
        }
    }

    @Test
    fun lifecycleAndOwnerChecksFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            TosDnsTransactionBuilder.createMessage(
                state(TosDnsLifecycle.AUCTION_ENDED), owner,
                TosDnsTransactionBuilder.Action.Renew(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TosDnsTransactionBuilder.createMessage(
                state(TosDnsLifecycle.LEASED, ownerAddress = "0:" + "44".repeat(32)), owner,
                TosDnsTransactionBuilder.Action.ChangeRecord(BigInteger.ZERO, null),
            )
        }
        val finish = TosDnsTransactionBuilder.createMessage(
            state(TosDnsLifecycle.AUCTION_ENDED), owner,
            TosDnsTransactionBuilder.Action.FinishAuction, queryId = BigInteger.valueOf(7),
        )
        assertEquals(BigInteger.valueOf(0x2fcb26a2), finish.getPayload().beginParse().loadUInt(32))
    }

    @Test
    fun renewReleaseAndRecordOperationsUseCanonicalItem() {
        val renew = TosDnsTransactionBuilder.createMessage(
            state(TosDnsLifecycle.LEASED), owner,
            TosDnsTransactionBuilder.Action.Renew(),
        )
        assertEquals(item, renew.addressValue)
        assertEquals(TosDnsOperation.ONE_TOS, renew.amount)
        assertEquals(0, renew.getPayload().bits.size)

        val now = TosDnsOperation.AUCTION_START_TIME + 1
        val releaseMinimum = TosDnsOperation.minimumPrice(5, now)
        val release = TosDnsTransactionBuilder.createMessage(
            state(TosDnsLifecycle.RELEASABLE), owner,
            TosDnsTransactionBuilder.Action.Release(releaseMinimum), now, BigInteger.valueOf(9),
        )
        assertEquals(item, release.addressValue)
        assertEquals(BigInteger.valueOf(0x4ed14b65), release.getPayload().beginParse().loadUInt(32))

        val record = TosDnsTransactionBuilder.createMessage(
            state(TosDnsLifecycle.LEASED), owner,
            TosDnsTransactionBuilder.Action.ChangeRecord(BigInteger.valueOf(42), null),
            queryId = BigInteger.valueOf(11),
        )
        assertEquals(item, record.addressValue)
        assertEquals(BigInteger.valueOf(0x4eb1f0f9), record.getPayload().beginParse().loadUInt(32))
    }

    @Test
    fun watchOnlyWalletCannotCreateDnsSignRequest() {
        val wallet = WalletEntity.EMPTY.copy(type = Wallet.Type.Watch)
        val now = TosDnsOperation.AUCTION_START_TIME + 1
        val minimum = TosDnsOperation.minimumPrice(5, now)
        assertThrows(IllegalArgumentException::class.java) {
            TosDnsTransactionBuilder.createSignRequest(
                wallet,
                state(TosDnsLifecycle.AVAILABLE),
                TosDnsTransactionBuilder.Action.Register(minimum),
                now = now,
            )
        }
    }

    private fun state(
        lifecycle: TosDnsLifecycle,
        maximumBid: BigInteger = BigInteger.ZERO,
        ownerAddress: String? = owner,
    ) = TosDnsDomainState(
        canonicalName = "alice.tos",
        label = "alice",
        rootAddress = "-1:" + "00".repeat(32),
        collectionAddress = collection,
        itemAddress = item,
        lifecycle = lifecycle,
        ownerAddress = ownerAddress,
        maximumBidAddress = null,
        maximumBid = maximumBid,
        auctionEndTime = 0,
        lastFillUpTime = 1_000,
        renewalDeadline = 31_623_400,
        checkpoint = checkpoint,
        observedAt = 1_000,
    )
}
