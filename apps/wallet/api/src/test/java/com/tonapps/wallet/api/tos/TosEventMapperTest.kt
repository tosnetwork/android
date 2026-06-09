package com.tonapps.wallet.api.tos

import io.tonapi.models.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Uses the raw BOC of a real incoming transaction from the local TOS localnet
 * (faucet sending 5 TOS to a new wallet) to verify TosEventMapper parses it into
 * an AccountEvent TonTransfer correctly.
 */
class TosEventMapperTest {

    // Used for the event.account field; not involved in transfer parsing (counterparties come from the BOC).
    private val account = "0:0000000000000000000000000000000000000000000000000000000000000000"

    private val incomingBoc =
        "te6ccgECCQEAAgQAA7NxQXCJvOpiOwLLdwMBHG+8K1DBD1pRFd9CazUVGr0S8RAAAAAADVn4EAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAaia2UgABxAfQgBAgMBAaAEAIJykK7Illr6uxbrw8ubQI665xthjXh4i8gNCYQ1k8rJjaTDS3H/hHIP+UHhXXLckjR0Fm0Vd8c7mm744PhZYWa+ngIVDAlASoF8gB5AfREHCAKtSf4AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAFBcIm86mI7Ast3AwEcb7wrUMEPWlEV30JrNRUavRLxFQEqBfIAAAAAAAAAYy6hNRNbKUZBQYAmP8AIN0gggFMl7qXMO1E0NcLH+Ck8mCBAgDXGCDXCx/tRNDTH9P/0VESuvKhIvkBVBBE+RDyovgAAdMfMdMH1NEB+wCkyMsfy//J7VQASAAAAAABxhmt88/oSBpuhulZxfJ5w+SMsCRycUtvEw77XdydHACcJ8w9CQAAAAAAAAAAAAMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFvAAAAAAAAAAAAAAAABLUUtpEnlC4z33SeGHxRhIq/htUa7i3D8ghbwxhQTn44E"

    @Test
    fun parsesIncomingTonTransfer() {
        val tx = TosRawTransaction(
            lt = 14000001L,
            hash = "Wj0SXHjVU1n8WWxtQjAbNELIHKa3IEkYtk9VyU6SJzk=",
            utime = 1780921938L,
            fee = BigInteger.ZERO,
            account = "0:...",
            dataBoc = incomingBoc,
            inMsgHash = null,
        )

        val events = TosEventMapper.toAccountEvents(account, listOf(tx))

        assertEquals("should parse 1 event", 1, events.size)
        val event = events.first()
        assertEquals(14000001L, event.lt)

        val transfers = event.actions.filter { it.type == Action.Type.TonTransfer }
        assertTrue("should have at least 1 TON transfer", transfers.isNotEmpty())

        val t = transfers.first().tonTransfer!!
        // faucet sent 5 TOS; the recipient loses a tiny fwd fee, so ~4.99-5.00 TOS
        assertTrue("amount should be ~5 TOS, actual=${t.amount}", t.amount in 4_900_000_000L..5_000_000_000L)
        // recipient should be a wc=0 wallet address
        assertTrue("recipient should be a wc0 address, actual=${t.recipient.address}", t.recipient.address.startsWith("0:"))
        println("[test] TonTransfer amount=${t.amount} sender=${t.sender.address} recipient=${t.recipient.address}")
    }
}
