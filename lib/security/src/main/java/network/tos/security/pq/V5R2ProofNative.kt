package network.tos.security.pq

/** Raw in-process proof boundary. The caller must provision a trusted anchor,
 * serialize reads, durably commit nextState before using results, and bind the
 * proven account/configuration to the wallet. No signing authority is inferred.
 */
object V5R2ProofNative {
    init { System.loadLibrary("tosproofverify") }

    class Result internal constructor(val verifiedJson: ByteArray, val nextState: ByteArray)

    fun verify(anchor: ByteArray, request: ByteArray, priorState: ByteArray, localNow: Long,
               kinds: IntArray, material: Array<ByteArray>): Result {
        require(localNow > 0 && anchor.size in 1..1_048_576 && request.size in 1..1_048_576)
        require(priorState.size <= 1_048_576 && kinds.size <= 1045 && kinds.size == material.size)
        require(kinds.all { it in 1..7 })
        require(material.all { it.isNotEmpty() } && material.sumOf { it.size.toLong() } <= 67_108_864L)
        val output = nativeVerify(anchor, request, priorState, localNow, kinds, material)
        check(output.size == 2 && output[0].isNotEmpty())
        return Result(output[0], output[1])
    }

    private external fun nativeVerify(anchor: ByteArray, request: ByteArray, priorState: ByteArray,
                                      localNow: Long, kinds: IntArray, material: Array<ByteArray>): Array<ByteArray>
}
