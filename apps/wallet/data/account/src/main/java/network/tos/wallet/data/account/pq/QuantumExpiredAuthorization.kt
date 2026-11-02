package network.tos.wallet.data.account.pq

import android.content.Context
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import org.json.JSONObject
import java.util.UUID

/** Non-serializable fresh expiry proof, never a receipt, delete, signing or broadcast operation. */
class QuantumExpiredAuthorization private constructor(
    private val binding: QuantumAuthorizationBinding,
    private val observation: QuantumAccountReader.Observation,
) {
    val operationId: UUID = binding.operationId
    val deadline: Long = binding.deadline
    val provenShardTime: Long = observation.proof.provenAccountTime(QuantumAccountReader.rawAddress(binding.identity))
    val hasExactMessage: Boolean = binding.hasExactMessage
    internal val identity: TosQuantumSelfPayIdentity get() = binding.identity
    internal fun provenTarget(entry: QuantumPaymentOutbox.Entry, now: Long): JSONObject {
        requireCurrent(entry, now)
        return JSONObject(observation.proof.requestAtCheckpoint(QuantumAccountReader.rawAddress(identity), maximumAge = 300)
            .toString(Charsets.UTF_8)).getJSONObject("target")
    }
    internal fun requireCurrent(entry: QuantumPaymentOutbox.Entry, now: Long) {
        observation.requireFresh(now)
        binding.requireCurrent(entry)
        check(observation.identity == entry.identity && sameProfile(observation.profile, entry.profile))
        check(provenShardTime > deadline) { "Original authorization is not proven strictly expired" }
    }
    companion object {
        /** Only this original-profile authenticated read path can invoke the private constructor. */
        internal fun acquire(context: Context, accounts: QuantumAccountReader, id: UUID,
                             identity: TosQuantumSelfPayIdentity, now: () -> Long, running: () -> Unit,
                             retirement: Boolean = false): QuantumExpiredAuthorization {
            running()
            val record = QuantumCreationRecord.load(context, id)
            check(record.id == id && record.identity == identity)
            val outbox = QuantumPaymentOutbox(context)
            val entry = if (retirement) outbox.retirementSnapshot(identity) else checkNotNull(outbox.pending(identity))
            check(sameProfile(entry.profile, record.profile))
            val binding = QuantumAuthorizationBinding.capture(entry)
            val observation = accounts.observe(id, identity)
            observation.requireFresh(now()); running()
            check(observation.id == id && observation.identity == identity && sameProfile(observation.profile, entry.profile))
            val capability = QuantumExpiredAuthorization(binding, observation)
            // No lock is held across network I/O. Re-read the full journal under the normal lock before publication.
            if (retirement) outbox.validateRetirementExpiry(capability, now()) else outbox.validateExpired(capability, now())
            running(); capability.requireCurrent(entry, now())
            return capability
        }
    }
}
