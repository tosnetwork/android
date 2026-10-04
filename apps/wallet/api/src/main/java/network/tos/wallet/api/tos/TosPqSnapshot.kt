package network.tos.wallet.api.tos

import network.tos.blockchain.ton.contract.TosPqWallet
import network.tos.blockchain.ton.extensions.cellFromBase64

/** Reads one selected endpoint at one masterchain height before local signing.
 * The configured node is the trust anchor; this is not a light-client proof verifier. */
data class TosPqSnapshot(val height: Int, val epoch: ULong, val nonce: ULong,
    val balance: java.math.BigInteger, val chainTime: Long) {
    companion object {
        fun read(source: TosSource, wallet: TosPqWallet, testnet: Boolean = false): TosPqSnapshot {
            val node = source.snapshot(testnet)
            val height = requireNotNull(node.getMasterchainInfo(testnet).last).seqno
            val network = TosNetworkInfo.fromConfig(node.getConfigParam(19, height, testnet), node.getConfigParam(8, height, testnet))
            require(network.globalId == wallet.network && network.vmVersion >= wallet.minimumVm) { "PQ network/profile mismatch" }
            fun raw(address: org.ton.block.AddrStd) = "${address.workchainId}:${address.address.toByteArray().joinToString("") { "%02x".format(it) }}"
            val module = node.getAccountState(raw(wallet.moduleAddress), testnet, height)
            val account = node.getAccountState(raw(wallet.address), testnet, height)
            require(module.isActive && account.isActive) { "Funded PQ wallet and root deployment required" }
            require(requireNotNull(module.codeBoc).cellFromBase64().hash() == wallet.moduleCode.hash()) { "Unexpected PQ root code" }
            require(requireNotNull(module.dataBoc).cellFromBase64().hash() == wallet.moduleData.hash()) { "Unexpected PQ root key/network/profile" }
            val (epoch, nonce) = wallet.authCounters(requireNotNull(account.codeBoc).cellFromBase64(), requireNotNull(account.dataBoc).cellFromBase64())
            require(account.syncUtime in 1..0xffffffffL) { "Missing chain timestamp" }
            return TosPqSnapshot(height, epoch, nonce, account.balance, account.syncUtime)
        }
    }
}
