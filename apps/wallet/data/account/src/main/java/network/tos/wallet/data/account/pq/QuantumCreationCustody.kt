package network.tos.wallet.data.account.pq

import android.content.Context
import android.content.SharedPreferences
import network.tos.security.Security
import network.tos.security.pq.QuantumDeviceKey
import network.tos.security.pq.QuantumRole
import network.tos.security.pq.QuantumSeedVault
import java.util.UUID
import javax.crypto.SecretKey

/** Dedicated encrypted storage keyed by wallet UUID, never by an Ed25519 key.
 * Callers authenticate creation, backup and deletion. All writes report persistence failure. */
internal class QuantumCreationCustody internal constructor(
    private val preferences: SharedPreferences,
    private val wrappingKey: () -> SecretKey,
) {
    constructor(context: Context) : this(Security.pref(context,
        "_network_tos_quantum_creation_key_", "quantum_creation_custody", requireUnlockedDevice = true),
        { QuantumDeviceKey.get(true) })

    private val roles = QuantumSeedVault(preferences)
    private fun mnemonicKey(id: UUID) = "quantum.mnemonic.v1.$id"

    fun saveMnemonic(id: UUID, words: List<String>) = synchronized(lock) {
        require(words.size == 24 && words.all { it.matches(Regex("[a-z]+")) })
        val key = mnemonicKey(id)
        check(!preferences.contains(key)) { "Mnemonic is already enrolled" }
        check(preferences.edit().putString(key, words.joinToString(" ")).commit()) { "Mnemonic persistence uncertain" }
    }

    fun mnemonic(id: UUID): Array<String>? = synchronized(lock) {
        val text = preferences.getString(mnemonicKey(id), null) ?: return@synchronized null
        require(text.length <= 1024)
        val words = text.split(" ")
        require(words.size == 24 && words.all { it.matches(Regex("[a-z]+")) }) { "Invalid stored mnemonic" }
        words.toTypedArray()
    }

    fun store(id: UUID, role: QuantumRole, context: QuantumSeedVault.Context, seed: ByteArray): ByteArray = synchronized(lock) {
        // Key lookup may fail before the vault takes ownership of the buffer.
        try { roles.importAndWipe(id.toString(), role, context, wrappingKey(), seed) }
        finally { seed.fill(0) }
    }

    fun delete(id: UUID) = synchronized(lock) {
        val editor = preferences.edit().remove(mnemonicKey(id))
        for (role in QuantumRole.entries) editor.remove("quantum.seed.v1.${role.id}.$id")
        check(editor.commit()) { "Quantum key deletion uncertain" }
    }

    fun deleteAll() = synchronized(lock) {
        // Clear failed enrollment attempts too, without touching other wallet services.
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith("quantum.mnemonic.v1.") || it.startsWith("quantum.seed.v1.") }
            .forEach(editor::remove)
        check(editor.commit()) { "Quantum key deletion uncertain" }
    }

    companion object { private val lock = Any() }
}
