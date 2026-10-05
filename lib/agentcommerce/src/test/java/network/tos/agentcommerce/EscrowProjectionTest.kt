package network.tos.agentcommerce

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * These tests decode the shared escrow v2 projection vectors, which the iOS
 * client decodes identically. The five status cases carry the fields of the data
 * cells the escrow v2 contract itself wrote in the TOS sandbox; the refusal
 * cases edit one field of such a case (see the file's provenance).
 */
class EscrowProjectionTest {

    private val root: JsonObject by lazy {
        val stream = javaClass.classLoader!!
            .getResourceAsStream("mobile_buyer_escrow_projection_v2.json")
            ?: error("shared vector resource is missing")
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private val cases: List<JsonObject> by lazy { root["cases"]!!.jsonArray.map { it.jsonObject } }

    private val quoted: ULong by lazy { parseAtomicAmount(root.str("quoted_atomic")) }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    private fun JsonObject.bool(key: String) = this[key]!!.jsonPrimitive.boolean

    private fun case(name: String) = cases.firstOrNull { it.str("name") == name }
        ?: error("missing case $name")

    private fun runtime(case: JsonObject): EscrowRuntimeState? {
        if (!case.bool("present")) return null
        val e = case["escrow"]!!.jsonObject
        return EscrowRuntimeState(
            status = e["status"]!!.jsonPrimitive.int,
            quoteCommitment = e.str("quote_commitment"),
            fundedAtomicAmount = e.str("funded_atomic_amount"),
            settledAtomicAmount = e.str("settled_atomic_amount"),
            receiptCommitment = e.str("receipt_commitment"),
            acceptedAtUnix = e["accepted_at_unix"]!!.jsonPrimitive.long.toULong(),
            pendingQueryId = e["pending_query_id"]!!.jsonPrimitive.long.toULong(),
        )
    }

    private fun errorKind(error: Throwable): String = when (error) {
        is EscrowStateException.UnsupportedStatus -> "unsupported_status"
        is EscrowStateException.InconsistentState -> "inconsistent_state"
        is EscrowStateException.MalformedCommitment -> "malformed_commitment"
        is AtomicAmountException -> "malformed_amount"
        else -> "unexpected: $error"
    }

    @Test
    fun `status values are the contract statuses`() {
        assertEquals(
            "tos.service.mobile-buyer-escrow-projection.v2",
            root.str("schema"),
        )
        val statuses = root["escrow_status"]!!.jsonObject
        val expected = mapOf(
            "pending_acceptance" to EscrowStatus.PendingAcceptance,
            "awaiting_funding" to EscrowStatus.AwaitingFunding,
            "funded" to EscrowStatus.Funded,
            "release_pending" to EscrowStatus.ReleasePending,
            "refund_pending" to EscrowStatus.RefundPending,
        )
        assertEquals(expected.keys, statuses.keys)
        assertEquals(expected.size, EscrowStatus.entries.size)
        for ((name, status) in expected) {
            assertEquals(name, statuses[name]!!.jsonPrimitive.int, status.raw)
        }
    }

    @Test
    fun `every contract status is covered`() {
        val covered = cases.filter { it["expect_error"] == null && it.bool("present") }
            .map { it["escrow"]!!.jsonObject["status"]!!.jsonPrimitive.int }
            .toSet()
        assertEquals(setOf(0, 1, 2, 3, 4), covered)
    }

    @Test
    fun `projection matches shared vectors`() {
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val name = case.str("name")
            val state = runtime(case)

            val expectError = case["expect_error"]?.jsonPrimitive?.content
            if (expectError != null) {
                val calls = listOf<Pair<String, () -> Unit>>(
                    "funding" to { EscrowProjection.funding(state) },
                    "settlement" to { EscrowProjection.settlement(state) },
                    "isExactlyFunded" to { EscrowProjection.isExactlyFunded(state, quoted) },
                )
                for ((label, call) in calls) {
                    try {
                        call()
                        fail("$name $label must refuse")
                    } catch (error: IllegalArgumentException) {
                        assertEquals("$name $label", expectError, errorKind(error))
                    }
                }
                continue
            }

            val funding = EscrowProjection.funding(state)
            val settlement = EscrowProjection.settlement(state)
            val wantFunding = case["funding_view"]!!.jsonObject
            val wantSettlement = case["settlement_view"]!!.jsonObject

            assertEquals(name, wantFunding.bool("found"), funding.found)
            assertEquals(name, wantFunding.bool("pending_acceptance"), funding.pendingAcceptance)
            assertEquals(name, wantFunding.bool("awaiting_funding"), funding.awaitingFunding)
            assertEquals(name, parseAtomicAmount(wantFunding.str("funded_atomic")), funding.fundedAtomic)
            assertEquals(name, parseAtomicAmount(wantFunding.str("settled_atomic")), funding.settledAtomic)
            assertEquals(name, wantFunding.str("receipt_commitment"), funding.receiptCommitment)

            assertEquals(name, wantSettlement.bool("released"), settlement.released)
            assertEquals(name, wantSettlement.bool("refunded"), settlement.refunded)
            assertEquals(
                name,
                parseAtomicAmount(wantSettlement.str("provider_credit_atomic")),
                settlement.providerCreditAtomic,
            )

            assertEquals(
                name,
                case.bool("exactly_funded_at_quote"),
                EscrowProjection.isExactlyFunded(state, quoted),
            )
        }
    }

    @Test
    fun `funded is never released`() {
        val funded = runtime(case("funded"))!!
        assertEquals(EscrowStatus.Funded.raw, funded.status)
        val settlement = EscrowProjection.settlement(funded)
        assertFalse(settlement.released)
        assertFalse(settlement.refunded)
        assertEquals(0uL, settlement.providerCreditAtomic)
        assertFalse(EscrowProjection.funding(funded).awaitingFunding)
        assertTrue(EscrowProjection.isExactlyFunded(funded, 25_000_000uL))
        assertFalse(EscrowProjection.isExactlyFunded(funded, 24_999_999uL))
    }

    @Test
    fun `settlement in progress is not dispatchable`() {
        for (name in listOf("release_pending", "refund_pending")) {
            val state = runtime(case(name))!!
            assertEquals(name, "25000000", state.fundedAtomicAmount)
            assertFalse(name, EscrowProjection.isExactlyFunded(state, 25_000_000uL))
        }
    }

    @Test
    fun `funding gate counts only the funded status`() {
        for (case in cases) {
            val expected = case["expect_error"] == null && case.bool("exactly_funded_at_quote")
            assertEquals(case.str("name"), expected, EscrowProjection.countsAsFunding(runtime(case), quoted))
        }
        assertTrue(EscrowProjection.countsAsFunding(ContractEscrowStates.state("funded"), quoted))
        for (name in listOf("pending_acceptance", "awaiting_funding", "release_pending", "refund_pending")) {
            assertFalse(name, EscrowProjection.countsAsFunding(ContractEscrowStates.state(name), quoted))
        }
        assertFalse(EscrowProjection.countsAsFunding(null, quoted))
    }

    @Test
    fun `missing escrow is not fundable`() {
        val funding = EscrowProjection.funding(null)
        assertFalse(funding.found)
        assertFalse(funding.awaitingFunding)
        assertFalse(funding.pendingAcceptance)
        assertFalse(EscrowProjection.isExactlyFunded(null, 25_000_000uL))
    }

    @Test
    fun `overflow amount is rejected`() {
        assertThrows(AtomicAmountException::class.java) {
            parseAtomicAmount("18446744073709551616")
        }
    }
}
