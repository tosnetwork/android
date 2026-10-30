package network.tos.security.pq

/** Bounded local directory I/O, shared by all supported Android API levels (24+). */
object QuantumLocalDirectory {
    init { System.loadLibrary("tos_mobile_pq") }
    fun names(path: String, maximum: Int): List<String> {
        require(path.startsWith('/') && '\u0000' !in path && path.toByteArray(Charsets.UTF_8).size <= 4096)
        require(maximum in 1..256)
        return nativeNames(path, maximum).toList().sorted()
    }
    private external fun nativeNames(path: String, maximum: Int): Array<String>
}
