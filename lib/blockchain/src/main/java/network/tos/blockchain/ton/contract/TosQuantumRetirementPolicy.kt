package network.tos.blockchain.ton.contract

import org.ton.cell.Cell
import org.ton.cell.CellType

/** Exact ConfigParam48 v1 semantics. Caller must bind authenticated checkpoint and local time. */
object TosQuantumRetirementPolicy {
    private val spec = "5e4380aedc95f8cb72de55f7506de0269b47c03ad1d1ed0e5184c332544262c0"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    fun requirePrimary(cell: Cell, network: ByteArray, now: Long) {
        require(network.size == 32 && now in 0..0xffffffffL)
        require(cell.type == CellType.ORDINARY && cell.levelMask.level == 0) { "Ordinary retirement policy required" }
        val s = cell.beginParse()
        require(s.loadUInt(8).toInt() == 0xa1) { "Retirement policy version" }
        require(s.loadBits(256).toByteArray().contentEquals(network)) { "Retirement network mismatch" }
        s.loadBits(64) // Authenticated sequence; governance enforces transitions.
        val retired = s.loadUInt(16).toInt()
        require(retired and 2.inv() == 0) { "Unknown retirement bits" }
        val schedule = if (s.loadBit()) s.loadRef() else null
        require(s.loadBits(256).toByteArray().contentEquals(spec)) { "Retirement specification mismatch" }
        require(s.remainingBits == 0 && s.refsPosition == s.refs.size) { "Retirement trailing data" }
        var deadline = 0L
        if (schedule != null) {
            require(schedule.type == CellType.ORDINARY && schedule.levelMask.level == 0) { "Ordinary schedule required" }
            val d = schedule.beginParse()
            var same = false; var sameBit = false
            val length = if (!d.loadBit()) {
                var count = 0
                while (d.loadBit()) { count++; require(count <= 8) { "Invalid schedule label" } }
                count
            } else if (!d.loadBit()) d.loadUInt(4).toInt()
            else { same = true; sameBit = d.loadBit(); d.loadUInt(4).toInt() }
            // A shorter label implies a fork and multiple suite keys, forbidden in v1.
            require(length == 8) { "Unsupported retirement schedule" }
            val key = if (same) if (sameBit) 255 else 0 else d.loadUInt(8).toInt()
            require(key == 1 && d.remainingBits == 32 && d.refsPosition == d.refs.size) { "Unsupported retirement schedule" }
            deadline = d.loadUInt(32).toLong()
            require(deadline > 0) { "Zero retirement deadline" }
        }
        require(retired and 2 == 0 && (deadline == 0L || now < deadline)) { "Primary suite retired" }
    }
}
