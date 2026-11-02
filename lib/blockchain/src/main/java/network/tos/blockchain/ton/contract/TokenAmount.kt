package network.tos.blockchain.ton.contract

import java.math.BigInteger

/** TEP-74 VarUInteger 16 quantities. Never use floating point for token units. */
object TokenAmount {
    private fun scale(decimals: Int): BigInteger {
        require(decimals in 0..255) { "Invalid token decimals" }
        return BigInteger.TEN.pow(decimals)
    }

    fun parse(text: String, decimals: Int): BigInteger {
        val scale = scale(decimals)
        require(text.length in 1..(38 + decimals))
        val parts = text.split('.')
        require(parts.size in 1..2)
        val whole = parts[0]
        require(whole.isNotEmpty() && whole.all { it in '0'..'9' } &&
            (whole == "0" || whole[0] != '0'))
        val fraction = parts.getOrNull(1)
        require(fraction == null || (fraction.isNotEmpty() && fraction.length <= decimals &&
            fraction.all { it in '0'..'9' }))
        val units = BigInteger(whole).multiply(scale) +
            BigInteger((fraction ?: "").padEnd(decimals, '0').ifEmpty { "0" })
        require(units.signum() > 0 && units.bitLength() <= 120) { "Invalid token amount" }
        return units
    }

    fun format(units: BigInteger, decimals: Int): String {
        val scale = scale(decimals)
        require(units.signum() >= 0 && units.bitLength() <= 120)
        if (decimals == 0) return units.toString()
        val parts = units.divideAndRemainder(scale)
        val fraction = parts[1].toString().padStart(decimals, '0').trimEnd('0')
        return parts[0].toString() + if (fraction.isEmpty()) "" else "." + fraction
    }
}
