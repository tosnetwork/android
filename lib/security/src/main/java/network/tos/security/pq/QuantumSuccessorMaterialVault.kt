package network.tos.security.pq

import android.content.SharedPreferences
import android.util.Base64
import network.tos.blockchain.ton.contract.*
import org.ton.boc.BagOfCells
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Staged independent roots only. No plaintext getter, signer, install or payment authority. */
class QuantumSuccessorMaterialVault(private val prefs: SharedPreferences) {
    class Stage internal constructor(val walletId: UUID, val inspection: QuantumBackupInspection)
    private fun name(id: UUID) = "quantum.successor.material.v1.$id"
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun header(current: QuantumSelfPayState, root: ByteArray, file: ByteArray): ByteArray {
        require(root.size == 32 && file.size == 32)
        return byteArrayOf(1) + current.cell.hash().toByteArray() + root + file
    }
    private fun aad(id: UUID, birth: TosQuantumSelfPayIdentity, header: ByteArray) =
        "TOS-QSP-SUCCESSOR-v1".toByteArray(Charsets.US_ASCII) + ByteBuffer.allocate(16)
            .putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array() + birth.cell.hash().toByteArray() + header

    /** Caller authenticates and supplies a fresh, independently verified current state. */
    fun generate(id: UUID, birth: TosQuantumSelfPayIdentity, current: QuantumSelfPayState,
                 root: ByteArray, file: ByteArray, key: SecretKey): Stage = synchronized(lock) {
        check(prefs.getString(name(id), null) == null) { "Successor material already staged or persistence uncertain" }
        check(prefs.all.keys.count { it.startsWith("quantum.successor.material.v1.") } < 64)
        require(current.walletId == birth.wallet.walletId)
        birth.requireNetwork(current.authority.globalId, current.authority.namespace)
        val rootHash = root.copyOf(); val fileHash = file.copyOf()
        val roles = mutableListOf<QuantumBackupPayload.Role>()
        var plaintext = ByteArray(0)
        var record = ByteArray(0)
        try {
            val random = SecureRandom()
            val public = mutableMapOf<QuantumRole, ByteArray>()
            for (role in QuantumRole.entries) {
                val master = ByteArray(32).also(random::nextBytes)
                var seed = ByteArray(0)
                try {
                    seed = QuantumKdf.deriveAndWipe(if (role == QuantumRole.PRIMARY) QuantumKdf.Material.PRIMARY else QuantumKdf.Material.RESCUE,
                        master.copyOf(), current.authority.namespace, current.authority.globalId, 0, 0)
                    val roleKey = QuantumCrypto.publicKey(role, seed)
                    val previous = if (role == QuantumRole.PRIMARY) current.authority.primaryKey else current.authority.rescueKey
                    check(!roleKey.contentEquals(previous))
                    public[role] = roleKey
                    roles += QuantumBackupPayload.Role(role.id, 0, 0, seed.copyOf(), digest(roleKey), 3,
                        intArrayOf(), byteArrayOf(), master.copyOf())
                } finally { seed.fill(0); master.fill(0) }
            }
            val authority = QuantumSelfPayAuthority(current.authority.globalId, current.authority.namespace,
                public.getValue(QuantumRole.PRIMARY), public.getValue(QuantumRole.RESCUE), QuantumPolicy.REQUIRED)
            plaintext = QuantumBackupPayload.encodeUnverified(authority.globalId, rootHash, fileHash, authority.namespace,
                birth.toBoc(), BagOfCells(authority.cell).toByteArray(), roles)
            QuantumBackupPayload.parseUnverified(plaintext).use {
                it.validateIdentity(authority.globalId, rootHash, fileHash, authority.namespace)
                it.validateSnapshotKeys(authority.primaryKey, authority.rescueKey); it.validateProvenance()
            }
            val header = header(current, rootHash, fileHash)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(aad(id, birth, header))
            check(cipher.iv.size == 12)
            record = header + cipher.iv + cipher.doFinal(plaintext)
            check(prefs.edit().putString(name(id), Base64.encodeToString(record, Base64.NO_WRAP)).commit()) {
                "Successor material persistence uncertain"
            }
            Stage(id, QuantumBackupInspection.inspectPlaintext(plaintext, digest(record), authority.globalId, rootHash, fileHash, authority.namespace))
        } finally { plaintext.fill(0); record.fill(0); roles.forEach { it.wipe() } }
    }

    /** Revalidates retained derivation and retries durability after an uncertain preferences commit. */
    fun inspect(id: UUID, birth: TosQuantumSelfPayIdentity, current: QuantumSelfPayState,
                root: ByteArray, file: ByteArray, key: SecretKey): Stage = synchronized(lock) {
        val encoded = checkNotNull(prefs.getString(name(id), null))
        require(encoded.length <= 350000)
        val record = Base64.decode(encoded, Base64.NO_WRAP)
        val rootHash = root.copyOf(); val fileHash = file.copyOf()
        var plaintext = ByteArray(0)
        try {
            require(record.size in 126..262500)
            val header = header(current, rootHash, fileHash)
            require(record.copyOfRange(0, 97).contentEquals(header)) { "Successor state or profile mismatch" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, record.copyOfRange(97, 109)))
            cipher.updateAAD(aad(id, birth, header)); plaintext = cipher.doFinal(record.copyOfRange(109, record.size))
            val inspection = QuantumBackupInspection.inspectPlaintext(plaintext, digest(record), current.authority.globalId,
                rootHash, fileHash, current.authority.namespace)
            require(inspection.birth == birth && inspection.snapshot.policy == QuantumPolicy.REQUIRED &&
                inspection.roles.size == 2 && inspection.roles.all { it.sourceProfile == 3 })
            check(prefs.edit().putString(name(id), encoded).commit()) { "Successor material persistence uncertain" }
            Stage(id, inspection)
        } finally { record.fill(0); plaintext.fill(0) }
    }
    companion object { private val lock = Any() }
}
