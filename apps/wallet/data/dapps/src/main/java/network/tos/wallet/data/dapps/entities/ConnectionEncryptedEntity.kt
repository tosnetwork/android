package network.tos.wallet.data.dapps.entities

import network.tos.security.CryptoBox

data class ConnectionEncryptedEntity(
    val keyPair: CryptoBox.KeyPair,
    val proofSignature: String?,
    val proofPayload: String?,
)