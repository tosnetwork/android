package network.tos.wallet.data.account.pq

/** Time arithmetic only; the caller must obtain time from authenticated observations. */
internal object QuantumMigrationDeadline {
    const val MOBILE_TTL = 120L
    fun choose(provenTime: Long, ttl: Long, retainedDeadline: Long? = null): Long {
        require(provenTime in 1..0xffffffffL && ttl in 1..3600)
        val deadline = retainedDeadline ?: (provenTime + ttl)
        require(deadline in 1..0xffffffffL && deadline - provenTime in 1..ttl) { "Migration deadline expired or outside original window" }
        return deadline
    }
}
