package network.tos.wallet

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import network.tos.wallet.data.account.pq.QuantumReviewCandidate
import network.tos.wallet.data.account.pq.QuantumWalletRepository
import network.tos.blockchain.ton.contract.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class QuantumReviewCandidateTest {
    @Test fun actualCandidateCodesArePinnedAndOtherNetworksAreRefused() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val (codes, pins) = QuantumReviewCandidate.load(app)
        assertEquals(18, QuantumReviewCandidate.minimumVm)
        assertEquals("06203e98d4d8bf97b0cab37523ec435f5ab0e1d6d2c5253926eebf20ef1712f5", codes.wallet.hash().toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) })
        val namespace = "quantum-candidate-qa-${UUID.randomUUID()}"
        val prefs = app.getSharedPreferences(namespace, 0)
        val repository = QuantumWalletRepository(app, codes, pins, { true }, namespace,
            QuantumReviewCandidate.network, QuantumReviewCandidate.globalId)
        val fee = ("000000010000000800000003" + "33".repeat(16) + "44".repeat(32)).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val profile = TosQuantumInitialRecovery.SeedProfile.RAW_MASTER_32
        fun manifest(network: ByteArray) = TosQuantumInitialRecovery.prepare(codes, pins, 1, network, 42,
            ByteArray(1312) { 0x11 }, ByteArray(32) { 0x22 }, QuantumPolicy.REQUIRED, ByteArray(32) { 7 }, fee, 100,
            TosQuantumInitialRecovery.Derivation(0, 0, profile, profile, profile))
        try {
            val wrong = manifest(ByteArray(32) { 0x43 })
            val error = runCatching { repository.registerInitial("wrong network", wrong.toJson(), wrong.genesis.address) }.exceptionOrNull()
            assertEquals("R2 manifest chain differs from fixed code candidate", error?.message)
            assertTrue(repository.list().isEmpty())
            val right = manifest(QuantumReviewCandidate.network)
            assertEquals(right.genesis.address, repository.registerInitial("candidate only", right.toJson(), right.genesis.address).address)
        } finally { check(prefs.edit().clear().commit()) }
    }
}
