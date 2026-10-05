package network.tos.agentcommerce

/**
 * EscrowStatus is the status byte of the stablecoin escrow v2 data cell, the
 * only escrow contract the buyer supports. The values are the contract's own
 * `status::` constants; any other value is refused, never mapped onto a nearby
 * status.
 */
enum class EscrowStatus(val raw: Int) {
    /** Deployed, but the buyer has not accepted the Quote. Not fundable. */
    PendingAcceptance(0),

    /** Accepted by the buyer; the escrow takes exactly the quoted amount. */
    AwaitingFunding(1),

    /** Holds the quoted amount. Funded is never paid to the provider. */
    Funded(2),

    /** A Receipt-bound release to the provider was sent for the full amount. */
    ReleasePending(3),

    /** A refund of the full amount to the buyer was sent. */
    RefundPending(4),
    ;

    companion object {
        fun fromRaw(raw: Int): EscrowStatus? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * Raised when an atomic amount string is not a canonical unsigned 64-bit value.
 * A malformed amount is rejected, never wrapped into a small balance that could
 * be mistaken for exact funding.
 */
class AtomicAmountException(value: String) :
    IllegalArgumentException("atomic amount is not a canonical uint64: $value")

/**
 * Raised when a decoded escrow state is not one the escrow v2 contract can be
 * in. The projection refuses it instead of guessing.
 */
sealed class EscrowStateException(message: String) : IllegalArgumentException(message) {
    /** The status is not an escrow v2 status. */
    class UnsupportedStatus(val status: Int) :
        EscrowStateException("escrow status $status is not an escrow v2 status")

    /** The runtime fields contradict what the contract writes in this status. */
    class InconsistentState(val status: EscrowStatus) :
        EscrowStateException("escrow runtime fields contradict status $status")

    /**
     * A commitment is not `tvm-cell-sha256:` followed by 64 lowercase hex digits
     * (the Receipt commitment may also be empty).
     */
    class MalformedCommitment(val value: String) :
        EscrowStateException("malformed escrow commitment: $value")
}

/**
 * Decodes a decimal atomic-amount string. An empty string is zero; anything
 * negative, non-numeric, or larger than the unsigned 64-bit range is rejected.
 */
fun parseAtomicAmount(value: String): ULong {
    if (value.isEmpty()) return 0uL
    return value.toULongOrNull() ?: throw AtomicAmountException(value)
}

/**
 * The finalized escrow v2 state as decoded from the escrow's data cell: the
 * status byte, the Quote commitment, and the runtime cell's funded amount,
 * settled amount, Receipt hash (empty when zero), pending settlement query id
 * and acceptance time. `null` represents an escrow account that does not exist.
 */
data class EscrowRuntimeState(
    val status: Int,
    val quoteCommitment: String,
    val fundedAtomicAmount: String,
    val settledAtomicAmount: String,
    val receiptCommitment: String,
    val acceptedAtUnix: ULong,
    val pendingQueryId: ULong,
)

/** The buyer's funding projection of finalized escrow state. */
data class FundingView(
    val found: Boolean,
    val pendingAcceptance: Boolean,
    val awaitingFunding: Boolean,
    val fundedAtomic: ULong,
    val settledAtomic: ULong,
    val receiptCommitment: String,
)

/**
 * The buyer's settlement projection. [released] is the only signal that means
 * "paid to the provider", derived from finalized escrow status — never from a
 * Gateway response or an HTTP success.
 */
data class SettlementView(
    val released: Boolean,
    val refunded: Boolean,
    val providerCreditAtomic: ULong,
)

/**
 * Derives the buyer's funding and settlement views from a single finalized
 * escrow v2 read, identically to the iOS client. Funding and settlement are two
 * projections of the same authoritative status, so they can never disagree, and
 * both refuse a state the contract cannot be in.
 */
object EscrowProjection {

    private class Validated(val status: EscrowStatus, val funded: ULong, val settled: ULong)

    private const val COMMITMENT_PREFIX = "tvm-cell-sha256:"

    private fun isCommitment(value: String): Boolean {
        if (!value.startsWith(COMMITMENT_PREFIX)) return false
        val digest = value.substring(COMMITMENT_PREFIX.length)
        return digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' }
    }

    /**
     * Checks the status and the per-status runtime invariants the escrow v2
     * contract maintains: acceptance time is set exactly once the Quote is
     * accepted; funds arrive only in funded; a release settles the full funded
     * amount against a Receipt; a refund settles nothing; and a pending
     * settlement always names its query id.
     */
    private fun validate(escrow: EscrowRuntimeState): Validated {
        val status = EscrowStatus.fromRaw(escrow.status)
            ?: throw EscrowStateException.UnsupportedStatus(escrow.status)
        if (!isCommitment(escrow.quoteCommitment)) {
            throw EscrowStateException.MalformedCommitment(escrow.quoteCommitment)
        }
        if (escrow.receiptCommitment.isNotEmpty() && !isCommitment(escrow.receiptCommitment)) {
            throw EscrowStateException.MalformedCommitment(escrow.receiptCommitment)
        }
        val funded = parseAtomicAmount(escrow.fundedAtomicAmount)
        val settled = parseAtomicAmount(escrow.settledAtomicAmount)
        val accepted = escrow.acceptedAtUnix > 0uL
        val hasReceipt = escrow.receiptCommitment.isNotEmpty()
        val hasQuery = escrow.pendingQueryId != 0uL
        val consistent = when (status) {
            EscrowStatus.PendingAcceptance ->
                !accepted && funded == 0uL && settled == 0uL && !hasReceipt && !hasQuery
            EscrowStatus.AwaitingFunding ->
                accepted && funded == 0uL && settled == 0uL && !hasReceipt && !hasQuery
            EscrowStatus.Funded ->
                accepted && funded > 0uL && settled == 0uL && !hasReceipt && !hasQuery
            EscrowStatus.ReleasePending ->
                accepted && funded > 0uL && settled == funded && hasReceipt && hasQuery
            EscrowStatus.RefundPending ->
                accepted && funded > 0uL && settled == 0uL && !hasReceipt && hasQuery
        }
        if (!consistent) throw EscrowStateException.InconsistentState(status)
        return Validated(status, funded, settled)
    }

    /**
     * A missing escrow (null) is neither awaiting funding nor funded: the
     * contract accepts funds only after the buyer has accepted the Quote on a
     * deployed escrow.
     */
    fun funding(escrow: EscrowRuntimeState?): FundingView {
        if (escrow == null) {
            return FundingView(
                found = false, pendingAcceptance = false, awaitingFunding = false,
                fundedAtomic = 0uL, settledAtomic = 0uL, receiptCommitment = "",
            )
        }
        val state = validate(escrow)
        return FundingView(
            found = true,
            pendingAcceptance = state.status == EscrowStatus.PendingAcceptance,
            awaitingFunding = state.status == EscrowStatus.AwaitingFunding,
            fundedAtomic = state.funded,
            settledAtomic = state.settled,
            receiptCommitment = escrow.receiptCommitment,
        )
    }

    /** Release and refund are mutually exclusive; only a release credits the provider. */
    fun settlement(escrow: EscrowRuntimeState?): SettlementView {
        if (escrow == null) {
            return SettlementView(released = false, refunded = false, providerCreditAtomic = 0uL)
        }
        val state = validate(escrow)
        val released = state.status == EscrowStatus.ReleasePending
        return SettlementView(
            released = released,
            refunded = state.status == EscrowStatus.RefundPending,
            providerCreditAtomic = if (released) state.settled else 0uL,
        )
    }

    /**
     * Reports whether the escrow is in the funded status and holds exactly the
     * quoted amount in finalized state — the only condition under which a buyer
     * may treat it as safe to dispatch against. An escrow whose release or
     * refund is already pending still records the funded amount, so the amount
     * alone is not enough.
     */
    fun isExactlyFunded(escrow: EscrowRuntimeState?, quotedAtomic: ULong): Boolean {
        if (escrow == null) return false
        val state = validate(escrow)
        return state.status == EscrowStatus.Funded && quotedAtomic > 0uL &&
            state.funded == quotedAtomic
    }
}
