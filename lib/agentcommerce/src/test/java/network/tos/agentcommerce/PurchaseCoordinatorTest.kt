package network.tos.agentcommerce

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PurchaseCoordinatorTest {
    @Test
    fun fundingSubmitterRunsOnceAcrossRestart() {
        val directory = Files.createTempDirectory("purchase-coordinator").toFile()
        try {
            val journal = preparedJournal(directory)
            var calls = 0
            val submitter = FundingSubmitter { _, _ -> calls++ }
            val first = PurchaseCoordinator(journal).submitFunding("lease-1", 3uL, submitter)
            val restarted = PurchaseCoordinator(PurchaseJournal(directory)).submitFunding("lease-2", 4uL, submitter)
            assertEquals(FundingSubmissionResult.Submitted("lease-1"), first)
            assertEquals(FundingSubmissionResult.ReconcileOnly("lease-1"), restarted)
            assertEquals(1, calls)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ambiguousSubmissionNeverRunsAgain() {
        val directory = Files.createTempDirectory("purchase-coordinator").toFile()
        try {
            val journal = preparedJournal(directory)
            var calls = 0
            val submitter = FundingSubmitter { _, _ -> calls++; error("ambiguous") }
            assertThrows(IllegalStateException::class.java) {
                PurchaseCoordinator(journal).submitFunding("lease-ambiguous", 3uL, submitter)
            }
            assertEquals(
                FundingSubmissionResult.ReconcileOnly("lease-ambiguous"),
                PurchaseCoordinator(PurchaseJournal(directory)).submitFunding("ignored", 4uL, submitter),
            )
            assertEquals(1, calls)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun fundingRequiresExactTwoOfThreeFinalizedAgreement() {
        val directory = Files.createTempDirectory("purchase-coordinator").toFile()
        try {
            val journal = preparedJournal(directory)
            journal.acquireFundingLease("lease-finality", 3uL)
            val network = NetworkTuple("tos-local", "root", "file")
            val endpoints = listOf("https://one", "https://two", "https://three")
            var resolverCalls = 0
            val resolver = FundingFinalityResolver {
                resolverCalls++
                listOf(
                    FundingObservation(endpoints[0], network, "block", "state", true, ContractEscrowStates.state("funded")),
                    FundingObservation(endpoints[1], network, "block", "state", true, ContractEscrowStates.state("funded")),
                    FundingObservation(endpoints[2], network, "other", "other", true, ContractEscrowStates.funded("1")),
                )
            }
            assertEquals(
                FundingReconciliationResult.Funded("block", "state", 2),
                PurchaseCoordinator(journal).pollFunding(endpoints, network, "25000000", 4uL, resolver),
            )
            assertEquals("funded", journal.load().phase)
            assertEquals(1, resolverCalls)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun wrongAmountAndEndpointEquivocationStayPending() {
        val directory = Files.createTempDirectory("purchase-coordinator").toFile()
        try {
            val journal = preparedJournal(directory)
            journal.acquireFundingLease("lease-pending", 3uL)
            val network = NetworkTuple("tos-local", "root", "file")
            val endpoints = listOf("https://one", "https://two", "https://three")
            val resolver = FundingFinalityResolver {
                listOf(
                    FundingObservation(endpoints[0], network, "block", "state", true, ContractEscrowStates.state("funded")),
                    FundingObservation(endpoints[0], network, "other", "other", true, ContractEscrowStates.state("funded")),
                    FundingObservation(endpoints[1], network, "block", "state", true, ContractEscrowStates.funded("24999999")),
                )
            }
            assertEquals(
                FundingReconciliationResult.Pending,
                PurchaseCoordinator(journal).pollFunding(endpoints, network, "25000000", 4uL, resolver),
            )
            assertEquals("funding_lease", journal.load().phase)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun onlyTheFundedStatusAdvancesFunding() {
        // Release-pending and refund-pending escrows still record the full funded
        // amount; only the funded status itself may confirm funding.
        val expectations = listOf(
            "funded" to true,
            "awaiting_funding" to false,
            "release_pending" to false,
            "refund_pending" to false,
        )
        for ((name, advances) in expectations) {
            val directory = Files.createTempDirectory("purchase-coordinator").toFile()
            try {
                val journal = preparedJournal(directory)
                journal.acquireFundingLease("lease-$name", 3uL)
                val network = NetworkTuple("tos-local", "root", "file")
                val endpoints = listOf("https://one", "https://two", "https://three")
                val escrow = ContractEscrowStates.state(name)
                val resolver = FundingFinalityResolver {
                    endpoints.map { FundingObservation(it, network, "block", "state", true, escrow) }
                }
                val result = PurchaseCoordinator(journal).pollFunding(endpoints, network, "25000000", 4uL, resolver)
                if (advances) {
                    assertEquals(name, FundingReconciliationResult.Funded("block", "state", 3), result)
                    assertEquals(name, "funded", journal.load().phase)
                } else {
                    assertEquals(name, FundingReconciliationResult.Pending, result)
                    assertEquals(name, "funding_lease", journal.load().phase)
                }
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    private fun preparedJournal(directory: java.io.File): PurchaseJournal {
        val journal = PurchaseJournal(directory)
        journal.create("purchase-1", 1uL)
        journal.advance("prepared", 2uL)
        return journal
    }
}
