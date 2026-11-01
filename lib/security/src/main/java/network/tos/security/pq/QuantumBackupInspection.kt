package network.tos.security.pq

import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import network.tos.blockchain.ton.contract.QuantumSelfPayAuthority

/** Historical encrypted-file inspection only. No result authorizes signing or clears recovery isolation. */
class QuantumBackupInspection private constructor(
    val birth: TosQuantumSelfPayIdentity,
    val snapshot: QuantumSelfPayAuthority,
    val roles: List<Role>,
    private val root: ByteArray,
    private val file: ByteArray,
    private val digest: ByteArray,
) {
    val encryptedDigest: ByteArray get() = digest.copyOf()
    class Role internal constructor(val role: QuantumRole, val account: Long,
                                    val generation: Long, val sourceProfile: Int)

    fun requireProfile(globalId: Int, rootHash: ByteArray, fileHash: ByteArray, namespace: ByteArray) {
        require(globalId == snapshot.globalId && java.security.MessageDigest.isEqual(root, rootHash) &&
            java.security.MessageDigest.isEqual(file, fileHash) &&
            java.security.MessageDigest.isEqual(snapshot.namespace, namespace)) { "Backup observation profile mismatch" }
    }

    companion object {
        /** Trust fields must come from an independently approved profile. Password is consumed. */
        fun inspect(encrypted: ByteArray, password: CharArray, expectedGlobalId: Int,
                    expectedRoot: ByteArray, expectedFile: ByteArray,
                    expectedNamespace: ByteArray): QuantumBackupInspection {
            var plaintext = ByteArray(0)
            try {
                require(encrypted.size in 58..(QuantumBackupEnvelope.HEADER_SIZE + QuantumBackupEnvelope.MAX_CIPHERTEXT_SIZE + 16))
                // Bind the journal digest to exactly the owned ciphertext bytes that were authenticated.
                val record = encrypted.copyOf()
                plaintext = QuantumBackupCipher.decrypt(record, password)
                return QuantumBackupPayload.parseUnverified(plaintext).use { payload ->
                    val identity = payload.validateIdentity(expectedGlobalId, expectedRoot, expectedFile, expectedNamespace)
                    payload.validateSnapshotKeys(identity.snapshot.primaryKey, identity.snapshot.rescueKey)
                    payload.validateProvenance()
                    QuantumBackupInspection(identity.birth, identity.snapshot, payload.roles.map {
                        Role(if (it.role == 1) QuantumRole.PRIMARY else QuantumRole.RESCUE,
                            it.account, it.generation, it.sourceProfile)
                    }, payload.rootHash.copyOf(), payload.fileHash.copyOf(),
                        java.security.MessageDigest.getInstance("SHA-256").digest(record))
                }
            } finally { plaintext.fill(0); password.fill('\u0000') }
        }
    }
}
