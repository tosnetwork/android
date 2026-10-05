package network.tos.agentcommerce

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Escrow states taken from the shared projection vectors: the named contract
 * cases are the fields of data cells the escrow v2 contract wrote.
 */
object ContractEscrowStates {
    private val cases by lazy {
        val stream = javaClass.classLoader!!
            .getResourceAsStream("mobile_buyer_escrow_projection_v2.json")
            ?: error("shared vector resource is missing")
        val root = Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
        root["cases"]!!.jsonArray.map { it.jsonObject }
    }

    fun state(name: String): EscrowRuntimeState {
        val case = cases.firstOrNull { it["name"]!!.jsonPrimitive.content == name }
            ?: error("missing case $name")
        val e = case["escrow"]?.jsonObject ?: error("case $name has no escrow")
        return EscrowRuntimeState(
            status = e["status"]!!.jsonPrimitive.int,
            quoteCommitment = e["quote_commitment"]!!.jsonPrimitive.content,
            fundedAtomicAmount = e["funded_atomic_amount"]!!.jsonPrimitive.content,
            settledAtomicAmount = e["settled_atomic_amount"]!!.jsonPrimitive.content,
            receiptCommitment = e["receipt_commitment"]!!.jsonPrimitive.content,
            acceptedAtUnix = e["accepted_at_unix"]!!.jsonPrimitive.long.toULong(),
            pendingQueryId = e["pending_query_id"]!!.jsonPrimitive.long.toULong(),
        )
    }

    /**
     * The contract's funded state with a different funded amount: still a
     * consistent funded escrow, but not funded at the quoted amount.
     */
    fun funded(atomic: String): EscrowRuntimeState = state("funded").copy(fundedAtomicAmount = atomic)
}
