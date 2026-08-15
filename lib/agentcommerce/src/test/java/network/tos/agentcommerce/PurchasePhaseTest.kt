package network.tos.agentcommerce

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Decodes the SAME shared purchase-phase vector the Go atosbridge phase functions
 * are verified against, so the Kotlin crash-safe resume logic — the at-most-once
 * payment invariant — is proven identical to the reference and to the iOS client.
 */
class PurchasePhaseTest {

    private fun loadVector(): String {
        val stream = javaClass.classLoader!!
            .getResourceAsStream("mobile_buyer_purchase_phase_v1.json")
            ?: error("shared vector resource is missing")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun `purchase phase matches shared vectors`() {
        val root = Json.parseToJsonElement(loadVector()).jsonObject
        assertEquals(
            "atos.native.mobile-buyer-purchase-phase.v1",
            root["schema"]!!.jsonPrimitive.content,
        )

        for (element in root["resume"]!!.jsonArray) {
            val case = element.jsonObject
            val phase = case["phase"]!!.jsonPrimitive.content
            assertEquals(phase, case["can_acquire_lease"]!!.jsonPrimitive.boolean, PurchasePhase.canAcquireFundingLease(phase))
            assertEquals(phase, case["resume_action"]!!.jsonPrimitive.content, PurchasePhase.resumeActionFor(phase).wire)
        }
        for (element in root["transitions"]!!.jsonArray) {
            val case = element.jsonObject
            val from = case["from"]!!.jsonPrimitive.content
            val to = case["to"]!!.jsonPrimitive.content
            assertEquals("$from->$to", case["can_advance"]!!.jsonPrimitive.boolean, PurchasePhase.canAdvance(from, to))
        }
    }

    @Test
    fun `never refunds at or after funding lease`() {
        for (phase in listOf("funding_lease", "funded", "execution", "receipt", "release")) {
            assertEquals(phase, ResumeAction.RECONCILE_NEVER_REFUND, PurchasePhase.resumeActionFor(phase))
            assertFalse(phase, PurchasePhase.canAcquireFundingLease(phase))
        }
    }
}
