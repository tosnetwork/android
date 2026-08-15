package network.tos.agentcommerce

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Decodes the SAME shared Contact Card vector the Go reference is verified
 * against (its valid case carries a real ed25519 signature over the canonical
 * preimage), so the Kotlin contactBytes is proven byte-identical to the issuer
 * and to the iOS client, and the stateless gate matches exactly.
 */
class ContactCardTest {

    private fun loadVector(): String {
        val stream = javaClass.classLoader!!
            .getResourceAsStream("mobile_buyer_contact_card_v1.json")
            ?: error("shared vector resource is missing")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `contact card matches shared vectors`() {
        val root = Json.parseToJsonElement(loadVector()).jsonObject
        assertEquals(
            "atos.native.mobile-buyer-contact-card.v1",
            root["schema"]!!.jsonPrimitive.content,
        )
        val now = root["now_unix"]!!.jsonPrimitive.long.toULong()
        val net = root["network"]!!.jsonObject
        val network = NetworkTuple(
            networkId = net["network_id"]!!.jsonPrimitive.content,
            genesisRoot = net["genesis_root"]!!.jsonPrimitive.content,
            genesisFile = net["genesis_file"]!!.jsonPrimitive.content,
        )

        for (element in root["cases"]!!.jsonArray) {
            val case = element.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            val c = case["card"]!!.jsonObject
            val facts = ContactCardFacts(
                agentId = c["agent_id"]!!.jsonPrimitive.content,
                networkId = c["network_id"]!!.jsonPrimitive.content,
                genesisRoot = c["genesis_root"]!!.jsonPrimitive.content,
                genesisFile = c["genesis_file"]!!.jsonPrimitive.content,
                endpoint = c["endpoint"]!!.jsonPrimitive.content,
                capabilities = c["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content },
                expiresAtUnix = c["expires_at_unix"]!!.jsonPrimitive.long.toULong(),
                publicKey = hexToBytes(c["public_key_hex"]!!.jsonPrimitive.content),
                signature = hexToBytes(c["signature_hex"]!!.jsonPrimitive.content),
            )

            assertEquals(
                "contactBytes for $name",
                case["contact_bytes_hex"]!!.jsonPrimitive.content,
                bytesToHex(ContactCard.contactBytes(facts)),
            )
            assertEquals(
                name,
                case["expect"]!!.jsonPrimitive.content,
                ContactCard.validateStateless(facts, network, now).wire,
            )
        }
    }
}
