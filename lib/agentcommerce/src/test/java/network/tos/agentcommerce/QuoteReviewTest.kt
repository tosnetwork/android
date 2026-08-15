package network.tos.agentcommerce

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes the SAME shared Quote-review vector the Go reference is verified
 * against, so the Kotlin approval gate is proven identical to the canonical
 * implementation and to the iOS client.
 */
class QuoteReviewTest {

    private fun loadVector(): String {
        val stream = javaClass.classLoader!!
            .getResourceAsStream("mobile_buyer_quote_review_v1.json")
            ?: error("shared vector resource is missing")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun `review matches shared vectors`() {
        val root = Json.parseToJsonElement(loadVector()).jsonObject
        assertEquals(
            "tos.service.mobile-buyer-quote-review.v1",
            root["schema"]!!.jsonPrimitive.content,
        )
        val now = root["now_unix"]!!.jsonPrimitive.long.toULong()
        val cases = root["cases"]!!.jsonArray
        assertTrue(cases.isNotEmpty())

        for (element in cases) {
            val case = element.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            val r = case["review"]!!.jsonObject
            val review = QuoteReview(
                capabilityVersion = r["capability_version"]!!.jsonPrimitive.content,
                manifestDigest = r["manifest_digest"]!!.jsonPrimitive.content,
                assetMaster = r["asset_master"]!!.jsonPrimitive.content,
                assetWalletCodeHash = r["asset_wallet_code_hash"]!!.jsonPrimitive.content,
                amountAtomic = r["amount_atomic"]!!.jsonPrimitive.content,
                escrowAddress = r["escrow_address"]!!.jsonPrimitive.content,
                quoteCommitment = r["quote_commitment"]!!.jsonPrimitive.content,
                feePayer = r["fee_payer"]!!.jsonPrimitive.content,
                expiryUnix = r["expiry_unix"]!!.jsonPrimitive.long.toULong(),
            )
            assertEquals(name, case["expect"]!!.jsonPrimitive.content, review.review(now).wire)
        }
    }

    @Test
    fun `gateway fee payer is rejected`() {
        val hex = "a".repeat(64)
        val review = QuoteReview(
            capabilityVersion = "1.0.0",
            manifestDigest = "sha256:$hex",
            assetMaster = "0:$hex",
            assetWalletCodeHash = "tvm-cell-sha256:$hex",
            amountAtomic = "25000000",
            escrowAddress = "0:" + "b".repeat(64),
            quoteCommitment = "tvm-cell-sha256:" + "c".repeat(64),
            feePayer = "gateway",
            expiryUnix = 2_000_000_000uL,
        )
        assertEquals(ReviewReason.FEE_PAYER_UNKNOWN, review.review(1_786_800_000uL))
        assertFalse(review.review(1_786_800_000uL) == ReviewReason.OK)
    }
}
