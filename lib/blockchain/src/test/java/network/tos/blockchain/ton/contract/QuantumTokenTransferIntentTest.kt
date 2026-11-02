package network.tos.blockchain.ton.contract

import java.math.BigInteger
import kotlinx.serialization.json.*
import network.tos.blockchain.ton.tlb.JettonTransfer
import org.junit.Assert.*
import org.junit.Test
import org.ton.block.AddrStd
import org.ton.block.Coins
import org.ton.boc.BagOfCells
import org.ton.cell.buildCell
import org.ton.tlb.storeTlb

class QuantumTokenTransferIntentTest {
    private val owner = AddrStd.parse("0:" + "11".repeat(32))
    private val master = AddrStd.parse("0:" + "22".repeat(32))
    private val recipient = AddrStd.parse("0:" + "33".repeat(32))
    private val wallet = TokenWalletState.address(owner, master, TokenContractCodes.nativeWallet)
    private fun intent() = QuantumTokenTransferIntent(owner, master, wallet, recipient, BigInteger("9007199254740993"),
        6, BigInteger.ONE.shiftLeft(64) - BigInteger.ONE, BigInteger("100000000"))
    private fun fixture(name: String) = BagOfCells(checkNotNull(javaClass.classLoader!!.getResourceAsStream(name)).use { it.readBytes() }).roots.single()

    @Test fun bodyEnvelopeAndActionsMatchIndependentFiftAtUnsigned64Maximum() {
        val value = intent()
        assertEquals(fixture("token-transfer-intent-body.boc").hash(), value.body.hash())
        assertEquals(fixture("token-transfer-intent-message.boc").hash(), value.message().hash())
        assertEquals(fixture("token-transfer-intent-actions.boc").hash(), value.actions().hash())
        TosQuantumAuth.validateActions(value.actions())
        assertThrows(Exception::class.java) { TosQuantumPaymentReview.parse(value.actions()) }
    }

    @Test fun standardBodyReusesTheExistingJettonWireLayout() {
        val value = intent().copy(units = BigInteger.valueOf(123456789), queryId = BigInteger.valueOf(7), forwardTos = BigInteger.ONE)
        val legacy = buildCell { storeTlb(JettonTransfer.tlbCodec(), JettonTransfer(7, Coins.ofNano(123456789L), recipient, owner, Coins.ofNano(1L), null)) }
        assertEquals(legacy.hash(), value.body.hash())
    }

    @Test fun encodingRetainsExactUnitsAndRejectsNoncanonicalRestoredFields() {
        val maximum = intent().copy(units = BigInteger.ONE.shiftLeft(120) - BigInteger.ONE, decimals = 255)
        assertEquals(maximum, QuantumTokenTransferIntent.decode(maximum.encode()))
        val fields = Json.parseToJsonElement(intent().encode()).jsonObject
        for ((key, bad) in listOf("version" to JsonPrimitive(2), "units" to JsonPrimitive(1),
            "units" to JsonPrimitive("01"), "queryId" to JsonPrimitive("18446744073709551616"),
            "attachedTos" to JsonPrimitive("0"), "decimals" to JsonPrimitive(256), "wallet" to JsonPrimitive("0:" + "44".repeat(32)))) {
            val changed = JsonObject(fields.toMutableMap().apply { put(key, bad) })
            assertThrows(Exception::class.java) { QuantumTokenTransferIntent.decode(changed.toString()) }
        }
        assertThrows(Exception::class.java) { QuantumTokenTransferIntent.decode(" ".repeat(4097)) }
    }

    @Test fun signedActionFieldsChangeWhileReviewPrecisionRemainsExplicit() {
        val base = intent()
        for (changed in listOf(base.copy(recipient = owner), base.copy(units = base.units + BigInteger.ONE),
            base.copy(queryId = BigInteger.ZERO), base.copy(attachedTos = base.attachedTos + BigInteger.ONE),
            base.copy(forwardTos = BigInteger.ONE))) {
            assertNotEquals(base.actions().hash(), changed.actions().hash())
        }
        val precision = base.copy(decimals = 9)
        assertNotEquals(base, precision)
        assertEquals(base.actions().hash(), precision.actions().hash()) // metadata precision is not a protocol field
    }

    @Test fun invalidBindingAmountsWorkchainsAndForwardBudgetAreRejected() {
        val base = intent()
        for (invalid in listOf<() -> Unit>(
            { base.copy(wallet = recipient) }, { base.copy(master = recipient) },
            { base.copy(units = BigInteger.ZERO) }, { base.copy(units = BigInteger.ONE.shiftLeft(120)) },
            { base.copy(queryId = BigInteger.ONE.shiftLeft(64)) }, { base.copy(queryId = BigInteger.valueOf(-1)) },
            { base.copy(attachedTos = BigInteger.ZERO) }, { base.copy(forwardTos = base.attachedTos) },
            { base.copy(forwardTos = BigInteger.valueOf(-1)) }, { base.copy(decimals = -1) },
            { base.copy(recipient = AddrStd.parse("-1:" + "33".repeat(32))) }
        )) assertThrows(Exception::class.java) { invalid() }
    }

    @Test fun paymentJournalCodecKeepsTokenDescriptorAndRejectsConflictingOuterFields() {
        val token = intent()
        val payment = QuantumPaymentIntent(token)
        assertEquals(token.actions().hash(), payment.actions().hash())
        val restored = QuantumPaymentIntent.decode(payment.encode())
        assertEquals(payment, restored); assertEquals(token, restored.tokenTransfer)
        val fields = Json.parseToJsonElement(payment.encode()).jsonObject
        for ((key, bad) in listOf("amount" to JsonPrimitive("1"), "bounce" to JsonPrimitive(false),
            "recipient" to JsonPrimitive("0:" + "11".repeat(32)), "comment" to JsonPrimitive("hidden body"), "token_transfer" to JsonNull)) {
            assertThrows(Exception::class.java) { QuantumPaymentIntent.decode(JsonObject(fields.toMutableMap().apply { put(key, bad) }).toString()) }
        }
        val oldNative = """{"recipient":"0:${"33".repeat(32)}","amount":"10","bounce":false,"comment":null}"""
        val native = QuantumPaymentIntent.decode(oldNative)
        assertNull(native.tokenTransfer)
        assertEquals(QuantumPaymentIntent(recipient, BigInteger.TEN, false), native)
        assertEquals(native, QuantumPaymentIntent.decode(native.encode()))
    }
}
