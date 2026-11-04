package network.tos.wallet.data.account.pq

import network.tos.blockchain.ton.contract.QuantumSelfPayState

/** Full contract-data consistency only. A match is never proof of transaction execution. */
internal object QuantumMigrationStateMatch {
    fun matches(current: QuantumSelfPayState?, expected: QuantumSelfPayState): Boolean =
        current?.cell?.hash() == expected.cell.hash()
}
