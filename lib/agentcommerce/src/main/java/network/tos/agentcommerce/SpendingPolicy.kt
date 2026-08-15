package network.tos.agentcommerce

/**
 * The deterministic verdict of evaluating a Quote against the owner's spending
 * policy. [OK] means proceed automatically; [MANUAL_CONFIRMATION] means within
 * policy but needing explicit owner approval; anything else blocks the spend.
 * [wire] matches the shared vectors and the Go servicebridge.PolicyEngine.
 */
enum class PolicyReason(val wire: String) {
    OK("ok"),
    POLICY_INVALID("policy_invalid"),
    POLICY_EXPIRED("policy_expired"),
    ASSET_NOT_ALLOWED("asset_not_allowed"),
    CAPABILITY_NOT_ALLOWED("capability_not_allowed"),
    OVER_PURCHASE_LIMIT("over_purchase_limit"),
    OVER_DAILY_BUDGET("over_daily_budget"),
    MANUAL_CONFIRMATION("manual_confirmation"),
}

/**
 * The owner-signed authorization envelope enforced locally before every
 * purchase. Its signature is verified out of band; this type enforces the
 * bounds. Expiry is intentionally not part of [isValid] — it is enforced by the
 * time comparison in [SpendingPolicyEngine.authorize] — so the reason ordering
 * matches the reference exactly.
 */
data class SpendingPolicy(
    val assetMaster: String,
    val assetWalletCodeHash: String,
    val maxAtomicPurchase: ULong,
    val dailyBudgetAtomic: ULong,
    val windowSeconds: ULong,
    val expiryUnix: ULong,
    val capabilityAllow: Set<String>,
    val confirmationMode: String,
    val hasOwnerSignature: Boolean,
) {
    val isValid: Boolean
        get() = assetMaster.isNotEmpty() && assetWalletCodeHash.isNotEmpty() &&
            maxAtomicPurchase > 0uL && dailyBudgetAtomic >= maxAtomicPurchase &&
            windowSeconds >= 60uL && capabilityAllow.isNotEmpty() && hasOwnerSignature &&
            (confirmationMode == "auto" || confirmationMode == "manual")
}

/** The subset of a Quote the spending policy is evaluated against. */
data class QuoteFacts(
    val assetMaster: String,
    val assetWalletCodeHash: String,
    val capabilityId: String,
    val maxAtomicAmount: ULong,
)

object SpendingPolicyEngine {

    /**
     * Evaluates a Quote against the owner policy. Checks run in a fixed order so
     * the reason is deterministic, and the budget arithmetic is overflow-safe.
     * [spentInWindow] is the amount already reserved in the policy window,
     * counted from the crash-safe journal.
     */
    fun authorize(policy: SpendingPolicy, proposal: QuoteFacts, spentInWindow: ULong, nowUnix: ULong): PolicyReason {
        if (!policy.isValid) return PolicyReason.POLICY_INVALID
        if (nowUnix >= policy.expiryUnix) return PolicyReason.POLICY_EXPIRED
        if (proposal.assetMaster != policy.assetMaster ||
            proposal.assetWalletCodeHash != policy.assetWalletCodeHash
        ) {
            return PolicyReason.ASSET_NOT_ALLOWED
        }
        if (proposal.capabilityId !in policy.capabilityAllow) return PolicyReason.CAPABILITY_NOT_ALLOWED
        val amount = proposal.maxAtomicAmount
        if (amount == 0uL || amount > policy.maxAtomicPurchase) return PolicyReason.OVER_PURCHASE_LIMIT
        if (spentInWindow > policy.dailyBudgetAtomic ||
            amount > policy.dailyBudgetAtomic - spentInWindow
        ) {
            return PolicyReason.OVER_DAILY_BUDGET
        }
        if (policy.confirmationMode == "manual") return PolicyReason.MANUAL_CONFIRMATION
        return PolicyReason.OK
    }
}
