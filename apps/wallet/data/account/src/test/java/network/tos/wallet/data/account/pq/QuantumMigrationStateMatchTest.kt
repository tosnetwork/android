package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.QuantumSelfPayAuthority
import network.tos.blockchain.ton.contract.QuantumSelfPayState
import network.tos.blockchain.ton.contract.QuantumPolicy
import org.junit.Assert.*
import org.junit.Test

/** Public contract-data fixtures only; no chain proof or completion capability. */
class QuantumMigrationStateMatchTest {
    private fun authority(global: Int = 1, namespace: Byte = 42, primary: Byte = 11,
        rescue: Byte = 12, policy: QuantumPolicy = QuantumPolicy.REQUIRED) =
        QuantumSelfPayAuthority(global, ByteArray(32) { namespace }, ByteArray(1312) { primary }, ByteArray(32) { rescue }, policy)
    private fun state(authority: QuantumSelfPayAuthority = authority(), wallet: Long = 17, seqno: Long = 0,
        retired: Int = 2, epoch: ULong = 5uL, primary: ULong = 0uL, rescue: ULong = 0uL) =
        QuantumSelfPayState(authority, wallet, seqno, retired, epoch, primary, rescue)

    @Test fun independentlyDecodedSameStateMatchesAndMissingAccountDoesNot() {
        val expected = state()
        assertTrue(QuantumMigrationStateMatch.matches(QuantumSelfPayState.parse(expected.cell), expected))
        assertFalse(QuantumMigrationStateMatch.matches(null, expected))
        assertFalse(QuantumMigrationStateMatch.matches(state(epoch = 6uL), expected))
    }

    @Test fun matchingEpochCannotHideWrongAuthorityPolicyIdentityRetirementOrCounters() {
        val expected = state()
        val changed = listOf(state(authority(primary = 21)), state(authority(rescue = 22)),
            state(authority(policy = QuantumPolicy.READY)), state(authority(global = 2)),
            state(authority(namespace = 43)), state(wallet = 18), state(seqno = 1), state(retired = 0),
            state(primary = 1uL), state(rescue = 1uL))
        for (current in changed) {
            assertEquals(expected.epoch, current.epoch)
            assertFalse("Matching epoch must not hide different contract data", QuantumMigrationStateMatch.matches(current, expected))
        }
    }
}
