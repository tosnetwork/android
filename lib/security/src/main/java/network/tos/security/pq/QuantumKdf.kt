package network.tos.security.pq

internal object QuantumKdfNative {
    init { System.loadLibrary("tos_mobile_pq") }
    external fun derive(material: Int, master: ByteArray, network: ByteArray, globalId: Int, account: Long, generation: Long, tree: ByteArray?): ByteArray?
}

/** Native master input only. Derivation neither establishes independent custody nor restores a fee journal. */
object QuantumKdf {
    enum class Material(val id: Int) { PRIMARY(1), RESCUE(2), FEE(3) }
    fun deriveAndWipe(material: Material, master: ByteArray, network: ByteArray, globalId: Int,
                      account: Long, generation: Long, tree: ByteArray? = null): ByteArray {
        try {
            require(master.size == 32 && network.size == 32)
            require(account in 0..0xffffffffL && generation in 0..0xffffffffL)
            require(if (material == Material.FEE) tree?.size == 32 else tree == null)
            return QuantumKdfNative.derive(material.id, master, network, globalId, account, generation, tree)
                ?: error("Native Quantum derivation failed")
        } finally { master.fill(0) }
    }
}
