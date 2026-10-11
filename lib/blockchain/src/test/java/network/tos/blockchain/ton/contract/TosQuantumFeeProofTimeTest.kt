package network.tos.blockchain.ton.contract

import org.junit.Assert.*
import org.junit.Test

class TosQuantumFeeProofTimeTest {
    @Test fun freshSameSlotAndExactAgeAccepted() {
        TosQuantumFeeProofTime.check(4610, 4600, 4620, 30, 1000)
        TosQuantumFeeProofTime.check(4600, 4600, 4630, 30, 1000)
    }
    @Test fun boundariesFailClosedWithoutWrapping() {
        for (row in listOf(
            longArrayOf(4610,4599,4620,30,1000), longArrayOf(4599,4610,4620,30,1000),
            longArrayOf(4610,4600,4631,30,1000), longArrayOf(4600,4610,4631,30,1000),
            longArrayOf(4621,4610,4620,30,1000), longArrayOf(4610,4621,4620,30,1000),
            longArrayOf(4620,4620,4620,0,1000), longArrayOf(4620,4620,4620,3600,1000),
            longArrayOf(999,999,999,30,1000), longArrayOf(999,1000,1001,30,1000),
            longArrayOf(1000,999,1001,30,1000), longArrayOf(-1,0,0,1,0),
            longArrayOf(0,0,Long.MAX_VALUE,1,0)))
            assertTrue("Unsafe fee time accepted", runCatching { TosQuantumFeeProofTime.check(row[0],row[1],row[2],row[3],row[4]) }.isFailure)
    }
}
