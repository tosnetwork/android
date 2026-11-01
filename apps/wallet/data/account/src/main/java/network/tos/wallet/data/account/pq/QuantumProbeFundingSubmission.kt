package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.*
import network.tos.security.pq.QuantumCrypto
import network.tos.security.pq.QuantumRole
import network.tos.security.pq.QuantumPurpose
import org.ton.boc.BagOfCells
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.math.BigInteger
import java.util.UUID

/** Strict historical reconstruction only. No proof, fresh authorization, key access or dispatch API. */
internal class QuantumProbeFundingSubmission private constructor(
    val operation: UUID, val walletId: UUID, val identity: TosQuantumSelfPayIdentity,
    val profile: QuantumNetworkProfile, val amount: BigInteger, val messageHash: String,
    val probeAddress: String, val probeBodyHash: String, val expectedState: QuantumSelfPayState,
) {
    companion object {
        internal fun read(operation: UUID, bytes: ByteArray, signature: ByteArray, approval: ByteArray,
                          prior: QuantumRecoveryControlJournal.Entry,
                          probe: QuantumRecoveryProbeJournal.Entry): QuantumProbeFundingSubmission =
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val id = UUID(input.readLong(), input.readLong())
                val profile = QuantumNetworkProfile(input.readInt(), input.readUTF(), input.readUTF())
                val amountText = input.readUTF()
                require(amountText.matches(Regex("[1-9][0-9]{0,8}")))
                val amount = BigInteger(amountText); QuantumPreparedProbeFunding.requireAmount(amount)
                fun field(): ByteArray {
                    val size = input.readInt(); require(size in 1..131072 && size <= input.available())
                    return ByteArray(size).also(input::readFully)
                }
                val identity = TosQuantumSelfPayIdentity.fromBoc(field())
                check(field().contentEquals(prior.encode())) { "Submitted funding prior control changed" }
                check(field().contentEquals(probe.encode())) { "Submitted funding possession probe changed" }
                val stored = QuantumStoredSelfPayRequest.parse(field()); require(input.read() == -1)
                require(prior.walletId == id && probe.walletId == id && prior.identity == identity && probe.identity == identity)
                require(sameProfile(profile, prior.profile) && sameProfile(profile, probe.profile))
                check(prior.phase == QuantumRecoveryControlJournal.Phase.COMPLETED &&
                    prior.kind == QuantumPreparedRecoveryControl.Kind.LOCK_PRIMARY &&
                    probe.phase == QuantumRecoveryProbeJournal.Phase.SIGNED)
                val before = prior.expectedControlState()
                probe.requireBaseState(before)
                // Rebuild the original codec at its saved deadline, never a current authorization.
                require(stored.deadline > prior.deadline && stored.deadline <= probe.deadline)
                val body = BagOfCells(probe.signedBody()).roots.single()
                val request = TosQuantumSelfPayRequest(identity.address, before, QuantumAuthRole.RESCUE,
                    stored.deadline, QuantumSelfPayAction.Execute(probe.probe.fundingActions(body, amount)), stored.deadline - 1)
                check(stored.cell.hash() == request.request.hash()) { "Submitted funding request changed" }
                check(QuantumCrypto.verify(QuantumRole.RESCUE, QuantumPurpose.SELF_PAY,
                    before.authority.rescueKey, request.digest, signature)) { "Submitted funding signature mismatch" }
                val message = BagOfCells(identity.wallet.external(request.submission(signature), false)).toByteArray()
                QuantumProbeFundingApproval.decode(approval).requireSubmitted(operation, amount, stored.deadline, request.digest, message)
                val after = QuantumSelfPayState(before.authority, before.walletId, before.seqno + 1,
                    before.retired, before.epoch, before.primaryNonce, before.rescueNonce + 1u)
                QuantumProbeFundingSubmission(operation, id, identity, profile, amount,
                    QuantumPaymentQuote.hex(BagOfCells(message).roots.single().hash().toByteArray()),
                    QuantumAccountReader.rawAddressFor(probe.probe.address),
                    QuantumPaymentQuote.hex(body.hash().toByteArray()), after)
            }
    }
}
