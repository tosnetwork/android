package network.tos.wallet.data.account.pq

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import network.tos.wallet.data.account.entities.WalletIdentity
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Public birth identity and derivation coordinates, retained before any secret writes.
 * Failed attempts remain recoverable; this is not a backup of the mnemonic or current authority. */
class QuantumCreationRecord(val id: UUID, val profile: QuantumNetworkProfile, val identity: TosQuantumSelfPayIdentity) {
    init {
        require(identity.wallet.initialAuthority.globalId == profile.globalId && identity.wallet.walletId == 0L)
    }
    fun encode(): ByteArray = JSONObject().put("version", 1).put("id", id.toString())
        .put("profile", profile.json()).put("account", 0).put("generation", 0)
        .put("identity", WalletIdentity.Quantum(identity).toBase64()).toString().toByteArray(Charsets.UTF_8)

    fun save(context: Context) {
        val directory = directory(context)
        val path = File(directory, "$id.json")
        val fd = Os.open(path.absolutePath, OsConstants.O_WRONLY or OsConstants.O_CREAT or
            OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180) // 0600; never overwrite enrollment
        FileOutputStream(fd).use { output -> output.write(encode()); output.fd.sync() }
        val dir = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            check((Os.fstat(dir).st_mode and OsConstants.S_IFMT) == OsConstants.S_IFDIR)
            Os.fsync(dir)
        } finally { Os.close(dir) }
    }

    companion object {
        private fun directory(context: Context): File {
            val directory = File(context.noBackupFilesDir, "quantum-creation")
            try { Os.mkdir(directory.absolutePath, 0x1c0) }
            catch (error: ErrnoException) { if (error.errno != OsConstants.EEXIST) throw error }
            val stat = Os.lstat(directory.absolutePath)
            check((stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFDIR &&
                stat.st_uid == Os.getuid() && (stat.st_mode and 0x3f) == 0) { "Creation directory unavailable" }
            return directory
        }
        fun decode(bytes: ByteArray): QuantumCreationRecord {
            require(bytes.size in 1..32768)
            val value = JSONObject(bytes.toString(Charsets.UTF_8))
            require(value.keys().asSequence().toSet() == setOf("version", "id", "profile", "account", "generation", "identity"))
            require(value.get("version") == 1 && value.get("account") == 0 && value.get("generation") == 0)
            val id = UUID.fromString(value.getString("id"))
            require(id.toString() == value.getString("id"))
            return QuantumCreationRecord(id, QuantumNetworkProfile.parse(value.getJSONObject("profile")),
                WalletIdentity.quantumFromBase64(value.getString("identity")))
        }
    }
}
