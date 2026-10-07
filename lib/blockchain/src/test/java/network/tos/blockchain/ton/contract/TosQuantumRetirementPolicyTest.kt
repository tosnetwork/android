package network.tos.blockchain.ton.contract

import org.junit.Assert.*
import org.junit.Test
import org.ton.cell.buildCell

class TosQuantumRetirementPolicyTest {
    private val network = ByteArray(32) { 1 }
    private val spec = "5e4380aedc95f8cb72de55f7506de0269b47c03ad1d1ed0e5184c332544262c0".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun policy(retired: Int = 0, deadline: Long? = null, key: Int = 1, short: Boolean = false,
                       validSpec: Boolean = true, tag: Int = 0xa1, tail: Boolean = false) = buildCell {
        storeUInt(tag, 8); storeBytes(network); storeUInt(7, 64); storeUInt(retired, 16); storeBit(deadline != null)
        if (deadline != null) storeRef(buildCell {
            if (short) { storeBit(false); repeat(8) { storeBit(true) }; storeBit(false) }
            else { storeUInt(2, 2); storeUInt(8, 4) }
            storeUInt(key, 8); storeUInt(deadline, 32)
        })
        storeBytes(if (validSpec) spec else ByteArray(32)); if (tail) storeBit(false)
    }
    @Test fun absentAndBothLegalSingleKeyLabelsWorkUntilDeadline() {
        TosQuantumRetirementPolicy.requirePrimary(policy(), network, 4620)
        for (short in listOf(false, true)) {
            TosQuantumRetirementPolicy.requirePrimary(policy(deadline = 4700, short = short), network, 4620)
            assertTrue("Scheduled retirement ignored", runCatching { TosQuantumRetirementPolicy.requirePrimary(policy(deadline = 4620, short = short), network, 4620) }.isFailure)
        }
    }
    @Test fun retiredUnknownMalformedAndWrongNetworkFailClosed() {
        for (cell in listOf(policy(retired = 2), policy(retired = 4), policy(deadline = 0), policy(deadline = 4700, key = 2),
                            policy(validSpec = false), policy(tag = 0xa2), policy(tail = true)))
            assertTrue("Invalid retirement policy accepted", runCatching { TosQuantumRetirementPolicy.requirePrimary(cell, network, 4620) }.isFailure)
        assertTrue("Wrong network accepted", runCatching { TosQuantumRetirementPolicy.requirePrimary(policy(), ByteArray(32), 4620) }.isFailure)
    }
}
