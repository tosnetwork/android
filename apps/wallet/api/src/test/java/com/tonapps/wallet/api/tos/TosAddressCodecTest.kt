package com.tonapps.wallet.api.tos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Verifies the address slice encode/decode that jetton balance queries rely on:
 * owner address -> get_wallet_address input slice (addressToSliceBoc)
 * -> address parsed back from the result cell (parseAddressFromBoc) should round-trip.
 */
class TosAddressCodecTest {

    @Test
    fun addressSliceRoundTrip() {
        val raw = "0:1417089bcea623b02cb7703011c6fbc2b50c10f5a5115df426b35151abd12f11"

        val boc = TosSource.addressToSliceBoc(raw)
        assertNotNull("should encode the address into a cell", boc)

        val back = TosSource.parseAddressFromBoc(boc!!)
        assertEquals("address encode/decode should round-trip", raw, back)
        println("[test] roundtrip ok: $back  (boc=$boc)")
    }

    @Test
    fun masterchainAddressRoundTrip() {
        val raw = "-1:0000000000000000000000000000000000000000000000000000000000000000"
        val boc = TosSource.addressToSliceBoc(raw)
        assertNotNull(boc)
        assertEquals(raw, TosSource.parseAddressFromBoc(boc!!))
    }
}
