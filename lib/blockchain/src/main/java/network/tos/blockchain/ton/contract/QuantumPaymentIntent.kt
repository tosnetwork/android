package network.tos.blockchain.ton.contract

import java.math.BigInteger
import kotlinx.serialization.json.*
import network.tos.blockchain.ton.extensions.storeAddress
import org.ton.block.AddrStd
import org.ton.cell.Cell
import org.ton.cell.buildCell

/** Exact single native payment or typed Token request. Neither is a signing capability. */
class QuantumPaymentIntent private constructor(val recipient: AddrStd, val amount: BigInteger, val bounce: Boolean,
                                               comment: String?, val tokenTransfer: QuantumTokenTransferIntent?) {
    constructor(recipient: AddrStd, amount: BigInteger, bounce: Boolean, comment: String? = null) :
        this(recipient, amount, bounce, comment, null)
    constructor(tokenTransfer: QuantumTokenTransferIntent) :
        this(tokenTransfer.wallet, tokenTransfer.attachedTos, true, null, tokenTransfer)
    val comment = comment?.takeIf { it.isNotEmpty() }
    init {
        require(recipient.anycast.value == null && amount.signum() > 0 && amount.bitLength() <= 120)
        require((comment?.toByteArray(Charsets.UTF_8)?.size ?: 0) <= 4096)
    }
    val body: Cell get() {
        tokenTransfer?.let { return it.body }
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
        tokenTransfer == other.tokenTransfer &&
        comment?.toByteArray(Charsets.UTF_8).contentEquals(other.comment?.toByteArray(Charsets.UTF_8))
    override fun hashCode() = 31 * (31 * recipient.hashCode() + amount.hashCode()) + (tokenTransfer?.hashCode() ?: 0)

    fun encode(): String = buildJsonObject {
        put("recipient", raw(recipient)); put("amount", amount.toString()); put("bounce", bounce)
        comment?.let { put("comment", it) }
        tokenTransfer?.let { put("token_transfer", Json.parseToJsonElement(it.encode())) }
    }.toString()

    companion object {
        private fun raw(address: AddrStd) = "${address.workchainId}:" + address.address.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
        fun decode(encoded: String): QuantumPaymentIntent {
            require(encoded.toByteArray(Charsets.UTF_8).size <= 32768)
            val value = Json.parseToJsonElement(encoded).jsonObject
            fun text(key: String) = value.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            val recipient = AddrStd.parse(text("recipient"))
            val decimal = text("amount")
            require(decimal.length in 1..37 && decimal.matches(Regex("0|[1-9][0-9]*")))
            val amount = decimal.toBigInteger()
            val bounce = value.getValue("bounce").jsonPrimitive.also { require(!it.isString) }.boolean
            val comment = value["comment"]?.takeIf { it != JsonNull }?.jsonPrimitive?.also { require(it.isString) }?.content
            val token = value["token_transfer"] ?: return QuantumPaymentIntent(recipient, amount, bounce, comment)
            val result = QuantumPaymentIntent(QuantumTokenTransferIntent.decode(token.jsonObject.toString()))
            require(recipient == result.recipient && amount == result.amount && bounce && comment.isNullOrEmpty()) { "Token outer message differs from saved intent" }
            return result
        }
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
