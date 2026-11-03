package network.tos.wallet.data.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.tos.blockchain.TosQuantumMnemonic
import network.tos.blockchain.ton.contract.*
import network.tos.security.pq.*
import network.tos.wallet.data.account.pq.QuantumCreationCustody
import network.tos.wallet.data.account.privacy.PrivacyMasterUnavailable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Existing public native-TOS vector only; production encrypted preferences, never user wallet data. */
@RunWith(AndroidJUnit4::class)
class PrivacyOriginalMasterDeviceTest {
    private val words = "twice half glass steak version only friend bone addict during joke offer fog punch panel true regular letter topple often afraid hint ready exhibit".split(" ")
    private class Material(words: List<String>) : AutoCloseable {
        val master = TosQuantumMnemonic.masterAndWipePassword(words, CharArray(0))
        val namespace = ByteArray(32) { 7 }
        val primary = QuantumKdf.deriveAndWipe(QuantumKdf.Material.PRIMARY, master.copyOf(), namespace, 3, 0, 0)
        private val rescue = QuantumKdf.deriveAndWipe(QuantumKdf.Material.RESCUE, master.copyOf(), namespace, 3, 0, 0)
        val birth = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(QuantumSelfPayAuthority(3, namespace,
            QuantumCrypto.publicKey(QuantumRole.PRIMARY, primary), QuantumCrypto.publicKey(QuantumRole.RESCUE, rescue))))
        override fun close() { master.fill(0); primary.fill(0); rescue.fill(0); namespace.fill(0) }
    }

    @Test fun protectedOriginalMasterBindsBirthAndWipesAfterSuccessOrFailure() {
        val custody = QuantumCreationCustody(InstrumentationRegistry.getInstrumentation().targetContext)
        val id = UUID.randomUUID()
        Material(words).use { material ->
            try {
                custody.saveMnemonic(id, words)
                // A different stored signing key must not change which original master is used.
                custody.store(id, QuantumRole.PRIMARY, QuantumSeedVault.Context(material.namespace, 3, 0, 1), ByteArray(32) { 77 })
                var borrowed: ByteArray? = null
                val result = custody.withOriginalPrivacyMaster(id, material.birth) {
                    borrowed = it; assertArrayEquals(material.master, it); 7
                }
                assertEquals(7, result); assertArrayEquals(ByteArray(32), borrowed)
                val thrown = assertThrows(IllegalArgumentException::class.java) {
                    custody.withOriginalPrivacyMaster(id, material.birth) { borrowed = it; throw IllegalArgumentException("public test callback") }
                }
                assertEquals("public test callback", thrown.message); assertArrayEquals(ByteArray(32), borrowed)
                val original = material.birth.wallet.initialAuthority
                val wrongBirth = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(QuantumSelfPayAuthority(3,
                    ByteArray(32) { 8 }, original.primaryKey, original.rescueKey)))
                val error = assertThrows(IllegalStateException::class.java) {
                    custody.withOriginalPrivacyMaster(id, wrongBirth) { fail("Mismatched original master reached consumer") }
                }
                assertEquals("Original privacy master differs from birth provenance", error.message)
            } finally { custody.delete(id) }
        }
    }

    @Test fun roleOnlyMaterialDoesNotBecomeAnOriginalMasterOrGenerateAnIdentity() {
        val custody = QuantumCreationCustody(InstrumentationRegistry.getInstrumentation().targetContext)
        val id = UUID.randomUUID()
        Material(words).use { material ->
            try {
                custody.store(id, QuantumRole.PRIMARY, QuantumSeedVault.Context(material.namespace, 3, 0, 0), material.primary.copyOf())
                assertFalse(custody.hasOriginalPrivacyMaster(id))
                assertThrows(PrivacyMasterUnavailable::class.java) {
                    custody.withOriginalPrivacyMaster(id, material.birth) { fail("Role seed was substituted for original master") }
                }
                assertFalse(custody.hasOriginalPrivacyMaster(id))
            } finally { custody.delete(id) }
        }
    }
    @Test fun cancelledObservationClosesItsLeaseWithoutTransportOrMasterAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Material(words).use { material ->
            val id = UUID.randomUUID()
            val profile = network.tos.wallet.data.account.pq.QuantumNetworkProfile(3, "11".repeat(32), "22".repeat(32))
            val selection = network.tos.wallet.data.account.privacy.PrivacyPoolSelection(profile, "0:" + "33".repeat(32),
                android.util.Base64.decode("te6ccgEBAQEAAgAAAA==", android.util.Base64.NO_WRAP),
                "44".repeat(32), "55".repeat(32), "66".repeat(32))
            var transportCalls = 0; var masterCalls = 0
            val transport = QuantumProofTransport { _, _ -> transportCalls++; error("Cancelled reader reached transport") }
            val reader = network.tos.wallet.data.account.pq.PrivacyBalanceReader(
                network.tos.wallet.data.account.pq.QuantumAccountReader(context, transport),
                network.tos.wallet.data.account.pq.QuantumHistoryReader.Query { _, _, _ -> error("Cancelled reader reached history") })
            repeat(3) {
                assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                    reader.read(network.tos.wallet.data.account.pq.QuantumCreationRecord(id, profile, material.birth), selection, { true }) {
                        masterCalls++; error("Cancelled reader reached master")
                    }
                }
                // Same-wallet acquisition after each cancellation proves cleanup did not strand the slot.
                network.tos.wallet.data.account.pq.PrivacyHistorySynchronizer.Lease(id).close()
            }
            assertEquals(0, transportCalls); assertEquals(0, masterCalls)
        }
    }

    @Test fun borrowedMasterDoesNotBlockAnotherCustodyRead() {
        val custody = QuantumCreationCustody(InstrumentationRegistry.getInstrumentation().targetContext)
        val id = UUID.randomUUID()
        val executor = Executors.newSingleThreadExecutor()
        Material(words).use { material ->
            try {
                custody.saveMnemonic(id, words)
                custody.withOriginalPrivacyMaster(id, material.birth) {
                    val materialPresent = executor.submit<Boolean> { custody.hasOriginalPrivacyMaster(id) }
                    try {
                        assertTrue(materialPresent.get(3, TimeUnit.SECONDS))
                    } catch (_: TimeoutException) {
                        fail("Another custody read was blocked by the borrowed-master callback")
                    }
                }
            } finally {
                executor.shutdownNow()
                custody.delete(id)
            }
        }
    }

}
