package network.tos.security.pq

import android.content.SharedPreferences
import android.util.Base64
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Complete recovery material at rest. App authentication and durable isolation belong to the service.
 * No public plaintext getter. The caller supplies the existing device wrapping key. */
class QuantumRecoveryMaterialVault(private val prefs: SharedPreferences) {
    private fun name(id: UUID) = "quantum.recovery.material.v1.$id"
    private fun aad(id: UUID, inspection: QuantumBackupInspection) =
        "TOSQAM01".toByteArray(Charsets.US_ASCII) + ByteBuffer.allocate(16)
            .putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array() +
            inspection.birth.cell.hash().toByteArray() + inspection.encryptedDigest

    fun storeValidated(encrypted: ByteArray, password: CharArray, inspection: QuantumBackupInspection,
                       id: UUID, wrappingKey: SecretKey) {
        var plaintext = ByteArray(0)
        try {
            require(encrypted.size in 58..262201)
            val input = encrypted.copyOf()
            require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(input), inspection.encryptedDigest))
            plaintext = QuantumBackupCipher.decrypt(input, password)
            inspection.validatePayload(plaintext)
            synchronized(lock) {
                val key = name(id)
                val existing = prefs.getString(key, null)
                if (existing != null) {
                    withStoredPayload(id, inspection, wrappingKey) { stored ->
                        require(MessageDigest.isEqual(stored, plaintext)) { "Recovery material differs from enrollment" }
                    }
                    // A failed commit may have updated only in-memory preferences. Require durable retry.
                    check(prefs.edit().putString(key, existing).commit()) { "Recovery material persistence uncertain" }
                } else {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
                    cipher.updateAAD(aad(id, inspection))
                    val record = byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
                    try {
                        check(prefs.edit().putString(key, Base64.encodeToString(record, Base64.NO_WRAP)).commit()) {
                            "Recovery material persistence uncertain"
                        }
                    } finally { record.fill(0) }
                }
            }
        } finally { plaintext.fill(0); password.fill('\u0000') }
    }

    /** Internal custody scope only; never exposed to wallet UI or as a general seed getter. */
    internal fun <T> withStoredPayload(id: UUID, inspection: QuantumBackupInspection, wrappingKey: SecretKey,
                                       body: (ByteArray) -> T): T = synchronized(lock) {
        val text = checkNotNull(prefs.getString(name(id), null))
        require(text.length <= 4 * ((262173 + 2) / 3))
        val record = Base64.decode(text, Base64.NO_WRAP)
        try {
            require(record.size in 30..262173 && record[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, record.copyOfRange(1, 13)))
            cipher.updateAAD(aad(id, inspection))
            val plaintext = cipher.doFinal(record, 13, record.size - 13)
            try { body(plaintext) } finally { plaintext.fill(0) }
        } finally { record.fill(0) }
    }
    companion object { private val lock = Any() }
}
