package network.tos.wallet.app.core.entities

import network.tos.blockchain.ton.contract.TosV5SigningRequest
import network.tos.blockchain.ton.contract.TosWalletV5R1Contract
import network.tos.blockchain.ton.contract.WalletVersion
import network.tos.blockchain.ton.extensions.asCellRef
import org.junit.Assert.*
import org.junit.Test
import org.ton.api.pub.PublicKeyEd25519
import org.ton.block.AddrStd
import org.ton.block.Coins
import org.ton.block.MessageRelaxed
import org.ton.block.StateInit
import org.ton.cell.buildCell
import org.ton.contract.wallet.MessageData
import org.ton.contract.wallet.WalletTransferBuilder
import org.ton.crypto.hex
import org.ton.tlb.constructor.AnyTlbConstructor
import org.ton.tlb.loadTlb
import org.ton.tlb.storeTlb

class TransferStateInitTest {
    private val sender = TosWalletV5R1Contract(PublicKeyEd25519(hex(
        "5754865e86d0ade1199301bbb0319a25ed6b129c4b0a57f28f62449b3df9c522",
    )), 3)

    @Test
    fun nativeFirstTransferDeploysSenderWithoutDeployingRecipient() {
        val comment = "First TOS transfer 🌌"
        val gift = WalletTransferBuilder().apply {
            destination = AddrStd.parse("0:${"22".repeat(32)}")
            coins = Coins.ofNano(10_000_000L)
            bounceable = false
            sendMode = 3
            messageData = MessageData.Raw(requireNotNull(asCellRef(comment)),
                TransferStateInit.forRecipient(WalletVersion.TOSV5R1, 0, sender.stateInitRef, null))
        }.build()
        val unsigned = sender.createTransferUnsignedBody(2_000_000_000L, 0, false, null, gift)
        val request = TosV5SigningRequest.parse(unsigned, 3, 0)
        val internal = request.messages.single().parse { loadTlb(MessageRelaxed.tlbCodec(AnyTlbConstructor)) }
        assertNull("Sender initialization leaked into the recipient message", internal.init.value)
        assertEquals(gift.destination, request.transfers.single().destination)
        assertEquals("10000000", request.transfers.single().coins.amount.toString())
        assertEquals(comment, request.transfers.single().comment)
        val external = sender.createTransferMessage(sender.address, 0, unsigned)
        val deployment = requireNotNull(external.init.value)
        val actualInit = deployment.x ?: requireNotNull(deployment.y).value
        assertEquals(
            buildCell { storeTlb(StateInit, sender.stateInitRef.value) }.hash(),
            buildCell { storeTlb(StateInit, actualInit) }.hash(),
        )
        assertNull(sender.createTransferMessage(sender.address, 1, unsigned).init.value)
    }

    @Test
    fun legacyFirstTransferAndExplicitRecipientInitializationRemainStable() {
        val recipient = TosWalletV5R1Contract(sender.publicKey, 4).stateInitRef
        assertSame(sender.stateInitRef,
            TransferStateInit.forRecipient(WalletVersion.V5R1, 0, sender.stateInitRef, null))
        assertNull(TransferStateInit.forRecipient(WalletVersion.V5R1, 1, sender.stateInitRef, null))
        for (version in listOf(WalletVersion.V5R1, WalletVersion.TOSV5R1)) {
            for (seqno in listOf(0, 1)) {
                assertSame(recipient, TransferStateInit.forRecipient(version, seqno, sender.stateInitRef, recipient))
            }
        }
    }
}
