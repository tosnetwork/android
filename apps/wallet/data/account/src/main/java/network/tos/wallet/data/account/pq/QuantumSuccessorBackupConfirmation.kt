package network.tos.wallet.data.account.pq

import android.content.SharedPreferences
import android.util.Base64
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Saved pending-successor backup only. Never current authority, signing or unlock evidence.
 * The account service supplies an authenticated binding and the actual exported ciphertext hash. */
internal class QuantumSuccessorBackupConfirmation(private val prefs: SharedPreferences) {
    class Binding(wallet: UUID, operation: UUID, birth: ByteArray, root: ByteArray, file: ByteArray,
                  material: ByteArray, authority: ByteArray) {
        val walletId = wallet
        private val bytes: ByteArray
        init {
            val hashes = listOf(birth, root, file, material, authority)
            require(hashes.all { it.size == 32 })
            bytes = ByteBuffer.allocate(193).put(1).putLong(wallet.mostSignificantBits).putLong(wallet.leastSignificantBits)
                .putLong(operation.mostSignificantBits).putLong(operation.leastSignificantBits)
                .also { buffer -> hashes.forEach { buffer.put(it) } }.array()
        }
        fun encode() = bytes.copyOf()
    }
    private fun name(id: UUID) = "quantum.successor.backup.v1.$id"

    fun confirm(binding: Binding, ciphertext: ByteArray, saved: QuantumBackupExportFile.Saved) = synchronized(lock) {
        require(ciphertext.size == 32 && saved.size in 58..262201 && MessageDigest.isEqual(ciphertext, saved.digest)) {
            "Saved ciphertext does not match successor export"
        }
        val encoded = Base64.encodeToString(binding.encode() + ciphertext, Base64.NO_WRAP)
        val existing = prefs.getString(name(binding.walletId), null)
        if (existing != null) {
            require(existing.length <= 300)
            val retained = Base64.decode(existing, Base64.NO_WRAP)
            // An explicitly saved replacement may use a new salt/password, but must contain
            // the same roots for the same recovery operation. Never replace another binding.
            check(retained.size == 225 && MessageDigest.isEqual(retained.copyOfRange(0, 193),
                binding.encode())) { "Another successor backup is already confirmed" }
        } else check(prefs.all.keys.count { it.startsWith("quantum.successor.backup.v1.") } < 64)
        check(prefs.edit().putString(name(binding.walletId), encoded).commit()) { "Backup confirmation persistence uncertain" }
    }

    /** Retry sync even for a matching in-memory value left behind by a failed commit. */
    fun requireConfirmed(binding: Binding): ByteArray = synchronized(lock) {
        val encoded = checkNotNull(prefs.getString(name(binding.walletId), null)) { "Successor backup is not confirmed" }
        require(encoded.length <= 300)
        val retained = Base64.decode(encoded, Base64.NO_WRAP)
        check(retained.size == 225 && MessageDigest.isEqual(retained.copyOfRange(0, 193), binding.encode())) {
            "Successor backup binding changed"
        }
        check(prefs.edit().putString(name(binding.walletId), encoded).commit()) { "Backup confirmation persistence uncertain" }
        retained.copyOfRange(193, 225)
    }

    companion object { private val lock = Any() }
}
