package network.tos.wallet.app.core.entities

import network.tos.blockchain.ton.contract.TosV5SigningRequest
import network.tos.blockchain.ton.contract.TosWalletV5R1Contract
import network.tos.blockchain.ton.contract.BaseWalletContract
import network.tos.blockchain.ton.contract.WalletVersion
import network.tos.blockchain.ton.extensions.asCellRef
import network.tos.blockchain.ton.extensions.bodyCell
import org.junit.Assert.*
import org.junit.Test
import org.ton.api.pub.PublicKeyEd25519
import org.ton.bitstring.BitString
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
    fun approvedVersionsNeverSubstituteSenderForRecipientAndPreserveExplicitInitialization() {
        val recipient = TosWalletV5R1Contract(sender.publicKey, 4).stateInitRef
        for (version in listOf(WalletVersion.V3R1, WalletVersion.V3R2, WalletVersion.V4R1,
            WalletVersion.V4R2, WalletVersion.V5R1, WalletVersion.TOSV5R1)) {
            for (seqno in listOf(0, 1)) {
                assertNull(TransferStateInit.forRecipient(version, seqno, sender.stateInitRef, null))
                assertSame(recipient, TransferStateInit.forRecipient(version, seqno, sender.stateInitRef, recipient))
            }
        }
        // The experimental Beta profile is outside this ordinary-wallet repair.
        assertSame(sender.stateInitRef,
            TransferStateInit.forRecipient(WalletVersion.V5BETA, 0, sender.stateInitRef, null))
    }

    @Test
    fun everyOrdinaryLegacyEnvelopeDeploysOnlyTheSenderAndKeepsRequestedRecipientInitialization() {
        val recipientInit = TosWalletV5R1Contract(sender.publicKey, 4).stateInitRef
        for (version in listOf("v3r1", "v3r2", "v4r1", "v4r2", "v5r1")) {
            val wallet = BaseWalletContract.create(sender.publicKey, version, -239)
            for (seqno in listOf(0, 1)) for (requested in listOf(null, recipientInit)) {
                val gift = WalletTransferBuilder().apply {
                    destination = AddrStd.parse("0:${"22".repeat(32)}")
                    coins = Coins.ofNano(10_000_000L)
                    bounceable = false; sendMode = 3
                    messageData = MessageData.Raw(requireNotNull(asCellRef("Legacy first transfer 🌌")),
                        TransferStateInit.forRecipient(wallet.getWalletVersion(), seqno, wallet.stateInitRef, requested))
                }.build()
                val unsigned = wallet.createTransferUnsignedBody(2_000_000_000L, seqno, false, null, gift)
                val signed = wallet.signedBody(BitString(ByteArray(64)), unsigned)
                // Decode the actual serialized external envelope and its outgoing
                // action, so an external-only fix cannot hide an internal leak.
                val external = wallet.parseTransferMessageCell(wallet.createTransferMessageCell(wallet.address, seqno, signed))
                if (seqno == 0) {
                    val init = requireNotNull(external.init.value)
                    val actual = init.x ?: requireNotNull(init.y).value
                    assertEquals(wallet.stateInitRef.hash(), buildCell { storeTlb(StateInit, actual) }.hash())
                } else assertNull(external.init.value)
                val request = external.bodyCell
                val internalCell = if (wallet.getWalletVersion() == WalletVersion.V5R1)
                    request.refs.single().refs.last() else request.refs.single()
                val internal = internalCell.parse { loadTlb(MessageRelaxed.tlbCodec(AnyTlbConstructor)) }
                if (requested == null) assertNull("$version leaked sender StateInit", internal.init.value)
                else {
                    val init = requireNotNull(internal.init.value)
                    // ton-kotlin 0.3.1's MessageRelaxed reference loader wraps
                    // StateInit twice. Decode its original stored reference
                    // directly, rather than treating the code ref as StateInit.
                    val serialized = init.x?.let { buildCell { storeTlb(StateInit, it) } }
                        ?: requireNotNull(init.y).toCell(StateInit)
                    val actual = serialized.parse { loadTlb(StateInit) }
                    assertEquals("$version seq$seqno changed explicit recipient bytes", requested.hash(), serialized.hash())
                    assertEquals("$version seq$seqno changed decoded recipient state", requested.hash(),
                        buildCell { storeTlb(StateInit, actual) }.hash())
                    assertEquals(requested.value.code.value!!.hash(), actual.code.value!!.hash())
                    assertEquals(requested.value.data.value!!.hash(), actual.data.value!!.hash())
                }
            }
        }
    }
}
