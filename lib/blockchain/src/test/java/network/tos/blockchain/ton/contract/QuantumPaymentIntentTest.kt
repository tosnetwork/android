package network.tos.blockchain.ton.contract

import org.junit.Assert.*
import org.junit.Test
import org.ton.block.AddrStd
import java.math.BigInteger

class QuantumPaymentIntentTest {
    private val recipient = AddrStd.parse("0:" + "12".repeat(32))
    @Test fun amountsStayExactAndRejectAmbiguousForms() {
        assertEquals(BigInteger.ONE, QuantumPaymentIntent.parseTos("0.000000001"))
        assertEquals(BigInteger("9007199254740993000000001"), QuantumPaymentIntent.parseTos("9007199254740993.000000001"))
        val maximum = BigInteger.ONE.shiftLeft(120) - BigInteger.ONE
        val split = maximum.divideAndRemainder(BigInteger.TEN.pow(9))
        assertEquals(maximum, QuantumPaymentIntent.parseTos("${split[0]}.${split[1].toString().padStart(9, '0')}"))
        for (text in listOf("0", "-1", "+1", "01", "1.", ".1", "1e3", "1,000", " 1", "1.0000000001", "1329227995784915872903807061")) {
            assertThrows(text, IllegalArgumentException::class.java) { QuantumPaymentIntent.parseTos(text) }
        }
    }
    @Test fun actionHashBindsEveryConfirmedFieldAndUsesUtf8ByteLimits() {
        val base = QuantumPaymentIntent(recipient, BigInteger.TEN, false, "hello")
        val different = listOf(
            QuantumPaymentIntent(AddrStd.parse("0:" + "13".repeat(32)), BigInteger.TEN, false, "hello"),
            QuantumPaymentIntent(recipient, BigInteger.valueOf(11), false, "hello"),
            QuantumPaymentIntent(recipient, BigInteger.TEN, true, "hello"),
            QuantumPaymentIntent(recipient, BigInteger.TEN, false, "Hello"))
        different.forEach { assertNotEquals(base.actions().hash(), it.actions().hash()) }
        assertEquals(QuantumPaymentIntent(recipient, BigInteger.TEN, false).body.hash(),
            QuantumPaymentIntent(recipient, BigInteger.TEN, false, "").body.hash())
        QuantumPaymentIntent(recipient, BigInteger.TEN, false, "界".repeat(1365)).body
        assertThrows(IllegalArgumentException::class.java) {
            QuantumPaymentIntent(recipient, BigInteger.TEN, false, "界".repeat(1366))
        }
    }
}
