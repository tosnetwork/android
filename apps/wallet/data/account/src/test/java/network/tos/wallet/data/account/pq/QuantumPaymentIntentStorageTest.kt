package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.QuantumPaymentIntent
import network.tos.blockchain.ton.contract.QuantumTokenTransferIntent
import org.junit.Assert.*
import org.junit.Test
import org.ton.block.AddrStd
import java.math.BigInteger

class QuantumPaymentIntentStorageTest {
    private fun address(byte: String) = AddrStd.parse("0:" + byte.repeat(32))
    private fun native() = QuantumPaymentIntent(address("33"), BigInteger.ONE, false)
    private fun token() = QuantumPaymentIntent(QuantumTokenTransferIntent(
        address("11"), address("22"),
        AddrStd.parse("0:5c2e7defab4c288bc81524d1594af7a14a984c4d789ac2acf28a4e8b589f9293"),
        address("33"), BigInteger.valueOf(1_000_000), 6, BigInteger.ONE,
        BigInteger.valueOf(100_000_000), BigInteger.ZERO,
    ))

    @Test fun validNativeAndTokenRecordsRoundTripExactly() {
        for (intent in listOf(native(), token())) {
            assertEquals(intent, QuantumPaymentOutbox.decodeIntent(QuantumPaymentOutbox.encodeIntent(intent)))
        }
    }

    @Test fun numericNativeAmountRemainsACorruptRecordRatherThanCallerInput() {
        val stored = QuantumPaymentOutbox.encodeIntent(native()).put("amount", 1.0)
        assertThrows(IllegalArgumentException::class.java) { QuantumPaymentIntent.decode(stored.toString()) }
        val error = assertThrows(IllegalStateException::class.java) { QuantumPaymentOutbox.decodeIntent(stored) }
        assertTrue(error.cause is IllegalArgumentException)
    }

    @Test fun numericTokenUnitsHaveTheSameDurableRecordErrorSemantics() {
        val stored = QuantumPaymentOutbox.encodeIntent(token())
        stored.getJSONObject("token_transfer").put("units", 1.0)
        assertThrows(IllegalArgumentException::class.java) { QuantumPaymentIntent.decode(stored.toString()) }
        val error = assertThrows(IllegalStateException::class.java) { QuantumPaymentOutbox.decodeIntent(stored) }
        assertTrue(error.cause is IllegalArgumentException)
        // Rejection must not normalize the corrupt payload into a different payment.
        assertTrue(stored.getJSONObject("token_transfer").get("units") is Number)
    }
}
