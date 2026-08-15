package network.tos.agentcommerce

/**
 * The crash-recovery decision for a persisted purchase phase. The single
 * authority for at-most-once payment across process death: before the funding
 * lease a purchase may still fund; at or after the lease recovery is read-only
 * and NEVER re-pays; once resolved it is complete. [wire] matches the shared
 * vectors and the Go reference.
 */
enum class ResumeAction(val wire: String) {
    INVALID("invalid"),
    MAY_FUND("may_fund"),
    RECONCILE_NEVER_REFUND("reconcile_never_refund"),
    COMPLETE("complete"),
}

/**
 * The crash-safe purchase-journal phase machine. Mirrors the servicebridge phase
 * functions so a mobile client resumes a purchase after process death with
 * exactly the same safety decision as the buyer engine.
 */
object PurchasePhase {

    /** Strict forward position of a phase, or -1 if unknown. */
    fun order(phase: String): Int = when (phase) {
        "intent" -> 0
        "prepared" -> 1
        "funding_lease" -> 2
        "funded" -> 3
        "execution" -> 4
        "receipt" -> 5
        "release" -> 6
        "resolved" -> 7
        else -> -1
    }

    /**
     * Whether a purchase may move from one phase to another. Only strictly-forward
     * transitions between known phases are legal.
     */
    fun canAdvance(from: String, to: String): Boolean {
        val fromOrder = order(from)
        val toOrder = order(to)
        return fromOrder >= 0 && toOrder >= 0 && toOrder > fromOrder
    }

    /** Whether the single funding lease may be taken from this phase. */
    fun canAcquireFundingLease(phase: String): Boolean = phase == "prepared"

    /** Decides how to safely resume a purchase after process death. */
    fun resumeActionFor(phase: String): ResumeAction {
        val position = order(phase)
        return when {
            position < 0 -> ResumeAction.INVALID
            position < order("funding_lease") -> ResumeAction.MAY_FUND
            phase == "resolved" -> ResumeAction.COMPLETE
            else -> ResumeAction.RECONCILE_NEVER_REFUND
        }
    }
}
