package network.tos.signer.screen.sign

import network.tos.blockchain.ton.TONOpCode
import network.tos.blockchain.ton.contract.TosV5SigningRequest
import org.ton.cell.Cell

/** URI labels cannot change the meaning of the bytes being authorized. */
object SignerRequestPolicy {
    fun requireSupported(body: Cell, version: String, network: Int, seqno: Int, nativeKey: Boolean = false) {
        val nativeVersion = version.equals("tosv5r1", ignoreCase = true)
        require(!nativeKey || nativeVersion) { "Native TOS keys require the native request profile" }
        // Native unsigned V5 has at least five uint32 fields and two flags.
        // Legacy V5's ordinary header has four uint32 fields and two flags.
        val nativeHeader = body.bits.size >= 162 && runCatching {
            body.beginParse().loadUInt(32).toLong() in
                setOf(TONOpCode.SIGNED_EXTERNAL.code, TONOpCode.SIGNED_INTERNAL.code)
        }.getOrDefault(false)
        require(!nativeHeader || nativeVersion) { "Native TOS signing bytes have a mismatched version label" }
        if (nativeVersion) TosV5SigningRequest.parse(body, network, seqno)
    }
}
