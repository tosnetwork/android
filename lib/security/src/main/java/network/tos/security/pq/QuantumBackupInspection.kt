package network.tos.security.pq

import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import network.tos.blockchain.ton.contract.QuantumSelfPayAuthority

/** Historical encrypted-file inspection only. No result authorizes signing or clears recovery isolation. */
class QuantumBackupInspection private constructor(
    val birth: TosQuantumSelfPayIdentity,
    val snapshot: QuantumSelfPayAuthority,
    val roles: List<Role>,
) {
    class Role internal constructor(val role: QuantumRole, val account: Long,
                                    val generation: Long, val sourceProfile: Int)

    companion object {
        /** Trust fields must come from an independently approved profile. Password is consumed. */
        fun inspect(encrypted: ByteArray, password: CharArray, expectedGlobalId: Int,
                    expectedRoot: ByteArray, expectedFile: ByteArray,
                    expectedNamespace: ByteArray): QuantumBackupInspection {
            var plaintext = ByteArray(0)
            try {
                plaintext = QuantumBackupCipher.decrypt(encrypted, password)
                return QuantumBackupPayload.parseUnverified(plaintext).use { payload ->
                    val identity = payload.validateIdentity(expectedGlobalId, expectedRoot, expectedFile, expectedNamespace)
                    payload.validateSnapshotKeys(identity.snapshot.primaryKey, identity.snapshot.rescueKey)
                    payload.validateProvenance()
                    QuantumBackupInspection(identity.birth, identity.snapshot, payload.roles.map {
                        Role(if (it.role == 1) QuantumRole.PRIMARY else QuantumRole.RESCUE,
                            it.account, it.generation, it.sourceProfile)
                    })
                }
            } finally { plaintext.fill(0); password.fill('\u0000') }
        }
    }
}
