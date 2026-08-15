package network.tos.agentcommerce

import java.io.ByteArrayOutputStream
import java.net.URI

/**
 * The caller's configured TOS network a Contact Card must bind to. A locator for
 * another network is refused before any connection.
 */
data class NetworkTuple(
    val networkId: String,
    val genesisRoot: String,
    val genesisFile: String,
)

/**
 * The deterministic verdict of the stateless Contact Card check performed before
 * any endpoint connection. [OK] means well-formed, unexpired, and on the caller's
 * network; the caller then verifies the ed25519 signature over [contactBytes]
 * and, authoritatively, that the key controls the Agent. [wire] matches the
 * shared vectors and the Go reference.
 */
enum class ContactReason(val wire: String) {
    OK("ok"),
    AGENT_ID_MALFORMED("agent_id_malformed"),
    PUBLIC_KEY_MALFORMED("public_key_malformed"),
    SIGNATURE_MALFORMED("signature_malformed"),
    ENDPOINT_MALFORMED("endpoint_malformed"),
    EXPIRY_INVALID("expiry_invalid"),
    CAPABILITY_MALFORMED("capability_malformed"),
    NETWORK_MISMATCH("network_mismatch"),
}

/** Bounds how far in the future a Contact Card may expire. */
val CONTACT_LIFETIME_SECONDS: ULong = 86_400uL

/** A signed, non-canonical locator from a QR code or a universal link. */
data class ContactCardFacts(
    val agentId: String,
    val networkId: String,
    val genesisRoot: String,
    val genesisFile: String,
    val endpoint: String,
    val capabilities: List<String>,
    val expiresAtUnix: ULong,
    val publicKey: ByteArray,
    val signature: ByteArray,
)

object ContactCard {

    // Domain separator for the Contact Card signing preimage: the ASCII tag
    // followed by a single null byte, matching the canonical issuer.
    private const val DOMAIN_TAG = "atos.agent.contact.v1"

    /**
     * Applies the pre-connection checks in a fixed order. No signature or
     * resolver work; the caller verifies the ed25519 signature over
     * [contactBytes] after this passes.
     */
    fun validateStateless(card: ContactCardFacts, network: NetworkTuple, nowUnix: ULong): ContactReason {
        if (!isAgentId(card.agentId)) return ContactReason.AGENT_ID_MALFORMED
        if (card.publicKey.size != 32) return ContactReason.PUBLIC_KEY_MALFORMED
        if (card.signature.size != 64) return ContactReason.SIGNATURE_MALFORMED
        if (card.endpoint.trim() != card.endpoint || !isValidEndpoint(card.endpoint)) {
            return ContactReason.ENDPOINT_MALFORMED
        }
        if (card.expiresAtUnix == 0uL || nowUnix >= card.expiresAtUnix ||
            card.expiresAtUnix > nowUnix + CONTACT_LIFETIME_SECONDS
        ) {
            return ContactReason.EXPIRY_INVALID
        }
        val seen = HashSet<String>()
        for (capability in card.capabilities) {
            if (!isCapabilityId(capability) || !seen.add(capability)) {
                return ContactReason.CAPABILITY_MALFORMED
            }
        }
        if (card.networkId != network.networkId || card.genesisRoot != network.genesisRoot ||
            card.genesisFile != network.genesisFile
        ) {
            return ContactReason.NETWORK_MISMATCH
        }
        return ContactReason.OK
    }

    /**
     * Builds the ed25519 signing preimage. It must be byte-for-byte identical to
     * the canonical issuer, or every signature check fails.
     */
    fun contactBytes(card: ContactCardFacts): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(DOMAIN_TAG.toByteArray(Charsets.UTF_8))
        out.write(0)
        fun text(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            out.write(bigEndian32(bytes.size))
            out.write(bytes)
        }
        text(card.agentId)
        text(card.networkId)
        text(card.genesisRoot)
        text(card.genesisFile)
        text(card.endpoint)
        out.write(bigEndian32(card.capabilities.size))
        for (capability in card.capabilities) text(capability)
        out.write(bigEndian64(card.expiresAtUnix))
        out.write(card.publicKey)
        return out.toByteArray()
    }
}

private fun bigEndian32(value: Int): ByteArray = byteArrayOf(
    (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
)

private fun bigEndian64(value: ULong): ByteArray {
    val out = ByteArray(8)
    for (i in 0 until 8) {
        out[i] = ((value shr (56 - i * 8)) and 0xffuL).toByte()
    }
    return out
}

private fun isAgentId(value: String): Boolean =
    value.startsWith("agent_") && isHex64(value.substring("agent_".length))

private fun isCapabilityId(value: String): Boolean =
    value.startsWith("cap_") && isHex64(value.substring("cap_".length))

private fun isHex64(body: String): Boolean =
    body.length == 64 && body.all { it in '0'..'9' || it in 'a'..'f' }

private fun isValidEndpoint(endpoint: String): Boolean {
    return try {
        val uri = URI(endpoint)
        val host = uri.host ?: return false
        if (host.isEmpty() || uri.userInfo != null || uri.query != null || uri.fragment != null) return false
        when (uri.scheme) {
            "https" -> true
            "http" -> host == "127.0.0.1" || host == "localhost" || host == "::1"
            else -> false
        }
    } catch (_: Exception) {
        false
    }
}
