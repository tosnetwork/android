package network.tos.wallet.api.tos

import network.tos.blockchain.ton.extensions.cellFromBase64

/** Configuration-derived capabilities, independent of the user's wallet key algorithm. */
data class TosNetworkInfo(val globalId: Int, val vmVersion: Long) {
    // Independently executed VM0..18 matrix: ordinary native V5 sends first
    // compute/action successfully, with an actual outgoing message, at VM6.
    val supportsNativeV5: Boolean get() = vmVersion >= 6
    val supportsMlDsa: Boolean get() = vmVersion >= 16
    // Experimental TOS-FALCON512-PADDED-v1 cannot execute on version-18 nodes.
    val supportsFalcon: Boolean get() = vmVersion >= 19

    fun requireNativeV5(): TosNetworkInfo = apply {
        require(supportsNativeV5) { "This node cannot execute native TOS V5 wallets (VM6 required)" }
    }

    companion object {
        fun fromConfig(globalIdBoc: String, versionBoc: String): TosNetworkInfo {
            val network = globalIdBoc.cellFromBase64().beginParse()
            require(network.bits.size == 32 && network.refs.isEmpty()) { "Invalid ConfigParam 19" }
            val globalId = network.loadInt(32).toInt()
            val version = versionBoc.cellFromBase64().beginParse()
            require(version.bits.size == 104 && version.refs.isEmpty()) { "Invalid ConfigParam 8" }
            require(version.loadUInt(8).toInt() == 0xc4) { "Invalid global version tag" }
            return TosNetworkInfo(globalId, version.loadUInt(32).toLong())
        }
    }
}
