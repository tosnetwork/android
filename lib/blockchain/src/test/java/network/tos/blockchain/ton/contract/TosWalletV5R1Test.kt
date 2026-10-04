package network.tos.blockchain.ton.contract

import network.tos.blockchain.ton.extensions.toAccountId
import network.tos.blockchain.ton.extensions.cellFromBase64
import network.tos.blockchain.ton.extensions.asCellRef
import org.junit.Assert.*
import org.junit.Test
import org.ton.api.pub.PublicKeyEd25519
import org.ton.block.AddrStd
import org.ton.block.Coins
import org.ton.block.CommonMsgInfoRelaxed
import org.ton.block.MessageRelaxed
import org.ton.cell.Cell
import org.ton.cell.buildCell
import org.ton.tlb.constructor.AnyTlbConstructor
import org.ton.tlb.loadTlb
import org.ton.tlb.storeTlb
import org.ton.contract.wallet.WalletTransferBuilder
import org.ton.crypto.hex

class TosWalletV5R1Test {
    @Test fun independentVmSuccessfulBlindSigningBodiesAreRejected() {
        val vectors = requireNotNull(javaClass.getResourceAsStream("/tos-signer-boundary-vectors.tsv"))
            .bufferedReader().use { it.readLines() }.filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(4, vectors.size)
        vectors.forEach { line ->
            val (name, boc) = line.split('\t')
            assertThrows("Dangerous message accepted: $name", Exception::class.java) {
                TosV5SigningRequest.parse(boc.cellFromBase64(), 3, 7)
            }
        }
    }
    @Test fun ordinaryEmptyAndCompleteUnicodeCommentAreFullyDecoded() {
        val plain = TosV5SigningRequest.parse(body(), 3, 7)
        assertEquals(null, plain.transfers.single().comment)
        assertEquals("123456789", plain.transfers.single().coins.amount.toString())
        val comment = "TOS 安全 🌌".repeat(30)
        val transfer = WalletTransferBuilder().apply {
            destination = AddrStd.parse("0:${"11".repeat(32)}")
            coins = Coins.ofNano(1L)
            sendMode = 3
            messageData = org.ton.contract.wallet.MessageData.Raw(requireNotNull(asCellRef(comment)), null)
        }.build()
        val unsigned = TosWalletV5R1Contract(key, 3).createTransferUnsignedBody(2000000000L, 7, false, null, transfer)
        assertEquals(comment, TosV5SigningRequest.parse(unsigned, 3, 7).transfers.single().comment)
    }
    @Test fun hiddenHeadersAndTrailingMessageOrRequestDataAreRejected() {
        val original = TosV5SigningRequest.parse(body(), 3, 7).messages.single()
        val message = original.parse { loadTlb(MessageRelaxed.tlbCodec(AnyTlbConstructor)) }
        val info = message.info as CommonMsgInfoRelaxed.IntMsgInfoRelaxed
        val messages = listOf(
            info.copy(bounced = true), info.copy(ihrDisabled = false),
            info.copy(src = AddrStd.parse("0:${"22".repeat(32)}")),
            info.copy(fwdFee = Coins.ofNano(1L)), info.copy(ihrFee = Coins.ofNano(1L)),
            info.copy(createdLt = 1uL), info.copy(createdAt = 1u),
        ).map { header -> buildCell { storeTlb(MessageRelaxed.tlbCodec(AnyTlbConstructor), message.copy(info = header)) } } +
            listOf(buildCell { storeSlice(original.beginParse()); storeBit(true) },
                buildCell { storeSlice(original.beginParse()); storeRef(Cell.empty()) })
        for (outgoing in messages) {
            val actions = buildCell {
                storeRef(Cell.empty()); storeUInt(0x0ec3c86d, 32); storeUInt(3, 8); storeRef(outgoing)
            }
            val request = buildCell {
                storeUInt(0x7369676e, 32); storeInt(3, 32); storeUInt(0, 32)
                storeUInt(2000000000L, 32); storeUInt(7, 32); storeBit(true); storeRef(actions); storeBit(false)
            }
            assertThrows(Exception::class.java) { TosV5SigningRequest.parse(request, 3, 7) }
        }
        for (request in listOf(buildCell { storeSlice(body().beginParse()); storeBit(true) },
            buildCell { storeSlice(body().beginParse()); storeRef(Cell.empty()) })) {
            assertThrows(Exception::class.java) { TosV5SigningRequest.parse(request, 3, 7) }
        }
    }
    private val key = PublicKeyEd25519(hex("5754865e86d0ade1199301bbb0319a25ed6b129c4b0a57f28f62449b3df9c522"))
    private val gift = WalletTransferBuilder().apply {
        destination = AddrStd.parse("0:${"11".repeat(32)}")
        coins = Coins.ofNano(123456789L)
        sendMode = 3
        bounceable = true
    }.build()
    private fun body(network: Int = 3, seqno: Int = 7, expiry: Long = 2000000000L) =
        TosWalletV5R1Contract(key, network).createTransferUnsignedBody(expiry, seqno, false, null, gift)

