package network.tos.agentcommerce

fun interface FundingSubmitter {
    /**
     * Broadcasts once, after the lease is durable. An error is ambiguous; the
     * coordinator never invokes this method again for the same purchase.
     */
    fun submitFunding(purchaseId: String, fundingLeaseId: String)
}

data class FundingObservation(
    val endpoint: String,
    val network: NetworkTuple,
    val blockRoot: String,
    val stateDigest: String,
    val finalized: Boolean,
    val fundedAtomic: String,
)

fun interface FundingFinalityResolver {
    fun resolveFunding(purchaseId: String): List<FundingObservation>
}

sealed interface FundingSubmissionResult {
    data class Submitted(val fundingLeaseId: String) : FundingSubmissionResult
    data class ReconcileOnly(val fundingLeaseId: String) : FundingSubmissionResult
}

sealed interface FundingReconciliationResult {
    data object Pending : FundingReconciliationResult
    data class Funded(val blockRoot: String, val stateDigest: String, val votes: Int) : FundingReconciliationResult
    data class AlreadyBeyondFunding(val phase: String) : FundingReconciliationResult
}

class PurchaseCoordinatorException(message: String) : Exception(message)

/**
 * Joins the durable funding lease to exact-amount, multi-endpoint finality.
 * Platform networking and custody are injected; the decision about whether a
 * broadcaster may run remains inside this crash-safe coordinator.
 */
class PurchaseCoordinator(private val journal: PurchaseJournal) {
    fun submitFunding(
        fundingLeaseId: String,
        nowUnix: ULong,
        submitter: FundingSubmitter,
    ): FundingSubmissionResult {
        val current = journal.load()
        if (current.phase == "prepared") {
            val leased = try {
                journal.acquireFundingLease(fundingLeaseId, nowUnix)
            } catch (_: PurchaseJournalException) {
                return reconciliationOnlyResult()
            }
            submitter.submitFunding(leased.purchaseId, requireNotNull(leased.fundingLeaseId))
            return FundingSubmissionResult.Submitted(requireNotNull(leased.fundingLeaseId))
        }
        if (PurchasePhase.order(current.phase) < PurchasePhase.order("funding_lease")) {
            throw PurchaseCoordinatorException("purchase is not prepared for funding")
        }
        return reconciliationOnlyResult()
    }

    fun pollFunding(
        configuredEndpoints: List<String>,
        expectedNetwork: NetworkTuple,
        expectedAtomic: String,
        nowUnix: ULong,
        resolver: FundingFinalityResolver,
    ): FundingReconciliationResult {
        val current = journal.load()
        if (PurchasePhase.order(current.phase) >= PurchasePhase.order("funded")) {
            return FundingReconciliationResult.AlreadyBeyondFunding(current.phase)
        }
        if (current.phase != "funding_lease") {
            throw PurchaseCoordinatorException("purchase has no funding lease")
        }
        val expected = try {
            parseAtomicAmount(expectedAtomic)
        } catch (_: IllegalArgumentException) {
            throw PurchaseCoordinatorException("invalid expected funding amount")
        }
        if (expected == 0uL) throw PurchaseCoordinatorException("invalid expected funding amount")
        val observations = resolver.resolveFunding(current.purchaseId).map { observation ->
            val amountMatches = try {
                parseAtomicAmount(observation.fundedAtomic) == expected
            } catch (_: IllegalArgumentException) {
                false
            }
            FinalizedObservation(
                endpoint = observation.endpoint,
                network = observation.network,
                blockRoot = observation.blockRoot,
                stateDigest = observation.stateDigest,
                finalized = observation.finalized && amountMatches,
            )
        }
        return when (val decision = FinalityQuorum.decide(configuredEndpoints, expectedNetwork, observations)) {
            FinalityDecision.InvalidConfiguration -> throw PurchaseCoordinatorException("invalid finality configuration")
            FinalityDecision.Pending -> FundingReconciliationResult.Pending
            is FinalityDecision.Finalized -> {
                try {
                    journal.advance("funded", nowUnix)
                } catch (error: PurchaseJournalException) {
                    if (PurchasePhase.order(journal.load().phase) < PurchasePhase.order("funded")) throw error
                }
                FundingReconciliationResult.Funded(decision.blockRoot, decision.stateDigest, decision.votes)
            }
        }
    }

    private fun reconciliationOnlyResult(): FundingSubmissionResult.ReconcileOnly {
        val current = journal.load()
        if (PurchasePhase.order(current.phase) < PurchasePhase.order("funding_lease") || current.fundingLeaseId == null) {
            throw PurchaseCoordinatorException("purchase has no durable funding lease")
        }
        return FundingSubmissionResult.ReconcileOnly(current.fundingLeaseId)
    }
}
