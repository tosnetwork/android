package network.tos.wallet.data.account.pq

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import network.tos.blockchain.ton.contract.*
import network.tos.security.pq.*
import org.json.JSONArray
import org.json.JSONObject
import org.ton.block.AddrStd
import org.ton.cell.Cell
import java.util.UUID

/** Initial public registry and authenticated role import. No current-authority/readiness or broadcast API. */
class QuantumWalletRepository(private val context: Context, private val codes: QuantumCodes, pins: QuantumCodePins,
                          private val authenticate: suspend () -> Boolean, namespace: String = "tos-quantum-wallets-v1", expectedNetwork: ByteArray? = null, private val expectedGlobalId: Int? = null) {
    private val expectedNetwork = expectedNetwork?.copyOf()
    init { require((expectedNetwork == null) == (expectedGlobalId == null)); require(expectedNetwork == null || expectedNetwork.size == 32) }
    class Record internal constructor(val id: String, val name: String, data: ByteArray, val address: AddrStd) {
        private val encoded = data.copyOf()
        fun publicManifest(): ByteArray = encoded.copyOf()
    }
    private val trusted = QuantumCodePins(pins.wallet.copyOf(), pins.module.copyOf(), pins.vault.copyOf())
    init { require(namespace.matches(Regex("[A-Za-z0-9._-]{1,128}"))) }
    private val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val vault = QuantumSeedVault(prefs)
    private suspend fun unlock() { check(authenticate()) { "R2 operation cancelled" } }
    private fun decode(value: String): ByteArray {
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(32) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    private fun requireChain(encoded: ByteArray) {
        expectedNetwork?.let { network ->
            val root = JSONObject(encoded.toString(Charsets.UTF_8))
            require(root.getInt("global_id") == expectedGlobalId && decode(root.getString("network")).contentEquals(network)) {
                "R2 manifest chain differs from fixed code candidate"
            }
        }
    }
    fun list(): List<Record> = synchronized(lock) {
        val encoded = checkNotNull(prefs.getString("registry.v1", "[]")); require(encoded.length <= 2 * 1024 * 1024)
        val array = JSONArray(encoded); require(array.length() <= 64)
        val records = (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            require(obj.keys().asSequence().toSet() == setOf("id", "name", "address", "manifest"))
            val id = obj.getString("id"); require(UUID.fromString(id).toString() == id)
            val name = obj.getString("name"); require(name.isNotBlank() && name.toByteArray().size <= 128)
            val base64 = obj.getString("manifest"); require(base64.length <= 22000)
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val address = AddrStd(0, decode(obj.getString("address")))
            TosQuantumInitialRecovery.parseAndReconstruct(bytes, codes, trusted, address)
            requireChain(bytes)
            Record(id, name, bytes, address)
        }
        require(records.map { it.id }.toSet().size == records.size)
        require(records.map { it.address }.toSet().size == records.size)
        records
    }
    private fun save(records: List<Record>) {
        val array = JSONArray()
        for (record in records) array.put(JSONObject().put("id", record.id).put("name", record.name)
            .put("address", record.address.address.toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) })
            .put("manifest", Base64.encodeToString(record.publicManifest(), Base64.NO_WRAP)))
        check(prefs.edit().putString("registry.v1", array.toString()).commit()) { "R2 registry persistence uncertain" }
    }
    suspend fun registerInitial(name: String, encoded: ByteArray, independentlyKnownWallet: AddrStd): Record {
        require(name.isNotBlank() && name.toByteArray().size <= 128)
        val initial = TosQuantumInitialRecovery.parseAndReconstruct(encoded, codes, trusted, independentlyKnownWallet)
        requireChain(initial.toJson())
        unlock()
        return synchronized(lock) {
            val records = list(); require(records.size < 64 && records.none { it.address == initial.genesis.address })
            val record = Record(UUID.randomUUID().toString(), name, initial.toJson(), independentlyKnownWallet)
            save(records + record); record
        }
    }
    /** Consumes master even on cancellation. Independently known wallet is required again; initial binding is not current authority. */
    suspend fun restoreInitialRole(id: String, independentlyKnownWallet: AddrStd, role: QuantumRole,
                                   master: ByteArray, inputProfile: QuantumSeedVault.MasterProfile): ByteArray {
        try {
            unlock()
            return synchronized(lock) {
                val record = list().single { it.id == id }
                require(record.address == independentlyKnownWallet)
                val manifest = record.publicManifest()
                val initial = TosQuantumInitialRecovery.parseAndReconstruct(manifest, codes, trusted, independentlyKnownWallet)
                val root = JSONObject(manifest.toString(Charsets.UTF_8))
                val publicKey = root.getString(if (role == QuantumRole.PRIMARY) "primary_key" else "rescue_key")
                    .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val d = initial.derivation
                val profile = if (role == QuantumRole.PRIMARY) d.primary else d.rescue
                val declared = if (profile == TosQuantumInitialRecovery.SeedProfile.NATIVE_MNEMONIC)
                    QuantumSeedVault.MasterProfile.NATIVE_MNEMONIC else QuantumSeedVault.MasterProfile.RAW_MASTER_32
                val ctx = QuantumSeedVault.Context(decode(root.getString("network")), root.getInt("global_id"), d.account, d.generation)
                vault.restoreDerivedAndWipe(id, role, ctx, QuantumDeviceKey.get(true), master, inputProfile, declared, publicKey)
            }
        } finally { master.fill(0) }
    }
    /** Locally authenticated anchor provisioning is mandatory. initialize is explicit first enrollment only. */
    suspend fun observeInitial(id: String, independentlyKnownWallet: AddrStd, locallyProvisionedAnchor: ByteArray,
                               initialize: Boolean, primaryExecution: Boolean, transport: QuantumProofTransport,
                               maximumAge: Long = 30): QuantumInstalledWallet {
        return observe(id, independentlyKnownWallet, locallyProvisionedAnchor, initialize, primaryExecution, transport, maximumAge, null)
    }
    /** Read a locally authenticated successor enrollment; this does not approve staging or migration. */
    suspend fun observeSuccessor(id: String, independentlyKnownWallet: AddrStd, locallyProvisionedAnchor: ByteArray,
                                 successor: TosQuantumGenesis, initialize: Boolean, primaryExecution: Boolean,
                                 transport: QuantumProofTransport, maximumAge: Long = 30): QuantumInstalledWallet {
        return observe(id, independentlyKnownWallet, locallyProvisionedAnchor, initialize, primaryExecution, transport, maximumAge, successor)
    }
    /** Generates unsigned AUTH only. Actions still need user review, solvency and current-key custody. */
    suspend fun preparePrimaryExecute(id: String, independentlyKnownWallet: AddrStd, locallyProvisionedAnchor: ByteArray,
                                     initialize: Boolean, actions: Cell, validUntil: Long, transport: QuantumProofTransport,
                                     maximumAge: Long = 30, successor: TosQuantumGenesis? = null): TosQuantumAuth =
        withProofCoordinator(id, independentlyKnownWallet, locallyProvisionedAnchor, transport, maximumAge, successor) {
            it.preparePrimaryExecute(initialize, actions, validUntil)
        }
    private suspend fun observe(id: String, independentlyKnownWallet: AddrStd, locallyProvisionedAnchor: ByteArray,
                                initialize: Boolean, primaryExecution: Boolean, transport: QuantumProofTransport,
                                maximumAge: Long, successor: TosQuantumGenesis?): QuantumInstalledWallet =
        withProofCoordinator(id, independentlyKnownWallet, locallyProvisionedAnchor, transport, maximumAge, successor) {
            it.observe(initialize, primaryExecution)
        }
    /** Initial custody is checked only after proving the currently installed module.
     * Returns unsigned AUTH; fee reservation, solvency and action approval remain required before signing. */
    suspend fun prepareInitialPrimaryWithCustody(id: String, independentlyKnownWallet: AddrStd,
                                                locallyProvisionedAnchor: ByteArray, initialize: Boolean,
                                                actions: Cell, validUntil: Long, transport: QuantumProofTransport,
                                                maximumAge: Long = 30): TosQuantumAuth =
        withProofCoordinator(id, independentlyKnownWallet, locallyProvisionedAnchor, transport, maximumAge, null) { worker ->
            val record = list().single { it.id == id }
            require(record.address == independentlyKnownWallet)
            val manifest = record.publicManifest()
            val initial = TosQuantumInitialRecovery.parseAndReconstruct(manifest, codes, trusted, independentlyKnownWallet)
            val root = JSONObject(manifest.toString(Charsets.UTF_8))
            val custody = QuantumSeedVault.Context(decode(root.getString("network")), root.getInt("global_id"),
                initial.derivation.account, initial.derivation.generation)
            worker.preparePrimaryExecute(initialize, actions, validUntil) {
                vault.publicKey(id, QuantumRole.PRIMARY, custody, QuantumDeviceKey.get(true))
            }
        }
    private suspend fun <T> withProofCoordinator(id: String, independentlyKnownWallet: AddrStd, locallyProvisionedAnchor: ByteArray,
                                               transport: QuantumProofTransport, maximumAge: Long, successor: TosQuantumGenesis?,
                                               action: (QuantumProofCoordinator) -> T): T {
        require(maximumAge in 1..3599 && locallyProvisionedAnchor.size in 1..1_048_576)
        val anchor = locallyProvisionedAnchor.copyOf()
        unlock()
        val record = list().single { it.id == id }
        require(record.address == independentlyKnownWallet)
        val manifest = record.publicManifest()
        val initial = TosQuantumInitialRecovery.parseAndReconstruct(manifest, codes, trusted, independentlyKnownWallet)
        requireChain(manifest)
        return runInterruptible(Dispatchers.IO) {
            val session = QuantumProofSession(context, UUID.fromString(record.id), anchor)
            action(QuantumProofCoordinator(session, initial.genesis, transport,
                { System.currentTimeMillis() / 1000 }, maximumAge, successor))
        }
    }
    companion object { private val lock = Any() }
}
