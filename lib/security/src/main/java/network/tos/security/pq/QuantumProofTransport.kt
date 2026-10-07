package network.tos.security.pq

/** Blocking transport called on the invoking worker thread. Apply timeout and
 * cancellation, enforce maximumBytes during reception, and return raw lite API
 * reply bytes. Replies remain untrusted until native verification completes.
 */
fun interface QuantumProofTransport {
    fun query(request: ByteArray, maximumBytes: Int): ByteArray
}
