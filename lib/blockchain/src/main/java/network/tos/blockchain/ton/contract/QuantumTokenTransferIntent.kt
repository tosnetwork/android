package network.tos.blockchain.ton.contract

import java.math.BigInteger
import kotlinx.serialization.json.*
import network.tos.blockchain.ton.extensions.storeAddress
import org.ton.block.AddrStd
import org.ton.cell.Cell
import org.ton.cell.CellBuilder
import org.ton.cell.buildCell

/** Exact Token request, not proof of funds or permission to sign.
 * Precision is persisted for review; raw integer units are the onchain amount.
 */
data class QuantumTokenTransferIntent(
    val owner: AddrStd, val master: AddrStd, val wallet: AddrStd, val recipient: AddrStd,
    val units: BigInteger, val decimals: Int, val queryId: BigInteger,
    val attachedTos: BigInteger, val forwardTos: BigInteger = BigInteger.ZERO,
) {
    init {
        for (address in listOf(owner, master, wallet, recipient)) {
            require(address.workchainId == 0 && address.anycast.value == null)
        }
        require(units.signum() > 0 && units.bitLength() <= 120 && decimals in 0..255)
        require(queryId.signum() >= 0 && queryId.bitLength() <= 64)
        require(attachedTos.signum() > 0 && attachedTos.bitLength() <= 120)
        require(forwardTos.signum() >= 0 && forwardTos < attachedTos)
        TokenContractCodes.family(owner, master, wallet)
    }

    val body: Cell get() = buildCell {
        storeUInt(0x0f8a7ea5, 32); storeUInt(queryId, 64); coins(units)
        storeAddress(recipient); storeAddress(owner)
        storeBit(false) // no custom payload
        coins(forwardTos); storeBit(false) // empty inline forward payload
    }
    fun message(): Cell = buildCell {
        storeUInt(24, 6); storeAddress(wallet); coins(attachedTos); storeBit(false)
        storeUInt(0, 4); storeUInt(0, 4); storeUInt(0, 64); storeUInt(0, 32)
        storeBit(false); storeBit(true); storeRef(body)
    }
    fun actions(): Cell = buildCell {
        storeUInt(0x0ec3c86d, 32); storeUInt(3, 8); storeRef(buildCell { }); storeRef(message())
    }

    /** Canonical strings preserve uint64 IDs and 120-bit amounts across a restart. */
    fun encode(): String = buildJsonObject {
        put("version", 1); put("owner", raw(owner)); put("master", raw(master)); put("wallet", raw(wallet))
        put("recipient", raw(recipient)); put("units", units.toString()); put("decimals", decimals)
        put("queryId", queryId.toString()); put("attachedTos", attachedTos.toString()); put("forwardTos", forwardTos.toString())
    }.toString()

    companion object {
        private fun raw(address: AddrStd) = "0:" + address.address.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun CellBuilder.coins(value: BigInteger) {
            val size = (value.bitLength() + 7) / 8
            storeUInt(size, 4); if (size > 0) storeUInt(value, size * 8)
        }
        fun decode(encoded: String): QuantumTokenTransferIntent {
            require(encoded.toByteArray(Charsets.UTF_8).size <= 4096)
            val value = Json.parseToJsonElement(encoded).jsonObject
            require(value.keys == setOf("version", "owner", "master", "wallet", "recipient", "units", "decimals", "queryId", "attachedTos", "forwardTos"))
            fun text(key: String): String = value.getValue(key).jsonPrimitive.also { require(it.isString) }.content
            fun integer(key: String): Int = value.getValue(key).jsonPrimitive.also { require(!it.isString) }.int
            fun address(key: String): AddrStd = text(key).also { require(it.matches(Regex("0:[0-9a-f]{64}"))) }.let { AddrStd.parse(it) }
            fun amount(key: String, limit: Int): BigInteger = text(key).also {
                require(it.length in 1..limit && it.matches(Regex("0|[1-9][0-9]*")))
            }.toBigInteger()
            require(integer("version") == 1)
            return QuantumTokenTransferIntent(address("owner"), address("master"), address("wallet"), address("recipient"),
                amount("units", 37), integer("decimals"), amount("queryId", 20), amount("attachedTos", 37), amount("forwardTos", 37))
        }
    }
}
