package network.tos.wallet.app.ui.screen.ledger.steps

import java.math.BigInteger

data class ProofData(
    val domain: String,
    val timestamp: BigInteger,
    val payload: String,
)