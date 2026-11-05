package network.tos.wallet.data.account.pq

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PrivacyHistoryLeaseTest {
    @Test fun transferredLeaseMustBeHeldForTheExactObservation() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        PrivacyHistorySynchronizer.Lease(a).use { lease ->
            lease.requireHeld(a)
            assertThrows(IllegalStateException::class.java) { lease.requireHeld(b) }
            lease.requireHeld(a)
            lease.close()
            assertThrows(IllegalStateException::class.java) { lease.requireHeld(a) }
        }
    }
    @Test fun boundsDuplicateFailureAndIdempotentRelease() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val c = UUID.randomUUID()
        PrivacyHistorySynchronizer.Lease(a).use { first ->
            assertThrows(IllegalStateException::class.java) { PrivacyHistorySynchronizer.Lease(a).close() }
            PrivacyHistorySynchronizer.Lease(b).use { second ->
                assertThrows(IllegalStateException::class.java) { PrivacyHistorySynchronizer.Lease(c).close() }
                first.close()
                PrivacyHistorySynchronizer.Lease(a).use { replacement ->
                    first.close()
                    assertThrows(IllegalStateException::class.java) { PrivacyHistorySynchronizer.Lease(c).close() }
                    replacement.close()
                }
                second.close()
                PrivacyHistorySynchronizer.Lease(c).use { }
            }
        }
    }
}
