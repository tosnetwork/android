package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.*
import org.junit.Assert.*
import org.junit.Test

/** State policy only, not native proof, import transaction or end-to-end acceptance. */
class QuantumOrdinaryRestoreTest {
    private val authority = QuantumSelfPayAuthority(1, ByteArray(32) { 42 }, ByteArray(1312) { 11 }, ByteArray(32) { 12 })
    private val identity = TosQuantumSelfPayIdentity(TosQuantumSelfPayWallet(authority))
    private fun active(authority: QuantumSelfPayAuthority = this.authority, wallet: Long = identity.wallet.walletId,
        retired: Int = 0, seqno: Long = 7, nonce: ULong = 9uL) =
        QuantumSelfPayAccount.Status.Active(QuantumSelfPayState(authority, wallet, seqno, retired, 3uL, nonce, 4uL))

    @Test fun existingWalletUsesCurrentCountersRatherThanRequiringInitialZeroState() {
        QuantumOrdinaryRestore.requireUsable(identity, active())
        QuantumOrdinaryRestore.requireUsable(identity, QuantumSelfPayAccount.Status.Absent)
        QuantumOrdinaryRestore.requireUsable(identity, QuantumSelfPayAccount.Status.Uninitialized)
    }
    @Test fun changedAuthorityRetirementIdentityAndExhaustionCannotEnableOrdinaryPayment() {
        val changed = QuantumSelfPayAuthority(1, authority.namespace, ByteArray(1312) { 21 }, authority.rescueKey)
        for (state in listOf(active(authority = changed), active(retired = 2), active(wallet = 1),
            active(seqno = 0xffffffffL), active(nonce = ULong.MAX_VALUE))) {
            assertThrows(IllegalStateException::class.java) { QuantumOrdinaryRestore.requireUsable(identity, state) }
        }
    }

    @Test fun frozenAccountRequiresSeparateRecovery() {
        assertThrows(IllegalStateException::class.java) {
            QuantumOrdinaryRestore.requireUsable(identity, QuantumSelfPayAccount.Status.Frozen(ByteArray(32)))
        }
    }
}
