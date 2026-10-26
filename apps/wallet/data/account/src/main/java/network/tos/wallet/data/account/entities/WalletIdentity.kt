package network.tos.wallet.data.account.entities

import android.util.Base64
import network.tos.blockchain.ton.contract.TosQuantumSelfPayIdentity
import network.tos.blockchain.ton.contract.WalletVersion
import org.ton.api.pub.PublicKeyEd25519

/** Public identity only. Current authority and key custody are separate from the birth address. */
sealed interface WalletIdentity {
    val version: WalletVersion
    val networkGlobalId: Int?

    data class Classical(
        val publicKey: PublicKeyEd25519,
        override val version: WalletVersion,
        override val networkGlobalId: Int? = null,
    ) : WalletIdentity {
        init {
            require(version != WalletVersion.QUANTUM_SELF_PAY)
            require(publicKey.key.size == 32)
        }
    }

    data class Quantum(val birth: TosQuantumSelfPayIdentity) : WalletIdentity {
        override val version: WalletVersion get() = WalletVersion.QUANTUM_SELF_PAY
        override val networkGlobalId: Int get() = birth.wallet.initialAuthority.globalId
        fun toBase64(): String = Base64.encodeToString(birth.toBoc(), Base64.NO_WRAP)
    }

    companion object {
        fun quantumFromBase64(encoded: String): TosQuantumSelfPayIdentity {
            require(encoded.length <= 4 * ((TosQuantumSelfPayIdentity.MAX_BOC_BYTES + 2) / 3))
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(Base64.encodeToString(bytes, Base64.NO_WRAP) == encoded) { "Noncanonical Quantum identity" }
            return TosQuantumSelfPayIdentity.fromBoc(bytes)
        }

        /** Reject mixed rows rather than silently choosing a key or a network. */
        fun restore(publicKey: ByteArray?, quantumBOC: ByteArray?, version: WalletVersion, networkGlobalId: Int?): WalletIdentity {
            if (version == WalletVersion.QUANTUM_SELF_PAY) {
                require(publicKey == null && quantumBOC != null) { "Quantum identity cannot contain a classical key" }
                val birth = TosQuantumSelfPayIdentity.fromBoc(quantumBOC)
                require(networkGlobalId == birth.wallet.initialAuthority.globalId) { "Quantum network identity mismatch" }
                return Quantum(birth)
            }
            require(quantumBOC == null && publicKey != null && publicKey.size == 32) { "Invalid classical identity" }
            return Classical(PublicKeyEd25519(publicKey), version, networkGlobalId)
        }
    }
}
