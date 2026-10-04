package network.tos.wallet.app.core.entities

import network.tos.blockchain.ton.TonSendMode
import network.tos.blockchain.ton.contract.TosWalletV5R1Contract
import network.tos.icu.Coins
import org.junit.Assert.*
import org.junit.Test
import org.ton.api.pub.PublicKeyEd25519
import org.ton.block.AddrStd
import org.ton.block.CommonMsgInfoRelaxed
import org.ton.block.MessageRelaxed
import org.ton.contract.wallet.WalletTransferBuilder
import org.ton.crypto.hex
import org.ton.tlb.constructor.AnyTlbConstructor
import org.ton.tlb.loadTlb

class TransferAmountTest {
    private val balance = Coins.ofNano("101984546470")
    private val wallet = TosWalletV5R1Contract(PublicKeyEd25519(hex(
        "5754865e86d0ade1199301bbb0319a25ed6b129c4b0a57f28f62449b3df9c522",
    )), 3)

    @Test
    fun nativeMaxSurvivesDifferentDecimalScalesAndSerializesCarryAll() {
        // Actual failure: node balance 101.984546470 becomes 101.98454647
        // after MAX is formatted and read back from the amount field.
        val displayedAmount = Coins.of("101.98454647")
        assertNotEquals(balance, displayedAmount)
        assertNotEquals(balance.value.scale(), displayedAmount.value.scale())
        assertEquals(balance.toBigInteger(), displayedAmount.toBigInteger())
        val max = isNativeMaxAmount(displayedAmount, balance)
        assertTrue("Numerically identical MAX amounts must retain carry-all", max)
        assertSerializedMode(displayedAmount, max, 130)
    }

    @Test
    fun oneNanoLessAndNonNativeMaxKeepOrdinarySeparateGasMode() {
        val amount = Coins.ofNano("101984546469")
        val max = isNativeMaxAmount(amount, balance)
        assertFalse("MAX comparison must not round away one nano", max)
        assertSerializedMode(amount, max, 3)
        assertEquals("A token MAX must not carry the native balance", 3,
            transferSendMode(max = true, isTon = false))
    }

    private fun assertSerializedMode(amount: Coins, max: Boolean, expected: Int) {
        // Use the mode policy consumed by TransferEntity and decode the actual
        // TOS V5 action. The restricted offline-signer parser intentionally
        // rejects carry-all, so it is not the Wallet MAX decoder.
        val gift = WalletTransferBuilder().apply {
            destination = AddrStd.parse("0:${"22".repeat(32)}")
            coins = org.ton.block.Coins.ofNano(amount.toBigInteger())
            bounceable = false
            sendMode = transferSendMode(max, isTon = true)
        }.build()
        val unsigned = wallet.createTransferUnsignedBody(2_000_000_000L, 7, false, null, gift)
        val action = unsigned.refs.single().beginParse()
        assertEquals(0x0ec3c86dL, action.loadUInt(32).toLong())
        val actualMode = action.loadUInt(8).toInt()
        assertEquals(expected, actualMode)
        assertEquals(max, actualMode and TonSendMode.CARRY_ALL_REMAINING_BALANCE.value != 0)
        assertEquals(!max, actualMode and TonSendMode.PAY_GAS_SEPARATELY.value != 0)
        val message = unsigned.refs.single().refs.last().parse {
            loadTlb(MessageRelaxed.tlbCodec(AnyTlbConstructor))
        }
        val info = message.info as CommonMsgInfoRelaxed.IntMsgInfoRelaxed
        assertEquals(gift.destination, info.dest)
        assertEquals(amount.toBigInteger(), info.value.coins.amount.value)
        assertFalse(info.bounced)
        assertNull(message.init.value)
    }
}
