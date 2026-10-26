package network.tos.wallet.data.account.pq

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import network.tos.blockchain.TosQuantumMnemonic
import network.tos.security.pq.QuantumCrypto
import network.tos.security.pq.QuantumKdf
import network.tos.security.pq.QuantumRole
import network.tos.security.pq.QuantumSeedVault
import java.util.UUID

/** Production creation ordering, with storage and proof boundaries injected for failure testing.
 * Both roles share this device. Independent rescue custody needs a separate explicit export flow. */
internal class QuantumWalletCreation(
    private val network: suspend (UUID, QuantumNetworkProfile) -> QuantumSelfPayNetwork,
    private val saveRecord: (QuantumCreationRecord) -> Unit,
    private val saveMnemonic: (UUID, List<String>) -> Unit,
    private val storeRole: (UUID, QuantumRole, QuantumSeedVault.Context, ByteArray) -> ByteArray,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    suspend fun <T> create(words: List<String>, profile: QuantumNetworkProfile, publish: suspend (QuantumCreationRecord) -> T): T {
        require(words.size == 24) { "New Quantum wallets require 24 native mnemonic words" }
        val id = UUID.randomUUID()
        val bound = network(id, profile)
        require(bound.globalId == profile.globalId) { "Creation network global ID mismatch" }
        currentCoroutineContext().ensureActive()
        var master = ByteArray(0)
        var primary = ByteArray(0)
        var rescue = ByteArray(0)
        try {
            master = TosQuantumMnemonic.masterAndWipePassword(words, CharArray(0))
            primary = QuantumKdf.deriveAndWipe(QuantumKdf.Material.PRIMARY, master.copyOf(), bound.namespace, bound.globalId, 0, 0)
            rescue = QuantumKdf.deriveAndWipe(QuantumKdf.Material.RESCUE, master.copyOf(), bound.namespace, bound.globalId, 0, 0)
            val primaryKey = QuantumCrypto.publicKey(QuantumRole.PRIMARY, primary)
            val rescueKey = QuantumCrypto.publicKey(QuantumRole.RESCUE, rescue)
            val identity = bound.makeIdentity(primaryKey, rescueKey, now = now())
            val record = QuantumCreationRecord(id, profile, identity)
            val context = QuantumSeedVault.Context(bound.namespace, bound.globalId, 0, 0)
            currentCoroutineContext().ensureActive()
            saveRecord(record)
            saveMnemonic(id, words)
            currentCoroutineContext().ensureActive()
            require(storeRole(id, QuantumRole.PRIMARY, context, primary).contentEquals(primaryKey)) { "Stored PRIMARY differs from enrollment" }
            require(storeRole(id, QuantumRole.RESCUE, context, rescue).contentEquals(rescueKey)) { "Stored RESCUE differs from enrollment" }
            bound.requireFresh(now())
            currentCoroutineContext().ensureActive()
            return publish(record)
        } finally { master.fill(0); primary.fill(0); rescue.fill(0) }
    }
}
