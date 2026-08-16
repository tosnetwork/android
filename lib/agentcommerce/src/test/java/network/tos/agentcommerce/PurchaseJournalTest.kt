package network.tos.agentcommerce

import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PurchaseJournalTest {
    @Test
    fun `lease persists before funding and cannot be reacquired after restart`() {
        val root = Files.createTempDirectory("agent-commerce-journal").toFile()
        try {
            val journal = PurchaseJournal(root)
            assertEquals("intent", journal.create("purchase_01", 10uL).phase)
            assertEquals("prepared", journal.advance("prepared", 11uL).phase)
            val leased = journal.acquireFundingLease("lease_01", 12uL)
            assertEquals("funding_lease", leased.phase)
            assertEquals(ResumeAction.RECONCILE_NEVER_REFUND, journal.resumeAction())

            val restarted = PurchaseJournal(root)
            assertEquals(leased, restarted.load())
            assertThrows(PurchaseJournalException::class.java) {
                restarted.acquireFundingLease("lease_02", 13uL)
            }
            assertEquals("funded", restarted.advance("funded", 14uL).phase)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cannot skip or move backward across funding lease`() {
        val root = Files.createTempDirectory("agent-commerce-journal").toFile()
        try {
            val journal = PurchaseJournal(root)
            journal.create("purchase_02", 10uL)
            assertThrows(PurchaseJournalException::class.java) { journal.advance("funded", 11uL) }
            journal.advance("prepared", 12uL)
            journal.acquireFundingLease("lease_02", 13uL)
            assertThrows(PurchaseJournalException::class.java) { journal.advance("prepared", 14uL) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `concurrent instances grant exactly one lease`() {
        val root = Files.createTempDirectory("agent-commerce-journal").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val setup = PurchaseJournal(root)
            setup.create("purchase_03", 10uL)
            setup.advance("prepared", 11uL)
            val journals = listOf(PurchaseJournal(root), PurchaseJournal(root))
            val futures = journals.mapIndexed { index, journal ->
                executor.submit(Callable {
                    runCatching { journal.acquireFundingLease("lease_0$index", 12uL) }.isSuccess
                })
            }
            assertEquals(1, futures.count { it.get() })
            assertEquals(ResumeAction.RECONCILE_NEVER_REFUND, setup.resumeAction())
        } finally {
            executor.shutdownNow()
            root.deleteRecursively()
        }
    }
}
