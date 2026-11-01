package network.tos.wallet.data.account.pq

import network.tos.security.pq.QuantumBackupInspection
import network.tos.security.pq.QuantumRole
import network.tos.blockchain.ton.contract.QuantumSelfPayAuthority

/** Role matching does not approve retirement policy, signing, or clearing recovery isolation. */
internal object QuantumBackupRoleObservation {
    enum class Match { MATCHES_CURRENT, STALE, UNAVAILABLE }
    data class Role(val role: QuantumRole, val match: Match)

    fun classify(backup: QuantumBackupInspection, observation: QuantumAccountReader.Observation, now: Long): List<Role> {
        observation.requireFresh(now)
        require(backup.birth == observation.identity) { "Backup observation birth mismatch" }
        fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        backup.requireProfile(observation.profile.globalId, hex(observation.profile.rootHash),
            hex(observation.profile.fileHash), observation.identity.wallet.initialAuthority.namespace)
        val current = (observation.snapshot.status as? QuantumSelfPayAccount.Status.Active)?.state?.authority
        if (current != null) require(current.globalId == backup.snapshot.globalId &&
            current.namespace.contentEquals(backup.snapshot.namespace)) { "Backup authority network mismatch" }
        val result = backup.roles.map { Role(it.role, compare(it.role, backup.snapshot, current)) }
        observation.requireFresh(now)
        return result
    }

    /** Pure byte comparison only; cannot mint an authenticated observation. */
    fun compare(role: QuantumRole, snapshot: QuantumSelfPayAuthority, current: QuantumSelfPayAuthority?): Match {
        if (current == null) return Match.UNAVAILABLE
        val stored = if (role == QuantumRole.PRIMARY) snapshot.primaryKey else snapshot.rescueKey
        val installed = if (role == QuantumRole.PRIMARY) current.primaryKey else current.rescueKey
        return if (stored.contentEquals(installed)) Match.MATCHES_CURRENT else Match.STALE
    }
}
