package network.tos.wallet.data.account.pq

import android.content.Context
import android.content.SharedPreferences
import network.tos.security.Security
import network.tos.security.pq.QuantumDeviceKey
import network.tos.security.pq.QuantumCrypto
import network.tos.security.pq.QuantumRole
import network.tos.security.pq.QuantumSeedVault
import network.tos.security.pq.QuantumPurpose
import network.tos.security.pq.QuantumRecoveryMaterialVault
import network.tos.security.pq.QuantumBackupInspection
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import network.tos.blockchain.ton.contract.QuantumSelfPayAuthority
import java.util.UUID
import javax.crypto.SecretKey

/** Dedicated encrypted storage keyed by wallet UUID, never by an Ed25519 key.
 * Callers authenticate creation, backup and deletion. All writes report persistence failure. */
internal class QuantumCreationCustody internal constructor(
    private val preferences: SharedPreferences,
    private val wrappingKey: (Boolean) -> SecretKey,
) {
    constructor(context: Context) : this(Security.pref(context,
        "_network_tos_quantum_creation_key_", "quantum_creation_custody", requireUnlockedDevice = true),
        { create -> QuantumDeviceKey.get(create) })

    private val roles = QuantumSeedVault(preferences)
    private val recovery = QuantumRecoveryMaterialVault(preferences)
    private val successors = network.tos.security.pq.QuantumSuccessorMaterialVault(preferences)
    private val successorBackups = QuantumSuccessorBackupConfirmation(preferences)
    fun exportSuccessorMaterial(record: QuantumRecoveryRecord,
        stagedState: network.tos.blockchain.ton.contract.QuantumSelfPayState,
        materialDigest: ByteArray, successor: QuantumSelfPayAuthority, password: CharArray,
    ): ByteArray = synchronized(lock) {
        try {
            fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            successors.exportEncrypted(record.id, record.identity, stagedState,
                hex(record.profile.rootHash), hex(record.profile.fileHash), wrappingKey(false), materialDigest, successor, password)
        } finally { password.fill('\u0000') }
    }
    fun confirmSuccessorBackup(binding: QuantumSuccessorBackupConfirmation.Binding, digest: ByteArray,
        saved: QuantumBackupExportFile.Saved) = synchronized(lock) { successorBackups.confirm(binding, digest, saved) }
    fun requireSuccessorBackup(binding: QuantumSuccessorBackupConfirmation.Binding): ByteArray =
        synchronized(lock) { successorBackups.requireConfirmed(binding) }
    fun successorMaterial(id: UUID, birth: TosQuantumSelfPayIdentity,
        current: network.tos.blockchain.ton.contract.QuantumSelfPayState, profile: QuantumNetworkProfile,
        resume: Boolean,
    ): network.tos.security.pq.QuantumSuccessorMaterialVault.Stage = synchronized(lock) {
        fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (resume) successors.inspect(id, birth, current, hex(profile.rootHash), hex(profile.fileHash), wrappingKey(false))
        else successors.generate(id, birth, current, hex(profile.rootHash), hex(profile.fileHash), wrappingKey(false))
    }
    fun exportCreationMaterial(record: QuantumCreationRecord, authority: QuantumSelfPayAuthority,
                               password: CharArray): ByteArray = synchronized(lock) {
        try {
            val words = checkNotNull(mnemonic(record.id)) { "Original recovery phrase unavailable" }.toList()
            fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            roles.encryptedCreationBackup(record.id.toString(), wrappingKey(false), record.identity, authority,
                hex(record.profile.rootHash), hex(record.profile.fileHash), words, password)
        } finally { password.fill('\u0000') }
    }
    fun inspectFundedSuccessor(id: UUID, birth: TosQuantumSelfPayIdentity,
        stagedState: network.tos.blockchain.ton.contract.QuantumSelfPayState,
        fundedState: network.tos.blockchain.ton.contract.QuantumSelfPayState, profile: QuantumNetworkProfile,
        materialDigest: ByteArray, successor: QuantumSelfPayAuthority,
    ): network.tos.security.pq.QuantumSuccessorMaterialVault.Stage = synchronized(lock) {
        fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        successors.inspectAfterFunding(id, birth, stagedState, fundedState, hex(profile.rootHash), hex(profile.fileHash),
            wrappingKey(false), materialDigest, successor)
    }
    fun signSuccessorProbe(id: UUID, birth: TosQuantumSelfPayIdentity,
        current: network.tos.blockchain.ton.contract.QuantumSelfPayState, profile: QuantumNetworkProfile,
        materialDigest: ByteArray, successor: QuantumSelfPayAuthority, challenge: ByteArray, deadline: Long, now: Long,
    ): Pair<ByteArray, ByteArray> = synchronized(lock) {
        fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        successors.signProbe(id, birth, current, hex(profile.rootHash), hex(profile.fileHash), wrappingKey(false),
            materialDigest, successor, challenge, deadline, now)
    }
    fun storeRecoveryMaterial(id: UUID, encrypted: ByteArray, password: CharArray, inspection: QuantumBackupInspection) = synchronized(lock) {
        try { recovery.storeValidated(encrypted, password, inspection, id, wrappingKey(true)) }
        finally { password.fill('\u0000') }
    }
    fun inspectRecoveryMaterial(id: UUID, birth: TosQuantumSelfPayIdentity, digest: ByteArray,
                                profile: QuantumNetworkProfile): QuantumBackupInspection = synchronized(lock) {
        fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        recovery.inspectStored(id, birth, digest, wrappingKey(false), profile.globalId,
            hex(profile.rootHash), hex(profile.fileHash), birth.wallet.initialAuthority.namespace)
    }
    fun exportRecoveryMaterial(id: UUID, inspection: QuantumBackupInspection, authority: QuantumSelfPayAuthority,
                               selected: List<QuantumRole>, password: CharArray): ByteArray = synchronized(lock) {
        try { recovery.exportEncrypted(id, inspection, wrappingKey(false), authority, selected, password) }
        finally { password.fill('\u0000') }
    }
    private fun mnemonicKey(id: UUID) = "quantum.mnemonic.v1.$id"
    fun signRecoveryControl(id: UUID, inspection: QuantumBackupInspection,
        state: network.tos.blockchain.ton.contract.QuantumSelfPayState, request: ByteArray, now: Long): ByteArray = synchronized(lock) {
        recovery.signRecoveryControl(id, inspection, wrappingKey(false), state, request, now)
    }
    fun signSuccessorMigration(id: UUID, inspection: QuantumBackupInspection,
        state: network.tos.blockchain.ton.contract.QuantumSelfPayState, successor: QuantumSelfPayAuthority,
        request: ByteArray, now: Long): ByteArray = synchronized(lock) {
        recovery.signSuccessorMigration(id, inspection, wrappingKey(false), state, successor, request, now)
    }
    fun signProbeFunding(id: UUID, inspection: QuantumBackupInspection,
        state: network.tos.blockchain.ton.contract.QuantumSelfPayState, successor: QuantumSelfPayAuthority,
        challenge: ByteArray, probeDeadline: Long, primary: ByteArray, rescue: ByteArray,
        amount: java.math.BigInteger, request: ByteArray, now: Long): ByteArray = synchronized(lock) {
        recovery.signProbeFunding(id, inspection, wrappingKey(false), state, successor, challenge, probeDeadline,
            primary, rescue, amount, request, now)
    }

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
        try { roles.importAndWipe(id.toString(), role, context, wrappingKey(true), seed) }
        finally { seed.fill(0) }
    }

    fun pendingIds(): List<UUID> = synchronized(lock) {
        preferences.all.keys.filter { it.startsWith("quantum.mnemonic.v1.") }.map { key ->
            val value = key.removePrefix("quantum.mnemonic.v1.")
            UUID.fromString(value).also { require(it.toString() == value) }
        }.filterNot { preferences.getBoolean("quantum.creation.complete.v1.$it", false) }
    }

    fun ensureMnemonic(id: UUID, words: List<String>) = synchronized(lock) {
        val existing = mnemonic(id)
        if (existing == null) saveMnemonic(id, words)
        else {
            require(existing.toList() == words) { "Creation mnemonic differs from enrollment" }
            // A previous failed commit may have changed the in-memory preferences. Require a
            // successful persistence operation before treating an existing value as committed.
            check(preferences.edit().putString(mnemonicKey(id), words.joinToString(" ")).commit()) { "Mnemonic persistence uncertain" }
        }
    }

    fun ensureRole(id: UUID, role: QuantumRole, context: QuantumSeedVault.Context, seed: ByteArray): ByteArray = synchronized(lock) {
        try {
            val name = "quantum.seed.v1.${role.id}.$id"
            val existing = preferences.getString(name, null)
            if (existing == null) store(id, role, context, seed)
            else {
                val expected = QuantumCrypto.publicKey(role, seed)
                val actual = roles.publicKey(id.toString(), role, context, wrappingKey(false))
                require(actual.contentEquals(expected)) { "Creation key differs from enrollment" }
                check(preferences.edit().putString(name, existing).commit()) { "Key persistence uncertain" }
                actual
            }
        } finally { seed.fill(0) }
    }

    fun signPayment(id: UUID, context: QuantumSeedVault.Context, expectedPublicKey: ByteArray, digest: ByteArray): ByteArray =
        roles.sign(id.toString(), QuantumRole.PRIMARY, context, wrappingKey(false), expectedPublicKey, QuantumPurpose.SELF_PAY, digest)

    fun signRegisteredPayment(id: UUID, network: ByteArray, globalId: Int, expectedPublicKey: ByteArray, digest: ByteArray): ByteArray =
        roles.signRegistered(id.toString(), QuantumRole.PRIMARY, network, globalId, wrappingKey(false),
            expectedPublicKey, QuantumPurpose.SELF_PAY, digest)

    fun markComplete(id: UUID) = synchronized(lock) {
        check(preferences.contains(mnemonicKey(id)))
        for (role in QuantumRole.entries) check(preferences.contains("quantum.seed.v1.${role.id}.$id"))
        check(preferences.edit().putBoolean("quantum.creation.complete.v1.$id", true).commit()) { "Creation completion persistence uncertain" }
    }

    fun delete(id: UUID) = synchronized(lock) {
        val editor = preferences.edit().remove(mnemonicKey(id)).remove("quantum.creation.complete.v1.$id")
            .remove("quantum.recovery.material.v1.$id")
            .remove("quantum.successor.material.v1.$id")
            .remove("quantum.successor.backup.v1.$id")
        for (role in QuantumRole.entries) editor.remove("quantum.seed.v1.${role.id}.$id")
        check(editor.commit()) { "Quantum key deletion uncertain" }
    }

    fun deleteAll() = synchronized(lock) {
        // Clear failed enrollment attempts too, without touching other wallet services.
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith("quantum.mnemonic.v1.") || it.startsWith("quantum.seed.v1.") || it.startsWith("quantum.creation.complete.v1.") || it.startsWith("quantum.recovery.material.v1.") || it.startsWith("quantum.successor.material.v1.") || it.startsWith("quantum.successor.backup.v1.") }
            .forEach(editor::remove)
        check(editor.commit()) { "Quantum key deletion uncertain" }
    }

    companion object { private val lock = Any() }
}
