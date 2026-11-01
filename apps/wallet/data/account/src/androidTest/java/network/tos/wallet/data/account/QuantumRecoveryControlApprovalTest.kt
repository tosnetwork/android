package network.tos.wallet.data.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import network.tos.wallet.data.account.pq.QuantumRecoveryControlApproval
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.UUID
import network.tos.wallet.data.account.pq.QuantumRecoveryControlJournal
import network.tos.wallet.data.account.pq.QuantumNetworkProfile
import network.tos.blockchain.ton.contract.*
import network.tos.security.pq.*
import org.ton.boc.BagOfCells

/** Metadata codecs only; no native quote or signing/broadcast authority is manufactured here. */
@RunWith(AndroidJUnit4::class)
class QuantumRecoveryControlApprovalTest {
    private val operation = UUID.randomUUID()
    @Test fun expectedControlStateRetainsIdentityAndAppliesExactContractCounters() {
        val authority = QuantumSelfPayAuthority(3, ByteArray(32) { 43 }, ByteArray(1312), ByteArray(32))
        val identity = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(authority))
        val before = QuantumSelfPayState(authority, 0, 41, 0, 123u, ULong.MAX_VALUE, ULong.MAX_VALUE)
        for (configure in listOf(false, true)) {
            val action = if (configure) QuantumSelfPayAction.ConfigureRequired else QuantumSelfPayAction.LockPrimary
            val request = TosQuantumSelfPayRequest(identity.address, before, QuantumAuthRole.RESCUE, 1120, action, 1000)
            val entry = QuantumRecoveryControlJournal.Entry(operation, UUID.randomUUID(), identity,
                QuantumNetworkProfile(3, "01".repeat(32), "02".repeat(32)), BagOfCells(before.cell).toByteArray(),
                BagOfCells(request.request).toByteArray(), QuantumRecoveryControlJournal.Phase.RESERVED, ByteArray(0))
            val expectedAuthority = if (configure) QuantumSelfPayAuthority(3, authority.namespace, authority.primaryKey,
                authority.rescueKey, QuantumPolicy.REQUIRED) else authority
            val expected = QuantumSelfPayState(expectedAuthority, 0, if (configure) 42 else 41,
                if (configure) 0 else 2, 124u, 0u, 0u)
            assertEquals(expected.cell.hash(), entry.expectedControlState().cell.hash())
            assertNotEquals(before.cell.hash(), entry.expectedControlState().cell.hash())
            assertEquals(identity, entry.identity)
            assertEquals(expected.cell.hash(), QuantumRecoveryControlJournal.Entry.decode(entry.encode()).expectedControlState().cell.hash())
        }
    }
    private fun record(before: BigInteger = BigInteger("100"), after: BigInteger = BigInteger("90"),
                       limit: BigInteger = BigInteger("10"), start: Long = 1000, end: Long = 1030) =
        QuantumRecoveryControlApproval.record(operation, ByteArray(32) { 1 }, ByteArray(32) { 2 }, start, end, before, after, limit)

    @Test fun fixedMetadataRetainsFullUnsignedValuesAndTimeBounds() {
        val maximum = BigInteger.ONE.shiftLeft(120).subtract(BigInteger.ONE)
        val bytes = record(maximum, maximum - BigInteger.TEN)
        assertEquals(148, bytes.size)
        val value = QuantumRecoveryControlApproval.decode(bytes)
        assertEquals(operation, value.operation); assertEquals(maximum, value.observedBalance)
        assertEquals(BigInteger.TEN, value.estimatedFee); assertEquals(1030L, value.expiresAt)
        assertThrows(IllegalArgumentException::class.java) { record(end = 1031) }
        assertThrows(IllegalArgumentException::class.java) { record(end = 1000) }
        assertThrows(IllegalArgumentException::class.java) { record(start = 0, end = 1) }
        assertThrows(IllegalArgumentException::class.java) { record(start = 0xffffffffL, end = 0x100000000L) }
    }

    @Test fun alteredAmountsTruncationTailAndUnknownVersionFailClosed() {
        val bytes = record()
        assertThrows(IllegalArgumentException::class.java) { QuantumRecoveryControlApproval.decode(bytes.copyOf(bytes.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { QuantumRecoveryControlApproval.decode(bytes + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { QuantumRecoveryControlApproval.decode(bytes.copyOf().also { it[3] = 2 }) }
        // Fixed field offsets: header/UUID/digests/times = 100 bytes, then 3 unsigned 128-bit fields with 120-bit limit.
        assertThrows(IllegalArgumentException::class.java) { QuantumRecoveryControlApproval.decode(bytes.copyOf().also { it[100] = 1 }) }
        assertThrows(IllegalArgumentException::class.java) {
            QuantumRecoveryControlApproval.decode(bytes.copyOf().also { ByteBuffer.wrap(it).putLong(92, 1031) })
        }
        assertThrows(IllegalArgumentException::class.java) { record(after = BigInteger("101")) }
        assertThrows(IllegalArgumentException::class.java) { record(limit = BigInteger("9")) }
        assertThrows(IllegalArgumentException::class.java) { record(limit = BigInteger.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { record(before = BigInteger.ONE.shiftLeft(120)) }
    }
    @Test fun submissionAndUnknownRetainExactSignatureAndBoundApproval() {
        val seed = ByteArray(48) { 23 } // Public fixed test seed, no funds.
        try {
            val authority = QuantumSelfPayAuthority(3, ByteArray(32) { 43 }, ByteArray(1312), QuantumCrypto.publicKey(QuantumRole.RESCUE, seed))
            val identity = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(authority))
            val state = QuantumSelfPayState(authority)
            val request = TosQuantumSelfPayRequest(identity.address, state, QuantumAuthRole.RESCUE, 1120, QuantumSelfPayAction.LockPrimary, 1000)
            val signature = QuantumCrypto.sign(QuantumRole.RESCUE, QuantumPurpose.SELF_PAY, seed, authority.rescueKey, request.digest)
            val signed = QuantumRecoveryControlJournal.Entry(operation, UUID.randomUUID(), identity,
                QuantumNetworkProfile(3, "01".repeat(32), "02".repeat(32)), BagOfCells(state.cell).toByteArray(),
                BagOfCells(request.request).toByteArray(), QuantumRecoveryControlJournal.Phase.SIGNED, signature)
            signature.fill(0)
            val message = signed.signedMessage()
            val approval = QuantumRecoveryControlApproval.record(operation, java.security.MessageDigest.getInstance("SHA-256").digest(message),
                signed.requestHash, 1000, 1030, BigInteger("100"), BigInteger("90"), BigInteger("10"))
            for (phase in listOf(QuantumRecoveryControlJournal.Phase.SUBMITTING, QuantumRecoveryControlJournal.Phase.UNKNOWN)) {
                val restored = QuantumRecoveryControlJournal.Entry.decode(signed.next(phase, approval = approval).encode())
                assertEquals(phase, restored.phase); assertArrayEquals(message, restored.signedMessage())
                QuantumRecoveryControlApproval.decode(approval).requireBound(restored)
            }
            assertThrows(IllegalArgumentException::class.java) { signed.next(QuantumRecoveryControlJournal.Phase.SUBMITTING) }
            val wrongOperation = approval.copyOf().also { it[4] = (it[4].toInt() xor 1).toByte() }
            assertThrows(IllegalArgumentException::class.java) { signed.next(QuantumRecoveryControlJournal.Phase.SUBMITTING, approval = wrongOperation) }
            val wrongMessage = approval.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
            assertThrows(IllegalArgumentException::class.java) { signed.next(QuantumRecoveryControlJournal.Phase.UNKNOWN, approval = wrongMessage) }
        } finally { seed.fill(0) }
    }
}
