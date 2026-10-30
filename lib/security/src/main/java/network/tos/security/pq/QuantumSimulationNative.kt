package network.tos.security.pq

import java.security.MessageDigest

/** A local-estimate capability, never an account read, receipt or signing grant. */
class QuantumLocalSimulation private constructor(
    encoded: ByteArray, anchor: ByteArray, request: ByteArray, message: ByteArray, val verifiedAt: Long,
) {
    private val bytes = encoded.copyOf()
    val encoded: ByteArray get() = bytes.copyOf()
    private val anchorHash = digest(anchor)
    private val requestHash = digest(request)
    private val messageHash = digest(message)
    fun requireBinding(anchor: ByteArray, request: ByteArray, message: ByteArray, now: Long, maximumAge: Long) {
        require(maximumAge in 1..300 && now >= verifiedAt && now - verifiedAt < maximumAge)
        check(anchorHash.contentEquals(digest(anchor)) && requestHash.contentEquals(digest(request)) &&
            messageHash.contentEquals(digest(message))) { "Local estimate binding mismatch" }
    }
    companion object {
        private fun digest(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
        internal fun acquire(directory: String, anchor: ByteArray, request: ByteArray, now: Long,
                             message: ByteArray, transport: QuantumProofTransport): QuantumLocalSimulation {
            val a = anchor.copyOf(); val r = request.copyOf(); val m = message.copyOf()
            require(now in 1..0xffffffffL && a.size in 1..1_048_576 && r.size in 1..1_048_576 && m.size in 1..1_048_576)
            val encoded = QuantumSimulationNative.nativeSimulate(directory, a, r, now, m, transport)
            check(encoded.size in 1..2_097_152)
            return QuantumLocalSimulation(encoded, a, r, m, now)
        }
    }
}

internal object QuantumSimulationNative {
    init { System.loadLibrary("toswalletreceipt") }
    class Refused(val code: Int) : IllegalStateException("Local simulation refused ($code)")
    fun simulate(directory: String, anchor: ByteArray, request: ByteArray, now: Long,
                 message: ByteArray, transport: QuantumProofTransport): QuantumLocalSimulation =
        QuantumLocalSimulation.acquire(directory, anchor, request, now, message, transport)
    external fun nativeSimulate(directory: String, anchor: ByteArray, request: ByteArray, now: Long,
                                message: ByteArray, transport: QuantumProofTransport): ByteArray
}
