package network.tos.agentcommerce

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Decodes the SAME shared spending-policy vector the Go atosbridge.PolicyEngine
 * is verified against, so the Kotlin owner-authorization is proven identical to
 * the canonical engine and to the iOS client.
 */
class SpendingPolicyTest {

    private fun loadVector(): String {
        val stream = javaClass.classLoader!!
            .getResourceAsStream("mobile_buyer_spending_policy_v1.json")
            ?: error("shared vector resource is missing")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun str(obj: kotlinx.serialization.json.JsonObject?, base: kotlinx.serialization.json.JsonObject, key: String): String =
        (obj?.get(key) ?: base[key])!!.jsonPrimitive.content

    private fun u64(obj: kotlinx.serialization.json.JsonObject?, base: kotlinx.serialization.json.JsonObject, key: String): ULong =
        (obj?.get(key) ?: base[key])!!.jsonPrimitive.long.toULong()

    private fun allow(obj: kotlinx.serialization.json.JsonObject?, base: kotlinx.serialization.json.JsonObject): Set<String> =
        (obj?.get("capability_allow") ?: base["capability_allow"])!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun bool(obj: kotlinx.serialization.json.JsonObject?, base: kotlinx.serialization.json.JsonObject, key: String): Boolean =
        (obj?.get(key) ?: base[key])!!.jsonPrimitive.content.toBoolean()

    @Test
    fun `authorize matches shared vectors`() {
        val root = Json.parseToJsonElement(loadVector()).jsonObject
        assertEquals(
            "atos.native.mobile-buyer-spending-policy.v1",
            root["schema"]!!.jsonPrimitive.content,
        )
        val now = root["now_unix"]!!.jsonPrimitive.long.toULong()
        val policyBase = root["policy_base"]!!.jsonObject
        val proposalBase = root["proposal_base"]!!.jsonObject

        for (element in root["cases"]!!.jsonArray) {
            val case = element.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            val po = case["policy"]?.jsonObject
            val pr = case["proposal"]?.jsonObject

            val policy = SpendingPolicy(
                assetMaster = str(po, policyBase, "asset_master"),
                assetWalletCodeHash = str(po, policyBase, "asset_wallet_code_hash"),
                maxAtomicPurchase = str(po, policyBase, "max_atomic_purchase").toULong(),
                dailyBudgetAtomic = str(po, policyBase, "daily_budget_atomic").toULong(),
                windowSeconds = u64(po, policyBase, "window_seconds"),
                expiryUnix = u64(po, policyBase, "expiry_unix"),
                capabilityAllow = allow(po, policyBase),
                confirmationMode = str(po, policyBase, "confirmation_mode"),
                hasOwnerSignature = bool(po, policyBase, "has_owner_signature"),
            )
            val proposal = QuoteFacts(
                assetMaster = str(pr, proposalBase, "asset_master"),
                assetWalletCodeHash = str(pr, proposalBase, "asset_wallet_code_hash"),
                capabilityId = str(pr, proposalBase, "capability_id"),
                maxAtomicAmount = str(pr, proposalBase, "max_atomic_amount").toULong(),
            )
            val spent = case["spent_in_window_atomic"]!!.jsonPrimitive.content.toULong()

            assertEquals(
                name,
                case["expect"]!!.jsonPrimitive.content,
                SpendingPolicyEngine.authorize(policy, proposal, spent, now).wire,
            )
        }
    }
}
