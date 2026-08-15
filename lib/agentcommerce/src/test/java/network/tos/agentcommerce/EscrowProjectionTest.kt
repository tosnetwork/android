package network.tos.agentcommerce

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests decode the SAME shared vector file the Go EscrowSettlementReader is
 * verified against, so the Kotlin projection is proven identical to the canonical
 * implementation and to the iOS client.
 */
class EscrowProjectionTest {

    private fun loadVector(): String {
        val stream = javaClass.classLoader!!
            .getResourceAsStream("mobile_buyer_escrow_projection_v1.json")
            ?: error("shared vector resource is missing")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun `projection matches shared vectors`() {
        val root = Json.parseToJsonElement(loadVector()).jsonObject
        assertEquals(
            "tos.service.mobile-buyer-escrow-projection.v1",
            root["schema"]!!.jsonPrimitive.content,
        )
        val cases = root["cases"]!!.jsonArray
        assertTrue(cases.isNotEmpty())

        for (element in cases) {
            val case = element.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            val present = case["present"]!!.jsonPrimitive.boolean
            val runtime = if (present) {
                val e = case["escrow"]!!.jsonObject
                EscrowRuntimeState(
                    status = e["status"]!!.jsonPrimitive.int,
                    quoteCommitment = e["quote_commitment"]!!.jsonPrimitive.content,
                    fundedAtomicAmount = e["funded_atomic_amount"]!!.jsonPrimitive.content,
                    settledAtomicAmount = e["settled_atomic_amount"]!!.jsonPrimitive.content,
                    receiptCommitment = e["receipt_commitment"]!!.jsonPrimitive.content,
                )
            } else {
                null
            }

            if (case["expect_decode_error"]?.jsonPrimitive?.boolean == true) {
                assertThrows(name, AtomicAmountException::class.java) {
                    EscrowProjection.funding(runtime)
                }
                continue
            }

            val funding = EscrowProjection.funding(runtime)
            val settlement = EscrowProjection.settlement(runtime)
            val wantFunding = case["funding_view"]!!.jsonObject
            val wantSettlement = case["settlement_view"]!!.jsonObject

            assertEquals(name, wantFunding["found"]!!.jsonPrimitive.boolean, funding.found)
            assertEquals(name, wantFunding["awaiting_funding"]!!.jsonPrimitive.boolean, funding.awaitingFunding)
            assertEquals(name, parseAtomicAmount(wantFunding["funded_atomic"]!!.jsonPrimitive.content), funding.fundedAtomic)
            assertEquals(name, parseAtomicAmount(wantFunding["settled_atomic"]!!.jsonPrimitive.content), funding.settledAtomic)
            assertEquals(name, wantFunding["receipt_commitment"]!!.jsonPrimitive.content, funding.receiptCommitment)

            assertEquals(name, wantSettlement["released"]!!.jsonPrimitive.boolean, settlement.released)
            assertEquals(name, wantSettlement["refunded"]!!.jsonPrimitive.boolean, settlement.refunded)
            assertEquals(
                name,
                parseAtomicAmount(wantSettlement["provider_credit_atomic"]!!.jsonPrimitive.content),
                settlement.providerCreditAtomic,
            )
        }
    }

    @Test
    fun `gateway success is never payment`() {
        val funded = EscrowRuntimeState(
            status = EscrowStatus.Funded.raw, quoteCommitment = "tvm-cell-sha256:aa",
            fundedAtomicAmount = "25000000", settledAtomicAmount = "0", receiptCommitment = "",
        )
        assertFalse(EscrowProjection.settlement(funded).released)
        assertTrue(EscrowProjection.isExactlyFunded(funded, 25_000_000uL))
        assertFalse(EscrowProjection.isExactlyFunded(funded, 24_999_999uL))
    }

    @Test
    fun `overflow amount is rejected`() {
        assertThrows(AtomicAmountException::class.java) {
            parseAtomicAmount("18446744073709551616")
        }
    }
}
