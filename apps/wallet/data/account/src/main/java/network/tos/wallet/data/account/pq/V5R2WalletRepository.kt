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
class V5R2WalletRepository(private val context: Context, private val codes: V5R2Codes, pins: V5R2CodePins,
                          private val authenticate: suspend () -> Boolean, namespace: String = "tos-v5r2-wallets-v1", expectedNetwork: ByteArray? = null, private val expectedGlobalId: Int? = null) {
    private val expectedNetwork = expectedNetwork?.copyOf()
    init { require((expectedNetwork == null) == (expectedGlobalId == null)); require(expectedNetwork == null || expectedNetwork.size == 32) }
    class Record internal constructor(val id: String, val name: String, data: ByteArray, val address: AddrStd) {
        private val encoded = data.copyOf()
        fun publicManifest(): ByteArray = encoded.copyOf()
    }
    private val trusted = V5R2CodePins(pins.wallet.copyOf(), pins.module.copyOf(), pins.vault.copyOf())
    init { require(namespace.matches(Regex("[A-Za-z0-9._-]{1,128}"))) }
    private val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val vault = V5R2SeedVault(prefs)
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
            TosV5R2InitialRecovery.parseAndReconstruct(bytes, codes, trusted, address)
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
        val initial = TosV5R2InitialRecovery.parseAndReconstruct(encoded, codes, trusted, independentlyKnownWallet)
        requireChain(initial.toJson())
        unlock()
        return synchronized(lock) {
            val records = list(); require(records.size < 64 && records.none { it.address == initial.genesis.address })
            val record = Record(UUID.randomUUID().toString(), name, initial.toJson(), independentlyKnownWallet)
            save(records + record); record
        }
    }
    /** Consumes master even on cancellation. Independently known wallet is required again; initial binding is not current authority. */
    suspend fun restoreInitialRole(id: String, independentlyKnownWallet: AddrStd, role: V5R2Role,
                                   master: ByteArray, inputProfile: V5R2SeedVault.MasterProfile): ByteArray {
        try {
            unlock()
            return synchronized(lock) {
                val record = list().single { it.id == id }
                require(record.address == independentlyKnownWallet)
                val manifest = record.publicManifest()
                val initial = TosV5R2InitialRecovery.parseAndReconstruct(manifest, codes, trusted, independentlyKnownWallet)
                val root = JSONObject(manifest.toString(Charsets.UTF_8))
                val publicKey = root.getString(if (role == V5R2Role.PRIMARY) "primary_key" else "rescue_key")
                    .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val d = initial.derivation
                val profile = if (role == V5R2Role.PRIMARY) d.primary else d.rescue
                val declared = if (profile == TosV5R2InitialRecovery.SeedProfile.NATIVE_MNEMONIC)
                    V5R2SeedVault.MasterProfile.NATIVE_MNEMONIC else V5R2SeedVault.MasterProfile.RAW_MASTER_32
                val ctx = V5R2SeedVault.Context(decode(root.getString("network")), root.getInt("global_id"), d.account, d.generation)
                vault.restoreDerivedAndWipe(id, role, ctx, V5R2DeviceKey.get(true), master, inputProfile, declared, publicKey)
            }
        } finally { master.fill(0) }
    }
    /** Locally authenticated anchor provisioning is mandatory. initialize is explicit first enrollment only. */
    suspend fun observeInitial(id: String, independentlyKnownWallet: AddrStd, locallyProvisionedAnchor: ByteArray,
                               initialize: Boolean, primaryExecution: Boolean, transport: V5R2ProofTransport,
                               maximumAge: Long = 30): V5R2InstalledWallet {
        return observe(id, independentlyKnownWallet, locallyProvisionedAnchor, initialize, primaryExecution, transport, maximumAge, null)
    }
    /** Read a locally authenticated successor enrollment; this does not approve staging or migration. */
    suspend fun observeSuccessor(id: String, independentlyKnownWallet: AddrStd, locallyProvisionedAnchor: ByteArray,
                                 successor: TosV5R2Genesis, initialize: Boolean, primaryExecution: Boolean,
                                 transport: V5R2ProofTransport, maximumAge: Long = 30): V5R2InstalledWallet {
        return observe(id, independentlyKnownWallet, locallyProvisionedAnchor, initialize, primaryExecution, transport, maximumAge, successor)
    }
    /** Generates unsigned AUTH only. Actions still need user review, solvency and current-key custody. */
    suspend fun preparePrimaryExecute(id: String, independentlyKnownWallet: AddrStd, locallyProvisionedAnchor: ByteArray,
                                     initialize: Boolean, actions: Cell, validUntil: Long, transport: V5R2ProofTransport,
                                     maximumAge: Long = 30, successor: TosV5R2Genesis? = null): TosV5R2Auth =
        withProofCoordinator(id, independentlyKnownWallet, locallyProvisionedAnchor, transport, maximumAge, successor) {
            it.preparePrimaryExecute(initialize, actions, validUntil)
        }
    private suspend fun observe(id: String, independentlyKnownWallet: AddrStd, locallyProvisionedAnchor: ByteArray,
                                initialize: Boolean, primaryExecution: Boolean, transport: V5R2ProofTransport,
                                maximumAge: Long, successor: TosV5R2Genesis?): V5R2InstalledWallet =
        withProofCoordinator(id, independentlyKnownWallet, locallyProvisionedAnchor, transport, maximumAge, successor) {
            it.observe(initialize, primaryExecution)
        }
    /** Initial custody is checked only after proving the currently installed module.
     * Returns unsigned AUTH; fee reservation, solvency and action approval remain required before signing. */
    suspend fun prepareInitialPrimaryWithCustody(id: String, independentlyKnownWallet: AddrStd,
                                                locallyProvisionedAnchor: ByteArray, initialize: Boolean,
                                                actions: Cell, validUntil: Long, transport: V5R2ProofTransport,
                                                maximumAge: Long = 30): TosV5R2Auth =
        withProofCoordinator(id, independentlyKnownWallet, locallyProvisionedAnchor, transport, maximumAge, null) { worker ->
            val record = list().single { it.id == id }
            require(record.address == independentlyKnownWallet)
            val manifest = record.publicManifest()
            val initial = TosV5R2InitialRecovery.parseAndReconstruct(manifest, codes, trusted, independentlyKnownWallet)
            val root = JSONObject(manifest.toString(Charsets.UTF_8))
            val custody = V5R2SeedVault.Context(decode(root.getString("network")), root.getInt("global_id"),
                initial.derivation.account, initial.derivation.generation)
            worker.preparePrimaryExecute(initialize, actions, validUntil) {
                vault.publicKey(id, V5R2Role.PRIMARY, custody, V5R2DeviceKey.get(true))
            }
        }
    private suspend fun <T> withProofCoordinator(id: String, independentlyKnownWallet: AddrStd, locallyProvisionedAnchor: ByteArray,
                                               transport: V5R2ProofTransport, maximumAge: Long, successor: TosV5R2Genesis?,
                                               action: (V5R2ProofCoordinator) -> T): T {
        require(maximumAge in 1..3599 && locallyProvisionedAnchor.size in 1..1_048_576)
        val anchor = locallyProvisionedAnchor.copyOf()
        unlock()
        val record = list().single { it.id == id }
        require(record.address == independentlyKnownWallet)
        val manifest = record.publicManifest()
        val initial = TosV5R2InitialRecovery.parseAndReconstruct(manifest, codes, trusted, independentlyKnownWallet)
        requireChain(manifest)
        return runInterruptible(Dispatchers.IO) {
            val session = V5R2ProofSession(context, UUID.fromString(record.id), anchor)
            action(V5R2ProofCoordinator(session, initial.genesis, transport,
                { System.currentTimeMillis() / 1000 }, maximumAge, successor))
        }
    }
    companion object { private val lock = Any() }
}
