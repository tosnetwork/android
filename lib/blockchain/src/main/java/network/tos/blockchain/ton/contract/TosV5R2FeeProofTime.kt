package network.tos.blockchain.ton.contract

/** Local authorization clock versus authenticated masterchain/vault shard times. */
object TosV5R2FeeProofTime {
    fun check(master: Long, shard: Long, now: Long, maximumAge: Long, epoch0: Long) {
        require(listOf(master, shard, now, epoch0).all { it in 0..0xffffffffL }) { "Unsigned fee time required" }
        require(maximumAge in 1..3599 && now >= epoch0) { "Invalid fee time policy" }
        val slot = (now - epoch0) / 3600
        for (time in listOf(master, shard)) {
            require(time <= now && time >= epoch0 && now - time <= maximumAge) { "Stale or future fee proof" }
            require((time - epoch0) / 3600 == slot) { "Fee proof from another slot" }
        }
    }
}
