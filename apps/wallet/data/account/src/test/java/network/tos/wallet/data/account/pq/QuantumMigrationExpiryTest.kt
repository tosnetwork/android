package network.tos.wallet.data.account.pq

import org.junit.Assert.*
import org.junit.Test

/** Time-policy boundary only. Does not fabricate a proof or an authenticated migration plan. */
class QuantumMigrationExpiryTest {
    @Test fun deadlineEqualityAndEarlierProvenTimeRemainBlocked() {
        for (time in listOf(999L, 1000L)) {
            val error = assertThrows(QuantumPriorControlNotExpired::class.java) {
                QuantumPreparedSuccessorMigration.requireStrictExpiry(time, 1000)
            }
            assertEquals(time, error.provenShardTime)
            assertEquals(1000L, error.authorizationDeadline)
        }
        QuantumPreparedSuccessorMigration.requireStrictExpiry(1001, 1000)
    }
    @Test fun wireTimeBoundsCannotWrapOrTreatZeroAsExpired() {
        for ((time, deadline) in listOf(0L to 1000L, -1L to 1000L, 0x100000000L to 1000L,
            1001L to 0L, 1001L to -1L, 1001L to 0x100000000L)) {
            assertThrows(IllegalArgumentException::class.java) {
                QuantumPreparedSuccessorMigration.requireStrictExpiry(time, deadline)
            }
        }
        QuantumPreparedSuccessorMigration.requireStrictExpiry(0xffffffffL, 0xfffffffeL)
        assertThrows(QuantumPriorControlNotExpired::class.java) {
            QuantumPreparedSuccessorMigration.requireStrictExpiry(0xffffffffL, 0xffffffffL)
        }
    }
}
