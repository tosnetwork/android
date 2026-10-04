package network.tos.wallet.api.tos

import network.tos.blockchain.ton.contract.TosWalletV5R1Contract
import network.tos.blockchain.ton.extensions.cellFromBase64
import java.math.BigInteger

/** Fee authority must be the reviewed ordinary native V5 wallet, with no extensions. */
data class TosPqFeeSnapshot(val seqno: Int, val chainTime: Long, val balance: BigInteger) {
    companion object {
        fun read(source: TosSource, payer: TosWalletV5R1Contract, minimumVm: Int, testnet: Boolean): TosPqFeeSnapshot {
            require(payer.subwalletNumber == 0L)
            val node = source.snapshot(testnet)
            val head = requireNotNull(node.getMasterchainInfo(testnet).last).seqno
            val network = TosNetworkInfo.fromConfig(node.getConfigParam(19, head, testnet), node.getConfigParam(8, head, testnet))
            require(network.globalId == payer.networkGlobalId && network.vmVersion >= minimumVm)
            val address = "${payer.address.workchainId}:${payer.address.address.toByteArray().joinToString("") { "%02x".format(it) }}"
            val state = node.getAccountState(address, testnet, head)
            require(state.syncUtime in 1..0xffffffffL)
            if (state.status in setOf("uninit", "uninitialized")) return TosPqFeeSnapshot(0, state.syncUtime, state.balance)
            require(state.isActive && requireNotNull(state.codeBoc).cellFromBase64().hash() == payer.getCode().hash())
            val data = requireNotNull(state.dataBoc).cellFromBase64().beginParse()
            require(data.loadBit()) { "Fee payer does not permit classical transport" }
            val seqno = data.loadUInt(32).toLong()
            require(seqno <= Int.MAX_VALUE && data.loadUInt(32).toLong() == 0L &&
                data.loadBits(256).toByteArray().contentEquals(payer.publicKey.key.toByteArray()) && !data.loadBit() &&
                data.bitsPosition == data.bits.size && data.refsPosition == data.refs.size) { "Fee wallet key/state mismatch" }
            return TosPqFeeSnapshot(seqno.toInt(), state.syncUtime, state.balance)
        }
    }
}
