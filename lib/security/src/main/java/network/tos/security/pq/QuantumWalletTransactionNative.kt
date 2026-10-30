package network.tos.security.pq

/** Bounded codec/hash binding only. The expected hash and logical time must come
 * from an independently authenticated account head or committed predecessor.
 * This result alone proves neither inclusion nor successful payment.
 */
object QuantumWalletTransactionNative {
    init {
        System.loadLibrary("tosproofverify")
        System.loadLibrary("toswalletreceipt")
    }

    fun verify(workchain: Int, account: ByteArray, logicalTime: String,
               expectedHash: ByteArray, boc: ByteArray): ByteArray {
        require(workchain == 0 && account.size == 32 && expectedHash.size == 32)
        require(boc.size in 1..1_048_576)
        require(logicalTime.length in 1..20 && logicalTime.first() != '0' &&
            logicalTime.all { it in '0'..'9' } && logicalTime.toULongOrNull() != null)
        return nativeVerify(workchain, account, logicalTime, expectedHash, boc)
    }

    private external fun nativeVerify(workchain: Int, account: ByteArray, logicalTime: String,
                                      expectedHash: ByteArray, boc: ByteArray): ByteArray
}
