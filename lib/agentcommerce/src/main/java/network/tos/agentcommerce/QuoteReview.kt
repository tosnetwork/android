package network.tos.agentcommerce

/**
 * The verdict of reviewing a Quote before approval. [OK] means safe to display;
 * every other value is a specific, deterministic rejection, so a malformed Quote
 * is rejected for the same reason on every platform. [wire] matches the shared
 * vectors and the Go reference.
 */
enum class ReviewReason(val wire: String) {
    OK("ok"),
    CAPABILITY_VERSION_MISSING("capability_version_missing"),
    MANIFEST_DIGEST_MALFORMED("manifest_digest_malformed"),
    ASSET_MASTER_MALFORMED("asset_master_malformed"),
    ASSET_WALLET_CODE_HASH_MALFORMED("asset_wallet_code_hash_malformed"),
    AMOUNT_NOT_POSITIVE("amount_not_positive"),
    ESCROW_ADDRESS_MALFORMED("escrow_address_malformed"),
    QUOTE_COMMITMENT_MALFORMED("quote_commitment_malformed"),
    FEE_PAYER_UNKNOWN("fee_payer_unknown"),
    EXPIRED("expired"),
}

/**
 * The canonical Accepted Quote facts a buyer must see and validate before
 * approving a spend — exactly what the confirmation screen shows. The buyer
 * approves these commitments, not a ticker or a Gateway claim.
 */
data class QuoteReview(
    val capabilityVersion: String,
    val manifestDigest: String,
    val assetMaster: String,
    val assetWalletCodeHash: String,
    val amountAtomic: String,
    val escrowAddress: String,
    val quoteCommitment: String,
    val feePayer: String,
    val expiryUnix: ULong,
) {
    /**
     * Validates the Quote facts against the current time. Checks run in a fixed
     * order so the reason is deterministic. A Gateway response or a friendly
     * asset ticker never substitutes for a commitment.
     */
    fun review(nowUnix: ULong): ReviewReason {
        if (capabilityVersion.isEmpty()) return ReviewReason.CAPABILITY_VERSION_MISSING
        if (!isShaDigest(manifestDigest)) return ReviewReason.MANIFEST_DIGEST_MALFORMED
        if (!isRawWorkchainZero(assetMaster)) return ReviewReason.ASSET_MASTER_MALFORMED
        if (!isCellDigest(assetWalletCodeHash)) return ReviewReason.ASSET_WALLET_CODE_HASH_MALFORMED
        val amount = amountAtomic.toULongOrNull()
        if (amount == null || amount == 0uL) return ReviewReason.AMOUNT_NOT_POSITIVE
        if (!isRawWorkchainZero(escrowAddress)) return ReviewReason.ESCROW_ADDRESS_MALFORMED
        if (!isCellDigest(quoteCommitment)) return ReviewReason.QUOTE_COMMITMENT_MALFORMED
        if (feePayer != "buyer" && feePayer != "provider") return ReviewReason.FEE_PAYER_UNKNOWN
        if (expiryUnix <= nowUnix) return ReviewReason.EXPIRED
        return ReviewReason.OK
    }
}

/** A canonical raw workchain-0 address: "0:" then 64 lowercase hex characters. */
fun isRawWorkchainZero(value: String): Boolean = hasPrefixHexBody(value, "0:")

/** A canonical tvm-cell-sha256 digest. */
fun isCellDigest(value: String): Boolean = hasPrefixHexBody(value, "tvm-cell-sha256:")

/** A canonical sha256 digest. */
fun isShaDigest(value: String): Boolean = hasPrefixHexBody(value, "sha256:")

private fun hasPrefixHexBody(value: String, prefix: String): Boolean {
    if (!value.startsWith(prefix)) return false
    val body = value.substring(prefix.length)
    if (body.length != 64) return false
    return body.all { it in '0'..'9' || it in 'a'..'f' }
}
