package network.tos.blockchain.ton.contract

import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class TokenAmountTest {
    @Test fun exactUnitsRoundTripAcrossSupportedPrecision() {
        val maximum = BigInteger.ONE.shiftLeft(120) - BigInteger.ONE
        for (decimals in listOf(0, 6, 9, 18, 255)) {
            for (units in listOf(BigInteger.ONE, BigInteger("9007199254740993"), maximum)) {
                assertEquals(units, TokenAmount.parse(TokenAmount.format(units, decimals), decimals))
            }
            assertEquals("0", TokenAmount.format(BigInteger.ZERO, decimals))
        }
        assertEquals(BigInteger("1234567"), TokenAmount.parse("1.234567", 6))
        assertEquals("1.23", TokenAmount.format(BigInteger("1230000"), 6))
    }

    @Test fun ambiguousAndOutOfRangeInputsAreRejected() {
        for (text in listOf("0", "0.0", "-1", "+1", "01", ".1", "1.", "1e3", " 1", "1,2", "١", "1.0000001")) {
            assertThrows(text, IllegalArgumentException::class.java) { TokenAmount.parse(text, 6) }
        }
        assertThrows(IllegalArgumentException::class.java) { TokenAmount.parse("1.0", 0) }
        assertThrows(IllegalArgumentException::class.java) { TokenAmount.parse("1", -1) }
        assertThrows(IllegalArgumentException::class.java) { TokenAmount.format(BigInteger.ONE, 256) }
        assertThrows(IllegalArgumentException::class.java) { TokenAmount.format(BigInteger.valueOf(-1), 9) }
        assertThrows(IllegalArgumentException::class.java) { TokenAmount.parse(BigInteger.ONE.shiftLeft(120).toString(), 0) }
    }
}
