package network.tos.blockchain.ton.contract

import java.math.BigInteger
import network.tos.blockchain.ton.extensions.storeAddress
import org.ton.block.AddrStd
import org.ton.cell.Cell
import org.ton.cell.buildCell

/** Exact single native-coin intent. Neither a quote nor a signing capability. */
class QuantumPaymentIntent(val recipient: AddrStd, val amount: BigInteger, val bounce: Boolean, comment: String? = null) {
    val comment = comment?.takeIf { it.isNotEmpty() }
    init {
        require(recipient.anycast.value == null && amount.signum() > 0 && amount.bitLength() <= 120)
        require((comment?.toByteArray(Charsets.UTF_8)?.size ?: 0) <= 4096)
    }
    val body: Cell get() {
        val text = comment?.toByteArray(Charsets.UTF_8) ?: return buildCell { }
        return buildCell {
            storeUInt(0, 32); storeBytes(text.copyOfRange(0, minOf(123, text.size)))
            if (text.size > 123) storeRef(TosPqWallet.byteChain(text.copyOfRange(123, text.size)))
        }
    }
    fun message(): Cell = buildCell {
        storeUInt(if (bounce) 24 else 16, 6) // internal, IHR disabled, absent relaxed source
        storeAddress(recipient)
        val count = (amount.bitLength() + 7) / 8
        storeUInt(count, 4); storeUInt(amount, count * 8); storeBit(false)
        storeUInt(0, 4); storeUInt(0, 4); storeUInt(0, 64); storeUInt(0, 32)
        storeBit(false); storeBit(true); storeRef(body)
    }
    fun actions(): Cell = buildCell {
        storeUInt(0x0ec3c86d, 32); storeUInt(3, 8); storeRef(buildCell { }); storeRef(message())
    }
    fun requireBound(request: Cell) = requireBound(QuantumStoredSelfPayRequest.parse(request))
    fun requireBound(request: QuantumStoredSelfPayRequest) {
        require(request.role == QuantumAuthRole.PRIMARY && request.kind == 0 &&
            request.executeActions?.hash() == actions().hash()) { "Payment intent differs from authorization" }
    }
    override fun equals(other: Any?): Boolean = other is QuantumPaymentIntent && recipient == other.recipient &&
        amount == other.amount && bounce == other.bounce &&
        comment?.toByteArray(Charsets.UTF_8).contentEquals(other.comment?.toByteArray(Charsets.UTF_8))
    override fun hashCode() = 31 * recipient.hashCode() + amount.hashCode()

    companion object {
        fun parseTos(text: String): BigInteger {
            require(text.length in 1..48 && text.matches(Regex("(0|[1-9][0-9]*)(\\.[0-9]{1,9})?")))
            val parts = text.split('.')
            val value = BigInteger(parts[0]).multiply(BigInteger.TEN.pow(9)) +
                BigInteger((parts.getOrNull(1) ?: "").padEnd(9, '0'))
            require(value.signum() > 0 && value.bitLength() <= 120)
            return value
        }
    }
}
