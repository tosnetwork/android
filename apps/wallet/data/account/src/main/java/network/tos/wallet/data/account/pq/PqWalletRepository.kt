package network.tos.wallet.data.account.pq

import android.content.Context
import network.tos.blockchain.ton.contract.TosPqWallet
import network.tos.security.pq.PqAlgorithm
import network.tos.security.pq.PqDeviceKey
import network.tos.security.pq.PqSeedVault
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PqWalletRecord(val id: String, val name: String, val algorithm: PqAlgorithm, val network: Int, publicKey: ByteArray) {
    private val key = publicKey.copyOf()
    val publicKey get() = key.copyOf()
    fun descriptor() = TosPqWallet(algorithm.id, publicKey, network)
}

/** Public metadata registry; dedicated encrypted seed storage, with mandatory app
 * authentication before every create/import/sign/export/delete operation. */
class PqWalletRepository(context: Context, private val authenticate: suspend () -> Boolean) {
    private val prefs = context.getSharedPreferences("tos-pq-wallets-v1", Context.MODE_PRIVATE)
    private val vault = PqSeedVault(prefs)
    companion object { private val lock = Any() }
    private val registry = "registry.v1"
    fun list(): List<PqWalletRecord> = synchronized(lock) {
        val array = JSONArray(prefs.getString(registry, "[]"))
        val records = (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val algorithm = PqAlgorithm.entries.single { it.id == obj.getInt("algorithm") }
            val bytes = obj.getString("public_key").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            PqWalletRecord(obj.getString("id"), obj.getString("name"), algorithm, obj.getInt("network"), bytes).also { it.descriptor() }
        }
        check(records.map { it.id }.toSet().size == records.size) { "Duplicate PQ identity" }
        records
    }
    private fun save(records: List<PqWalletRecord>) {
        val array = JSONArray()
        records.forEach { r -> array.put(JSONObject().put("id", r.id).put("name", r.name).put("algorithm", r.algorithm.id)
            .put("network", r.network).put("public_key", r.publicKey.joinToString("") { "%02x".format(it) })) }
        check(prefs.edit().putString(registry, array.toString()).commit()) { "PQ wallet persistence failed" }
    }
    private suspend fun unlock() { check(authenticate()) { "PQ operation cancelled" } }
    private fun existing(id: String) = list().single { it.id == id }
    suspend fun create(name: String, algorithm: PqAlgorithm, network: Int): PqWalletRecord {
        require(name.isNotBlank() && name.toByteArray().size <= 128)
        unlock()
        synchronized(lock) {
            val records = list(); val id = UUID.randomUUID().toString()
            val key = vault.create(id, algorithm, PqDeviceKey.get(records.isEmpty()))
            val record = PqWalletRecord(id, name, algorithm, network, key)
            try { save(records + record); return record }
            catch (e: Throwable) { vault.delete(id, algorithm); throw e }
        }
    }
    suspend fun sign(id: String, message: ByteArray): ByteArray {
        unlock()
        synchronized(lock) {
            val r = existing(id)
            return vault.sign(id, r.algorithm, PqDeviceKey.get(false), r.publicKey, message)
        }
    }
    suspend fun backup(id: String, password: CharArray): ByteArray {
        unlock()
        synchronized(lock) {
            val r = existing(id)
            return vault.backup(id, r.algorithm, r.network, PqDeviceKey.get(false), password)
        }
    }
    suspend fun restore(name: String, algorithm: PqAlgorithm, network: Int, backup: ByteArray, password: CharArray): PqWalletRecord {
        require(name.isNotBlank() && name.toByteArray().size <= 128)
        unlock()
        synchronized(lock) {
            val records = list(); val id = UUID.randomUUID().toString()
            val key = vault.restoreBackup(id, algorithm, network, PqDeviceKey.get(records.isEmpty()), backup, password)
            if (records.any { it.algorithm == algorithm && it.network == network && it.publicKey.contentEquals(key) }) {
                vault.delete(id, algorithm); error("PQ wallet already exists")
            }
            val record = PqWalletRecord(id, name, algorithm, network, key)
            try { save(records + record); return record }
            catch (e: Throwable) { vault.delete(id, algorithm); throw e }
        }
    }
    suspend fun delete(id: String) {
        unlock()
        synchronized(lock) {
            val record = existing(id);vault.delete(id, record.algorithm)
            val remaining = list().filter { it.id != id };save(remaining)
            if (remaining.isEmpty()) PqDeviceKey.delete()
        }
    }
}
