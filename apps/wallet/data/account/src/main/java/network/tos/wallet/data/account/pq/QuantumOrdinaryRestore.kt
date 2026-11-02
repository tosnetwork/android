package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity

/** Public-state policy only. The caller must first authenticate network, account and freshness.
 * Ordinary restore retains existing journals and guards; it neither revokes other devices nor
 * claims to recover their unbacked-up pending operations. */
internal object QuantumOrdinaryRestore {
    fun requireUsable(identity: TosQuantumSelfPayIdentity, status: QuantumSelfPayAccount.Status) {
        when (status) {
            QuantumSelfPayAccount.Status.Absent, QuantumSelfPayAccount.Status.Uninitialized -> Unit
            is QuantumSelfPayAccount.Status.Frozen -> error("Frozen wallet requires a separate recovery flow")
            is QuantumSelfPayAccount.Status.Active -> {
                val state = status.state
                check(state.walletId == identity.wallet.walletId && state.retired == 0 &&
                    state.authority.cell.hash() == identity.wallet.initialAuthority.cell.hash()) {
                    "Current wallet authority differs from this ordinary backup; use the recovery flow"
                }
                check(state.seqno < 0xffffffffL && state.primaryNonce < ULong.MAX_VALUE) { "Wallet execute counter exhausted" }
            }
        }
    }
}
