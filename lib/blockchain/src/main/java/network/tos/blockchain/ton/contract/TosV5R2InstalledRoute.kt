package network.tos.blockchain.ton.contract

import org.ton.block.AddrStd
import org.ton.cell.Cell

/** Locally reconstructed tuple; never proof of deployment, POP, custody or migration approval. */
class TosV5R2InstalledRoute private constructor(
    val moduleData: Cell, val moduleInit: Cell, val metadata: Cell,
    val vaultData: Cell, val vaultInit: Cell,
    val moduleAddress: AddrStd, val vaultAddress: AddrStd
) {
    /** Local module identity check; callers must separately authenticate its installed data. */
    fun requirePrimaryKey(publicKey: ByteArray) {
        require(publicKey.size == 1312 && TosPqWallet.byteChain(publicKey).hash() == moduleData.refs.single().hash()) {
            "Primary custody key differs from module enrollment"
        }
    }
    companion object {
        fun initial(birth: TosV5R2Genesis) = TosV5R2InstalledRoute(birth.moduleData, birth.moduleInit, birth.metadata,
            birth.vaultData, birth.vaultInit, birth.moduleAddress, birth.vaultAddress)
        fun successor(birth: TosV5R2Genesis, next: TosV5R2Genesis): TosV5R2InstalledRoute {
            require(next.moduleInit.refs[0].hash() == birth.moduleInit.refs[0].hash() &&
                    next.vaultInit.refs[0].hash() == birth.vaultInit.refs[0].hash()) { "Successor code identity mismatch" }
            val original = birth.moduleData.beginParse(); val replacement = next.moduleData.beginParse()
            original.loadUInt(8); replacement.loadUInt(8)
            require(original.loadBits(32 + 256) == replacement.loadBits(32 + 256)) { "Successor namespace mismatch" }
            val paired = next.successorFor(birth.address).first
            return TosV5R2InstalledRoute(next.moduleData, next.moduleInit, next.metadata, paired.refs[1], paired,
                next.moduleAddress, AddrStd(0, paired.hash()))
        }
    }
}
