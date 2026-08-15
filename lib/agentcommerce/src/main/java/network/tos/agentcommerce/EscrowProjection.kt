package network.tos.agentcommerce

/**
 * EscrowStatus is the finalized escrow runtime status. The values match the
 * canonical escrow decoder; the buyer never invents a status the chain does not
 * define.
 */
enum class EscrowStatus(val raw: Int) {
    AwaitingFunding(0),
    Funded(1),
    ReleasePending(2),
    RefundPending(3),
}

/**
 * Raised when an atomic amount string is not a canonical unsigned 64-bit value.
 * A malformed amount is rejected, never wrapped into a small balance that could
 * be mistaken for exact funding.
 */
class AtomicAmountException(value: String) :
    IllegalArgumentException("atomic amount is not a canonical uint64: $value")

/**
 * Decodes a decimal atomic-amount string. An empty string is zero; anything
 * negative, non-numeric, or larger than the unsigned 64-bit range is rejected.
 */
fun parseAtomicAmount(value: String): ULong {
    if (value.isEmpty()) return 0uL
    return value.toULongOrNull() ?: throw AtomicAmountException(value)
}

/** The finalized escrow state as decoded from typed chain state. */
data class EscrowRuntimeState(
    val status: Int,
    val quoteCommitment: String,
    val fundedAtomicAmount: String,
    val settledAtomicAmount: String,
    val receiptCommitment: String,
)

/** The buyer's funding projection of finalized escrow state. */
data class FundingView(
    val found: Boolean,
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
 * escrow read, mirroring the canonical resolver and the iOS client. Funding and
 * settlement are two projections of the same authoritative status, so they can
 * never disagree.
 */
object EscrowProjection {

    /** A not-found escrow (null) reads as unfunded/awaiting, never funded. */
    fun funding(escrow: EscrowRuntimeState?): FundingView {
        if (escrow == null) {
            return FundingView(
                found = false, awaitingFunding = true, fundedAtomic = 0uL,
                settledAtomic = 0uL, receiptCommitment = "",
            )
        }
        val funded = parseAtomicAmount(escrow.fundedAtomicAmount)
        val settled = parseAtomicAmount(escrow.settledAtomicAmount)
        return FundingView(
            found = true,
            awaitingFunding = escrow.status == EscrowStatus.AwaitingFunding.raw,
            fundedAtomic = funded,
            settledAtomic = settled,
            receiptCommitment = escrow.receiptCommitment,
        )
    }

    /**
     * Release and refund are the mutually exclusive terminal outcomes; only a
     * release credits the provider.
     */
    fun settlement(escrow: EscrowRuntimeState?): SettlementView {
        if (escrow == null) {
            return SettlementView(released = false, refunded = false, providerCreditAtomic = 0uL)
        }
        val settled = parseAtomicAmount(escrow.settledAtomicAmount)
        val released = escrow.status == EscrowStatus.ReleasePending.raw
        return SettlementView(
            released = released,
            refunded = escrow.status == EscrowStatus.RefundPending.raw,
            providerCreditAtomic = if (released) settled else 0uL,
        )
    }

    /**
     * Reports whether the escrow holds exactly the quoted amount in finalized
     * state — the only condition under which a buyer may treat a funded escrow as
     * safe to dispatch against.
     */
    fun isExactlyFunded(escrow: EscrowRuntimeState?, quotedAtomic: ULong): Boolean {
        val view = funding(escrow)
        return view.found && view.fundedAtomic == quotedAtomic
    }
}
