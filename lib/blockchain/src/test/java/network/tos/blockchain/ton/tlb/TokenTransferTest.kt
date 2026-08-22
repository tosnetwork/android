package network.tos.blockchain.ton.tlb

import network.tos.blockchain.ton.TONOpCode
import network.tos.blockchain.ton.extensions.loadCoins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.ton.block.AddrStd
import org.ton.block.Coins
import org.ton.block.MsgAddressInt
import org.ton.cell.CellBuilder
import org.ton.tlb.storeTlb
import org.ton.tlb.loadTlb
import java.math.BigInteger

class TokenTransferTest {
    private val recipient = AddrStd.parse("0:${"11".repeat(32)}")
    private val response = AddrStd.parse("0:${"22".repeat(32)}")

    @Test
    fun jettonTransferMatchesTep74WireLayout() {
        val cell = CellBuilder.createCell {
            storeTlb(
                JettonTransfer.tlbCodec(),
                JettonTransfer(
                    queryId = 7,
                    coins = Coins.ofNano(123_456_789L),
                    toAddress = recipient,
                    responseAddress = response,
                    forwardAmount = Coins.ofNano(1L),
                    comment = "jetton transfer",
                )
            )
        }
        val slice = cell.beginParse()
        assertEquals(BigInteger.valueOf(TONOpCode.JETTON_TRANSFER.code), slice.loadUInt(32))
        assertEquals(BigInteger.valueOf(7), slice.loadUInt(64))
        assertEquals(Coins.ofNano(123_456_789L), slice.loadCoins())
        assertEquals(recipient, slice.loadTlb(MsgAddressInt))
        assertEquals(response, slice.loadTlb(MsgAddressInt))
        assertFalse("custom_payload must be absent", slice.loadBit())
    }

    @Test
    fun nftTransferMatchesTep62WireLayout() {
        val cell = CellBuilder.createCell {
            storeTlb(
                NftTransfer.tlbCodec(),
                NftTransfer(
                    queryId = 9,
                    newOwnerAddress = recipient,
                    excessesAddress = response,
                    forwardAmount = Coins.ofNano(1L),
                    comment = "nft transfer",
                )
            )
        }
        val slice = cell.beginParse()
        assertEquals(BigInteger.valueOf(TONOpCode.NFT_TRANSFER.code), slice.loadUInt(32))
        assertEquals(BigInteger.valueOf(9), slice.loadUInt(64))
        assertEquals(recipient, slice.loadTlb(MsgAddressInt))
        assertEquals(response, slice.loadTlb(MsgAddressInt))
        assertFalse("custom_payload must be absent", slice.loadBit())
    }
}
