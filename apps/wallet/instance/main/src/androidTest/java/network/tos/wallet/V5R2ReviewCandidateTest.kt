package network.tos.wallet

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import network.tos.wallet.data.account.pq.V5R2ReviewCandidate
import network.tos.wallet.data.account.pq.V5R2WalletRepository
import network.tos.blockchain.ton.contract.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class V5R2ReviewCandidateTest {
    @Test fun actualCandidateCodesArePinnedAndOtherNetworksAreRefused() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val (codes, pins) = V5R2ReviewCandidate.load(app)
        assertEquals(18, V5R2ReviewCandidate.minimumVm)
        assertEquals("06203e98d4d8bf97b0cab37523ec435f5ab0e1d6d2c5253926eebf20ef1712f5", codes.wallet.hash().toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) })
        val namespace = "v5r2-candidate-qa-${UUID.randomUUID()}"
        val prefs = app.getSharedPreferences(namespace, 0)
        val repository = V5R2WalletRepository(app, codes, pins, { true }, namespace,
            V5R2ReviewCandidate.network, V5R2ReviewCandidate.globalId)
        val fee = ("000000010000000800000003" + "33".repeat(16) + "44".repeat(32)).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val profile = TosV5R2InitialRecovery.SeedProfile.RAW_MASTER_32
        fun manifest(network: ByteArray) = TosV5R2InitialRecovery.prepare(codes, pins, 1, network, 42,
            ByteArray(1312) { 0x11 }, ByteArray(32) { 0x22 }, V5R2Policy.REQUIRED, ByteArray(32) { 7 }, fee, 100,
            TosV5R2InitialRecovery.Derivation(0, 0, profile, profile, profile))
        try {
            val wrong = manifest(ByteArray(32) { 0x43 })
            val error = runCatching { repository.registerInitial("wrong network", wrong.toJson(), wrong.genesis.address) }.exceptionOrNull()
            assertEquals("R2 manifest chain differs from fixed code candidate", error?.message)
            assertTrue(repository.list().isEmpty())
            val right = manifest(V5R2ReviewCandidate.network)
            assertEquals(right.genesis.address, repository.registerInitial("candidate only", right.toJson(), right.genesis.address).address)
        } finally { check(prefs.edit().clear().commit()) }
    }
}
