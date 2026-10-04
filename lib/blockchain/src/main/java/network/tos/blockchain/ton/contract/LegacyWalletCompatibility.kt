package network.tos.blockchain.ton.contract

import network.tos.blockchain.ton.TONOpCode
import network.tos.blockchain.ton.extensions.bodyCell
import org.ton.block.AddrStd
import org.ton.block.ExtInMsgInfo
import org.ton.block.StateInit
import org.ton.cell.Cell
import org.ton.cell.CellType
import org.ton.cell.buildCell
import org.ton.tlb.CellRef

/** Exact ordinary legacy contracts shipped before native TOS V5.
 * The node's wallet-code registry can omit these contracts. Never infer their
 * sequence from an arbitrary account's data or a failed getter invocation. */
object LegacyWalletCompatibility {
    private data class Profile(
        val version: WalletVersion,
        val code: Cell,
        val signatureFlag: Boolean = false,
        val dictionary: Boolean = false,
    )

    private val profiles = listOf(
        Profile(WalletVersion.V3R1, WalletV3R1Contract.CODE),
        Profile(WalletVersion.V3R2, WalletV3R2Contract.CODE),
        Profile(WalletVersion.V4R1, WalletV4R1Contract.CODE, dictionary = true),
        Profile(WalletVersion.V4R2, WalletV4R2Contract.CODE, dictionary = true),
        Profile(WalletVersion.V5R1, WalletV5R1Contract.CODE, signatureFlag = true, dictionary = true),
    )

    private fun contractProfile(contract: BaseWalletContract): Profile? = profiles.firstOrNull {
        it.version == contract.getWalletVersion() && it.code.hash() == contract.getCode().hash()
    }

    fun isSupported(contract: BaseWalletContract): Boolean = contractProfile(contract) != null

    /** Code and data must come from the same active getAddressInformation result.
     * Reconstructing the initial state binds even address-only API callers to the
     * original public key and wallet ID. Typed callers additionally bind persisted
     * revision, key, ID and address; native TOS and V5 Beta never match a profile. */
    fun verifiedSeqno(address: AddrStd, code: Cell, data: Cell, expected: BaseWalletContract? = null): Int {
        require(code.type == CellType.ORDINARY && data.type == CellType.ORDINARY &&
            code.levelMask.level == 0 && data.levelMask.level == 0) {
            "Legacy wallet code and data must be ordinary level-zero cells"
        }
        val profile = profiles.firstOrNull { it.code.hash() == code.hash() }
            ?: error("Account code is not a supported legacy wallet")
        expected?.let {
            require(contractProfile(it) == profile && it.address == address) { "Legacy wallet metadata mismatch" }
        }
        val slice = data.beginParse()
        if (profile.signatureFlag) require(slice.loadBit()) { "Legacy wallet signatures are disabled" }
        val seqno = slice.loadUInt(32).toLong()
        require(seqno <= Int.MAX_VALUE) { "Unsupported legacy wallet sequence number" }
        val walletId = slice.loadUInt(32)
        val publicKey = slice.loadBits(256)
        // HashmapE has exactly one presence bit and, when present, one reference.
        // Its contents do not change the original empty-dictionary StateInit.
        if (profile.dictionary && slice.loadBit()) slice.loadRef()
        require(slice.bitsPosition == slice.bits.size && slice.refsPosition == slice.refs.size) {
            "Unexpected legacy wallet state data"
        }
        val initialData = buildCell {
            if (profile.signatureFlag) storeBit(true)
            storeUInt(0, 32)
            storeUInt(walletId, 32)
            storeBits(publicKey)
            if (profile.dictionary) storeBit(false)
        }
        val initialHash = CellRef(StateInit(profile.code, initialData), StateInit).hash()
        require(address == AddrStd(address.workchainId, initialHash)) { "Legacy wallet initial address mismatch" }
        expected?.let {
            require(publicKey.toByteArray().contentEquals(it.publicKey.key.toByteArray()) &&
                initialData.hash() == it.getStateCell().hash()) { "Legacy wallet key or ID mismatch" }
        }
        return seqno.toInt()
    }

    /** Anchor retry reconciliation to the nonce actually signed, including when
     * another device sends while this user is authenticating. Legacy V3/V4 put
     * the signature first; V5 puts it after the packed-wallet-ID request. */
    fun signedTransferSeqno(contract: BaseWalletContract, message: Cell): Int {
        val profile = contractProfile(contract) ?: error("Unsupported legacy wallet request")
        val external = contract.parseTransferMessageCell(message)
        val info = external.info as? ExtInMsgInfo ?: error("Expected external legacy wallet message")
        require(info.dest == contract.address) { "Signed message belongs to another wallet" }
        val signed = external.bodyCell
        val minimum = if (profile.signatureFlag) 130 else if (profile.dictionary) 104 else 96
        require(signed.bits.size >= minimum + 512) { "Incomplete signed legacy request" }
        val slice = signed.beginParse()
        if (profile.signatureFlag) {
            require(slice.loadUInt(32).toLong() == TONOpCode.SIGNED_EXTERNAL.code) {
                "Expected external legacy V5 request"
            }
        } else {
            slice.loadBits(512)
        }
        val initial = contract.getStateCell().beginParse()
        if (profile.signatureFlag) initial.loadBit()
        initial.loadUInt(32)
        val expectedId = initial.loadUInt(32)
        require(slice.loadUInt(32) == expectedId) { "Signed wallet ID does not match legacy wallet" }
        slice.loadUInt(32) // valid_until
        val seqno = slice.loadUInt(32).toLong()
        require(seqno <= Int.MAX_VALUE) { "Unsupported legacy wallet sequence number" }
        return seqno.toInt()
    }
}
