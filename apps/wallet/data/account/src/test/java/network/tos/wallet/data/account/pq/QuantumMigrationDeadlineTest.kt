package network.tos.wallet.data.account.pq

import org.junit.Assert.*
import org.junit.Test

class QuantumMigrationDeadlineTest {
    @Test fun retainedDeadlineNeverMovesForwardWithNewObservationTime() {
        val original = QuantumMigrationDeadline.choose(1000, 120)
        assertEquals(1120L, original)
        assertEquals(original, QuantumMigrationDeadline.choose(1050, 120, original))
        assertEquals(original, QuantumMigrationDeadline.choose(1119, 120, original))
        for (time in listOf(1120L, 1121L)) assertThrows(IllegalArgumentException::class.java) {
            QuantumMigrationDeadline.choose(time, 120, original)
        }
    }
    @Test fun invalidOrExcessiveWindowAndWireTimeAreRejected() {
        for ((time, ttl, deadline) in listOf(Triple(0L, 120L, 1120L), Triple(1000L, 0L, 1120L),
            Triple(1000L, 3601L, 1120L), Triple(1000L, 120L, 1121L), Triple(1000L, 120L, 0L),
            Triple(0x100000000L, 120L, 0x100000001L))) {
            assertThrows(IllegalArgumentException::class.java) { QuantumMigrationDeadline.choose(time, ttl, deadline) }
        }
        assertEquals(0xffffffffL, QuantumMigrationDeadline.choose(0xfffffffeL, 1))
        assertThrows(IllegalArgumentException::class.java) { QuantumMigrationDeadline.choose(0xffffffffL, 1) }
    }
}
