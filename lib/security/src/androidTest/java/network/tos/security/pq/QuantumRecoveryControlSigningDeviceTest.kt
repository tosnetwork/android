package network.tos.security.pq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import network.tos.blockchain.ton.contract.*
import org.ton.boc.BagOfCells
import org.ton.cell.buildCell
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import java.math.BigInteger

/** Fixed public seeds and software AES test keys only. Not OS authentication or live chain acceptance. */
@RunWith(AndroidJUnit4::class)
class QuantumRecoveryControlSigningDeviceTest {
    private fun fixture(selected: Set<QuantumRole> = setOf(QuantumRole.RESCUE),
        body: (QuantumRecoveryMaterialVault, UUID, QuantumBackupInspection, SecretKey) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("control-sign-test-${UUID.randomUUID()}", 0)
        val primarySeed = ByteArray(32) { 17 }; val rescueSeed = ByteArray(48) { 23 }
        val primary = QuantumCrypto.publicKey(QuantumRole.PRIMARY, primarySeed)
        val rescue = QuantumCrypto.publicKey(QuantumRole.RESCUE, rescueSeed)
        val authority = QuantumSelfPayAuthority(3, ByteArray(32) { 43 }, primary, rescue)
        val identity = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(authority))
        val roles = selected.map { role ->
            val seed = if (role == QuantumRole.PRIMARY) primarySeed else rescueSeed
            val key = if (role == QuantumRole.PRIMARY) primary else rescue
            QuantumBackupPayload.Role(role.id, 0, 0, seed.copyOf(), MessageDigest.getInstance("SHA-256").digest(key),
                1, intArrayOf(), byteArrayOf(), byteArrayOf())
        }
        val plain = QuantumBackupPayload.encodeUnverified(3, ByteArray(32) { 1 }, ByteArray(32) { 2 },
            authority.namespace, identity.toBoc(), BagOfCells(authority.cell).toByteArray(), roles)
        try {
            val encrypted = QuantumBackupCipher.encrypt(plain, "public control fixture password".toCharArray())
            val inspection = QuantumBackupInspection.inspect(encrypted, "public control fixture password".toCharArray(),
                3, ByteArray(32) { 1 }, ByteArray(32) { 2 }, authority.namespace)
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val id = UUID.randomUUID(); val vault = QuantumRecoveryMaterialVault(prefs)
            vault.storeValidated(encrypted, "public control fixture password".toCharArray(), inspection, id, key)
            encrypted.fill(0); plain.fill(0)
            body(vault, id, inspection, key)
        } finally {
            plain.fill(0); roles.forEach { it.wipe() }; primarySeed.fill(0); rescueSeed.fill(0)
            check(prefs.edit().clear().commit())
        }
    }
    private fun request(inspection: QuantumBackupInspection, state: QuantumSelfPayState, action: QuantumSelfPayAction,
                        role: QuantumAuthRole = QuantumAuthRole.RESCUE) =
        TosQuantumSelfPayRequest(inspection.birth.address, state, role, 1120, action, 1000)

    @Test fun rescueOnlyMaterialSignsBothControlsInSelfPayDomainAfterPrimaryRotation() = fixture { vault, id, inspection, key ->
        val old = inspection.snapshot
        val current = QuantumSelfPayAuthority(3, old.namespace, ByteArray(1312) { 45 }, old.rescueKey, QuantumPolicy.REQUIRED)
        val state = QuantumSelfPayState(current, seqno = 7, retired = 2, epoch = 4u, rescueNonce = 9u)
        for (action in listOf(QuantumSelfPayAction.LockPrimary, QuantumSelfPayAction.ConfigureRequired)) {
            val prepared = request(inspection, state, action)
            val signature = vault.signRecoveryControl(id, inspection, key, state, BagOfCells(prepared.request).toByteArray(), 1000)
            try {
                assertEquals(7856, signature.size)
                assertTrue(QuantumCrypto.verify(QuantumRole.RESCUE, QuantumPurpose.SELF_PAY, current.rescueKey, prepared.digest, signature))
                assertFalse(QuantumCrypto.verify(QuantumRole.RESCUE, QuantumPurpose.AUTH, current.rescueKey, prepared.digest, signature))
            } finally { signature.fill(0) }
        }
    }

    @Test fun stateMismatchExpiredRequestAndUnrestrictedActionsCannotSign() = fixture { vault, id, inspection, key ->
        val state = QuantumSelfPayState(inspection.snapshot, seqno = 5, epoch = 2u, rescueNonce = 6u)
        val lock = request(inspection, state, QuantumSelfPayAction.LockPrimary)
        val bytes = BagOfCells(lock.request).toByteArray()
        assertThrows(IllegalArgumentException::class.java) { vault.signRecoveryControl(id, inspection, key, state, bytes, 1120) }
        assertThrows(IllegalArgumentException::class.java) {
            vault.signRecoveryControl(id, inspection, key, QuantumSelfPayState(inspection.snapshot, seqno = 5, epoch = 2u, rescueNonce = 7u), bytes, 1000)
        }
        val rotated = QuantumSelfPayAuthority(3, inspection.snapshot.namespace, inspection.snapshot.primaryKey, ByteArray(32) { 99 })
        val rotatedState = QuantumSelfPayState(rotated, epoch = 3u)
        val rotatedRequest = request(inspection, rotatedState, QuantumSelfPayAction.LockPrimary)
        assertThrows(IllegalArgumentException::class.java) {
            vault.signRecoveryControl(id, inspection, key, rotatedState, BagOfCells(rotatedRequest.request).toByteArray(), 1000)
        }
        val successor = QuantumSelfPayAuthority(3, inspection.snapshot.namespace, inspection.snapshot.primaryKey,
            inspection.snapshot.rescueKey, QuantumPolicy.REQUIRED)
        for (action in listOf(QuantumSelfPayAction.Execute(buildCell {}), QuantumSelfPayAction.Migrate(successor))) {
            val unrestricted = request(inspection, state, action)
            assertThrows(IllegalArgumentException::class.java) {
                vault.signRecoveryControl(id, inspection, key, state, BagOfCells(unrestricted.request).toByteArray(), 1000)
            }
        }
    }

    @Test fun primaryOnlyBackupCannotSupplyRescueControl() = fixture(setOf(QuantumRole.PRIMARY)) { vault, id, inspection, key ->
        val state = QuantumSelfPayState(inspection.snapshot)
        val prepared = request(inspection, state, QuantumSelfPayAction.LockPrimary)
        assertThrows(IllegalArgumentException::class.java) {
            vault.signRecoveryControl(id, inspection, key, state, BagOfCells(prepared.request).toByteArray(), 1000)
        }
    }
    private class Funding(val state: QuantumSelfPayState, val successor: QuantumSelfPayAuthority,
        val challenge: ByteArray, val primary: ByteArray, val rescue: ByteArray, val request: ByteArray,
        val digest: ByteArray, val amount: BigInteger)
    private fun funding(inspection: QuantumBackupInspection, body: (Funding) -> Unit) {
        val primarySeed = ByteArray(32) { 19 }; val rescueSeed = ByteArray(48) { 29 }
        val successor = QuantumSelfPayAuthority(3, inspection.snapshot.namespace,
            QuantumCrypto.publicKey(QuantumRole.PRIMARY, primarySeed), QuantumCrypto.publicKey(QuantumRole.RESCUE, rescueSeed), QuantumPolicy.REQUIRED)
        val state = QuantumSelfPayState(inspection.snapshot, seqno = 7, retired = 2, epoch = 4u, rescueNonce = 9u)
        val challenge = ByteArray(32) { 11 }
        val probe = TosQuantumSelfPayProbe(inspection.birth.address, successor, challenge, 1120, 1000)
        val primary = QuantumCrypto.sign(QuantumRole.PRIMARY, QuantumPurpose.SELF_PAY_POP, primarySeed, successor.primaryKey, probe.digest)
        val rescue = QuantumCrypto.sign(QuantumRole.RESCUE, QuantumPurpose.SELF_PAY_POP, rescueSeed, successor.rescueKey, probe.digest)
        val amount = BigInteger("10000000")
        val request = TosQuantumSelfPayRequest(inspection.birth.address, state, QuantumAuthRole.RESCUE, 1100,
            QuantumSelfPayAction.Execute(probe.fundingActions(probe.submission(primary, rescue), amount)), 1000)
        try { body(Funding(state, successor, challenge, primary, rescue, BagOfCells(request.request).toByteArray(), request.digest, amount)) }
        finally { primarySeed.fill(0); rescueSeed.fill(0); primary.fill(0); rescue.fill(0) }
    }
    @Test fun protectedMaterialSignsOnlyTheExactBoundProbeFunding() = fixture { vault, id, inspection, key ->
        funding(inspection) { p ->
            val signature = vault.signProbeFunding(id, inspection, key, p.state, p.successor, p.challenge,
                1120, p.primary, p.rescue, p.amount, p.request, 1000)
            try {
                assertTrue(QuantumCrypto.verify(QuantumRole.RESCUE, QuantumPurpose.SELF_PAY,
                    p.state.authority.rescueKey, p.digest, signature))
                assertFalse(QuantumCrypto.verify(QuantumRole.RESCUE, QuantumPurpose.SELF_PAY_POP,
                    p.state.authority.rescueKey, p.digest, signature))
            } finally { signature.fill(0) }
        }
    }
    @Test fun probeFundingRejectsChangedIntentProofAndState() = fixture { vault, id, inspection, key ->
        funding(inspection) { p ->
            fun sign(amount: BigInteger = p.amount, challenge: ByteArray = p.challenge,
                     state: QuantumSelfPayState = p.state, now: Long = 1000): ByteArray =
                vault.signProbeFunding(id, inspection, key, state, p.successor, challenge,
                    1120, p.primary, p.rescue, amount, p.request, now)
            assertThrows(IllegalArgumentException::class.java) { sign(amount = p.amount + BigInteger.ONE) }
            assertThrows(IllegalArgumentException::class.java) { sign(amount = BigInteger("100000001")) }
            assertThrows(IllegalArgumentException::class.java) { sign(amount = BigInteger.ZERO) }
            assertThrows(IllegalArgumentException::class.java) { sign(challenge = ByteArray(32) { 12 }) }
            assertThrows(IllegalArgumentException::class.java) { sign(now = 1100) }
            assertThrows(IllegalArgumentException::class.java) { sign(now = 1120) }
            assertThrows(IllegalArgumentException::class.java) { sign(state = QuantumSelfPayState(inspection.snapshot)) }
            assertThrows(IllegalArgumentException::class.java) { sign(state = QuantumSelfPayState(inspection.snapshot,
                seqno = 7, retired = 2, epoch = 4u, rescueNonce = 10u)) }
            p.primary[0] = (p.primary[0].toInt() xor 1).toByte()
            assertThrows(IllegalArgumentException::class.java) { sign() }
        }
    }
    @Test fun migrationSignsExactReplacementInSelfPayDomain() = fixture { vault, id, inspection, key ->
        funding(inspection) { p ->
            val prepared = request(inspection, p.state, QuantumSelfPayAction.Migrate(p.successor))
            val signature = vault.signSuccessorMigration(id, inspection, key, p.state, p.successor,
                BagOfCells(prepared.request).toByteArray(), 1000)
            try {
                assertTrue(QuantumCrypto.verify(QuantumRole.RESCUE, QuantumPurpose.SELF_PAY,
                    p.state.authority.rescueKey, prepared.digest, signature))
                assertFalse(QuantumCrypto.verify(QuantumRole.RESCUE, QuantumPurpose.SELF_PAY_POP,
                    p.state.authority.rescueKey, prepared.digest, signature))
            } finally { signature.fill(0) }
        }
    }

    @Test fun migrationRejectsChangedReplacementStateDeadlineAndControlRequest() = fixture { vault, id, inspection, key ->
        funding(inspection) { p ->
            val prepared = request(inspection, p.state, QuantumSelfPayAction.Migrate(p.successor))
            val bytes = BagOfCells(prepared.request).toByteArray()
            fun sign(state: QuantumSelfPayState = p.state, successor: QuantumSelfPayAuthority = p.successor,
                request: ByteArray = bytes, now: Long = 1000) =
                vault.signSuccessorMigration(id, inspection, key, state, successor, request, now)
            assertThrows(IllegalArgumentException::class.java) { sign(now = 1120) }
            assertThrows(IllegalArgumentException::class.java) { sign(now = 999) }
            assertThrows(IllegalArgumentException::class.java) { sign(state = QuantumSelfPayState(inspection.snapshot)) }
            assertThrows(IllegalArgumentException::class.java) { sign(state = QuantumSelfPayState(inspection.snapshot,
                seqno = 7, retired = 2, epoch = 4u, rescueNonce = 10u)) }
            val changed = QuantumSelfPayAuthority(3, p.successor.namespace, ByteArray(1312) { 45 },
                p.successor.rescueKey, QuantumPolicy.REQUIRED)
            assertThrows(IllegalArgumentException::class.java) { sign(successor = changed) }
            val lock = request(inspection, p.state, QuantumSelfPayAction.LockPrimary)
            assertThrows(IllegalArgumentException::class.java) { sign(request = BagOfCells(lock.request).toByteArray()) }
        }
    }

    @Test fun primaryOnlyMaterialCannotSignMigration() = fixture(setOf(QuantumRole.PRIMARY)) { vault, id, inspection, key ->
        funding(inspection) { p ->
            val prepared = request(inspection, p.state, QuantumSelfPayAction.Migrate(p.successor))
            assertThrows(IllegalArgumentException::class.java) {
                vault.signSuccessorMigration(id, inspection, key, p.state, p.successor,
                    BagOfCells(prepared.request).toByteArray(), 1000)
            }
        }
    }

    @Test fun primaryOnlyMaterialCannotFundProbe() = fixture(setOf(QuantumRole.PRIMARY)) { vault, id, inspection, key ->
        funding(inspection) { p ->
            assertThrows(IllegalArgumentException::class.java) {
                vault.signProbeFunding(id, inspection, key, p.state, p.successor, p.challenge,
                    1120, p.primary, p.rescue, p.amount, p.request, 1000)
            }
        }
    }
}