    @Test fun reconciliationUsesTheSignedNonceAndRejectsOtherWalletOrNetworkHeaders() {
        val wallet = TosWalletV5R1Contract(key, 3)
        fun signed(unsigned: Cell) = wallet.signedBody(org.ton.bitstring.BitString(ByteArray(64)), unsigned)
        val message = wallet.createTransferMessageCell(wallet.address, 7, signed(body()))
        assertEquals(7, wallet.signedTransferSeqno(message))
        assertThrows(IllegalArgumentException::class.java) {
            TosWalletV5R1Contract(key, 4).signedTransferSeqno(message)
        }
        assertThrows(IllegalArgumentException::class.java) {
            wallet.signedTransferSeqno(wallet.createTransferMessageCell(
                AddrStd.parse("0:${"22".repeat(32)}"), 7, signed(body())))
        }
        assertThrows(IllegalArgumentException::class.java) {
            wallet.signedTransferSeqno(wallet.createTransferMessageCell(wallet.address, 7, body()))
        }
        val overflow = buildCell {
            storeUInt(0x7369676e, 32); storeInt(3, 32); storeUInt(0, 32)
            storeUInt(2000000000L, 32); storeUInt(0xffffffffL, 32)
            storeBit(false); storeBit(false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            wallet.signedTransferSeqno(wallet.createTransferMessageCell(wallet.address, 7, signed(overflow)))
        }
    }

    @Test fun matchesIndependentPythonAndTosSdkVector() {
        val wallet = TosWalletV5R1Contract(key, 3)
        assertEquals("086a86aa9913c0ec52277adbb7e4b5695964dbb8c817ad0c305cdd345bbfac69", hex(wallet.getCode().hash().toByteArray()))
        // Shared vector with iOS; generated independently of this Kotlin implementation.
        assertEquals("0:a0436d4b7c36efb6a949a1f1ba08cda9ac9fc08b77bbae81ddaab20051648ee6", wallet.address.toAccountId())
        assertEquals("75746f815ed146820318296d5ae0822c5fc0b82e6e1ec8b7dd621d81fd70dc0a", hex(body().hash().toByteArray()))
    }
    @Test fun legacyWalletVersionAndAddressArePreserved() {
        val legacy = BaseWalletContract.create(key, "v5r1", -239)
        assertEquals(WalletVersion.V5R1, legacy.getWalletVersion())
        assertNotEquals(legacy.address, TosWalletV5R1Contract(key, 3).address)
        assertEquals(WalletVersion.V5R1, walletVersion(5))
        assertEquals(WalletVersion.TOSV5R1, walletVersion(6))
        assertEquals(WalletVersion.TOSV5R1, BaseWalletContract.resolveVersion(key, TosWalletV5R1Contract(key, 3).address.toAccountId(), 3))
    }
    @Test fun networkSeparatesSignaturesWithoutChangingPlainSubwalletAddress() {
        assertEquals(TosWalletV5R1Contract(key, 3).address, TosWalletV5R1Contract(key, 4).address)
        assertNotEquals(body(3).hash(), body(4).hash())
        assertThrows(IllegalArgumentException::class.java) { TosV5SigningRequest.parse(body(3), 4, 7, 1) }
    }
    @Test fun expiryAppliesToFirstDeploymentAndSignerRejectsReplayMetadata() {
        assertEquals(2000000000L, TosV5SigningRequest.parse(body(seqno = 0), 3, 0, 1).validUntil)
        assertThrows(IllegalArgumentException::class.java) { TosV5SigningRequest.parse(body(), 3, 8, 1) }
        assertThrows(IllegalArgumentException::class.java) { TosV5SigningRequest.parse(body(), 3, 7, 2000000000L) }
        assertThrows(IllegalArgumentException::class.java) { TosWalletV5R1Contract(key, 3).createTransferUnsignedBody(1, 0, false, null) }
        assertThrows(IllegalArgumentException::class.java) { body(expiry = 0) }
    }
    @Test fun offlineSignerRejectsHiddenSendAllOrCarryModes() {
        for (mode in listOf(64, 66, 128, 130, 131, 160)) {
            val request = TosWalletV5R1Contract(key, 3).createTransferUnsignedBody(
                2000000000L, 7, false, null, gift.copy(sendMode = mode))
            assertThrows(IllegalArgumentException::class.java) { TosV5SigningRequest.parse(request, 3, 7, 1) }
        }
    }
    @Test fun signingNetworkNeverSilentlyMapsUnknownInputToTestnet() {
        assertEquals(3, SigningNetworkId.parse("3", true))
        assertThrows(IllegalStateException::class.java) { SigningNetworkId.parse(null, true) }
        assertThrows(IllegalStateException::class.java) { SigningNetworkId.parse("ton", false) }
        assertThrows(IllegalStateException::class.java) { SigningNetworkId.parse("2147483648", true) }
    }
}
