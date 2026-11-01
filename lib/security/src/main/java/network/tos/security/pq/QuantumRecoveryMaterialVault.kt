package network.tos.security.pq

import android.content.SharedPreferences
import android.util.Base64
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import network.tos.blockchain.ton.contract.QuantumSelfPayAuthority
import network.tos.blockchain.ton.contract.QuantumSelfPayState
import network.tos.blockchain.ton.contract.QuantumStoredSelfPayRequest
import network.tos.blockchain.ton.contract.QuantumAuthRole
import org.ton.boc.BagOfCells

/** Complete recovery material at rest. App authentication and durable isolation belong to the service.
 * No public plaintext getter. The caller supplies the existing device wrapping key. */
class QuantumRecoveryMaterialVault(private val prefs: SharedPreferences) {
    private fun name(id: UUID) = "quantum.recovery.material.v1.$id"
    private fun aad(id: UUID, birthHash: ByteArray, digest: ByteArray): ByteArray {
        require(birthHash.size == 32 && digest.size == 32)
        return "TOSQAM01".toByteArray(Charsets.US_ASCII) + ByteBuffer.allocate(16)
            .putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array() +
            birthHash + digest
    }
    private fun aad(id: UUID, inspection: QuantumBackupInspection) =
        aad(id, inspection.birth.cell.hash().toByteArray(), inspection.encryptedDigest)

    /** Returns only verified public metadata. Does not need the original file password. */
    fun inspectStored(id: UUID, birth: TosQuantumSelfPayIdentity, originalDigest: ByteArray, wrappingKey: SecretKey,
                      globalId: Int, root: ByteArray, file: ByteArray, namespace: ByteArray): QuantumBackupInspection {
        require(originalDigest.size == 32)
        val digest = originalDigest.copyOf()
        return withStoredRecord(id, birth.cell.hash().toByteArray(), digest, wrappingKey) { plaintext ->
            QuantumBackupInspection.inspectPlaintext(plaintext, digest, globalId, root, file, namespace).also {
                require(it.birth == birth)
            }
        }
    }

    /** Service supplies fresh verified authority and explicitly selects matching declared roles.
     * Birth bytes and original provenance are retained. Only ciphertext crosses the boundary. */
    fun exportEncrypted(id: UUID, inspection: QuantumBackupInspection, wrappingKey: SecretKey,
                        authority: QuantumSelfPayAuthority, selected: List<QuantumRole>, password: CharArray): ByteArray {
        try {
            require(selected.size in 1..2)
            val selection = selected.toList()
            require(selection.size in 1..2 && selection.toSet().size == selection.size)
            return withStoredPayload(id, inspection, wrappingKey) { plaintext ->
                inspection.validatePayload(plaintext)
                QuantumBackupPayload.parseUnverified(plaintext).use { original ->
                    require(authority.globalId == original.globalId && authority.namespace.contentEquals(original.namespace))
                    val roles = original.roles.filter { role -> selection.any { it.id == role.role } }
                    require(roles.size == selection.size) { "Selected role is not stored" }
                    val output = QuantumBackupPayload.encodeUnverified(original.globalId, original.rootHash, original.fileHash,
                        original.namespace, original.birthBoc, BagOfCells(authority.cell).toByteArray(), roles)
                    try {
                        QuantumBackupPayload.parseUnverified(output).use { bound ->
                            bound.validateSnapshotKeys(authority.primaryKey, authority.rescueKey)
                            bound.validateProvenance()
                        }
                        QuantumBackupCipher.encrypt(output, password)
                    } finally { output.fill(0) }
                }
            }
        } finally { password.fill('\u0000') }
    }

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

    /** Restricted primitive only: service must authenticate, obtain fresh state and durably
     * enter SIGNING before calling. Historical file metadata never authorizes this operation. */
    fun signRecoveryControl(id: UUID, inspection: QuantumBackupInspection, wrappingKey: SecretKey,
                            current: QuantumSelfPayState, requestBOC: ByteArray, now: Long): ByteArray {
        val request = QuantumStoredSelfPayRequest.parse(requestBOC)
        request.requireIdentity(inspection.birth)
        require(request.role == QuantumAuthRole.RESCUE && request.kind in setOf(1, 3)) { "Only RESCUE lock/configure allowed" }
        require(now in 1..0xffffffffL && request.deadline - now in 1..3600) { "Recovery control expired" }
        require(current.walletId == inspection.birth.wallet.walletId &&
            current.authority.globalId == request.globalId && current.authority.namespace.contentEquals(request.namespace))
        require(request.authorityHash.contentEquals(current.authority.hash) && request.epoch == current.epoch &&
            request.nonce == current.rescueNonce) { "Recovery control state mismatch" }
        // Reconstructing also enforces control counter saturation rules.
        val expected = network.tos.blockchain.ton.contract.TosQuantumSelfPayRequest(inspection.birth.address, current,
            QuantumAuthRole.RESCUE, request.deadline, if (request.kind == 1)
                network.tos.blockchain.ton.contract.QuantumSelfPayAction.ConfigureRequired else
                network.tos.blockchain.ton.contract.QuantumSelfPayAction.LockPrimary, now)
        require(expected.request.hash() == request.cell.hash())
        return withStoredPayload(id, inspection, wrappingKey) { plaintext ->
            inspection.validatePayload(plaintext)
            QuantumBackupPayload.parseUnverified(plaintext).use { payload ->
                val rescue = requireNotNull(payload.roles.singleOrNull { it.role == QuantumRole.RESCUE.id }) { "RESCUE material unavailable" }
                // Native signing independently derives and checks the actual current public key.
                QuantumCrypto.sign(QuantumRole.RESCUE, QuantumPurpose.SELF_PAY, rescue.seed,
                    current.authority.rescueKey, request.cell.hash().toByteArray())
            }
        }
    }

    /** Internal custody scope only; never exposed to wallet UI or as a general seed getter. */
    internal fun <T> withStoredPayload(id: UUID, inspection: QuantumBackupInspection, wrappingKey: SecretKey,
                                       body: (ByteArray) -> T): T =
        withStoredRecord(id, inspection.birth.cell.hash().toByteArray(), inspection.encryptedDigest, wrappingKey, body)

    private fun <T> withStoredRecord(id: UUID, birthHash: ByteArray, digest: ByteArray, wrappingKey: SecretKey,
                                     body: (ByteArray) -> T): T = synchronized(lock) {
        val text = checkNotNull(prefs.getString(name(id), null))
        require(text.length <= 4 * ((262173 + 2) / 3))
        val record = Base64.decode(text, Base64.NO_WRAP)
        try {
            require(record.size in 30..262173 && record[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, record.copyOfRange(1, 13)))
            cipher.updateAAD(aad(id, birthHash, digest))
            val plaintext = cipher.doFinal(record, 13, record.size - 13)
            try { body(plaintext) } finally { plaintext.fill(0) }
        } finally { record.fill(0) }
    }
    companion object { private val lock = Any() }
}
