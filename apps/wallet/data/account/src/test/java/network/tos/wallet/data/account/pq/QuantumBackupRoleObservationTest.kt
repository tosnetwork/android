package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.QuantumSelfPayAuthority
import network.tos.security.pq.QuantumRole
import org.junit.Assert.assertEquals
import org.junit.Test

class QuantumBackupRoleObservationTest {
    @Test fun mixedCurrentAndStaleRolesAreIndependent() {
        val snapshot = QuantumSelfPayAuthority(-239, ByteArray(32), ByteArray(1312) { 7 }, ByteArray(32) { 8 })
        val changedPrimary = QuantumSelfPayAuthority(-239, ByteArray(32), ByteArray(1312) { 9 }, ByteArray(32) { 8 })
        assertEquals(QuantumBackupRoleObservation.Match.STALE,
            QuantumBackupRoleObservation.compare(QuantumRole.PRIMARY, snapshot, changedPrimary))
        assertEquals(QuantumBackupRoleObservation.Match.MATCHES_CURRENT,
            QuantumBackupRoleObservation.compare(QuantumRole.RESCUE, snapshot, changedPrimary))
        val changedRescue = QuantumSelfPayAuthority(-239, ByteArray(32), snapshot.primaryKey, ByteArray(32) { 9 })
        assertEquals(QuantumBackupRoleObservation.Match.MATCHES_CURRENT,
            QuantumBackupRoleObservation.compare(QuantumRole.PRIMARY, snapshot, changedRescue))
        assertEquals(QuantumBackupRoleObservation.Match.STALE,
            QuantumBackupRoleObservation.compare(QuantumRole.RESCUE, snapshot, changedRescue))
        for (role in QuantumRole.entries) assertEquals(QuantumBackupRoleObservation.Match.UNAVAILABLE,
            QuantumBackupRoleObservation.compare(role, snapshot, null))
    }
}
