package com.tonapps.wallet.api.tos

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Verifies the W4 jetton parsing against a real get_wallet_data stack captured from
 * the local TOS node (jetton minted to 0:5555, balance 1e9). TOS runGetMethod returns
 * the stack top-first, so for the standard return (balance, owner, jetton, code) the
 * layout is [code, jetton, owner, balance]: balance at index n-1, master at index n-3.
 */
class TosJettonDataTest {

    private val getWalletDataStack =
        "[[\"cell\",{\"bytes\":\"te6ccgECEQEAAyMAART/APSkE/S88sgLAQIBYgIDAgLMBAUAG6D2BdqJofQB9IH0gahhAgHUBgcCASAICQDDCDHAJJfBOAB0NMDAXGwlRNfA/AM4PpA+kAx+gAxcdch+gAx+gAwc6m0AALTH4IQD4p+pVIgupUxNFnwCeCCEBeNRRlSILqWMUREA/AK4DWCEFlfB7y6k1nwC+BfBIQP8vCAAET6RDBwuvLhTYAIBIAoLAIPUAQa5D2omh9AH0gfSBqGAJpj8EIC8aijKkQXUEIPe7L7wndCVj5cWLpn5j9ABgJ0CgR5CgCfQEsZ4sA54tmZPaqQB8VA9M/+gD6QCHwAe1E0PoA+kD6QNQwUTahUirHBfLiwSjC//LiwlQ0QnBUIBNUFAPIUAT6AljPFgHPFszJIsjLARL0APQAywDJIPkAcHTIywLKB8v/ydAE+kD0BDH6ACDXScIA8uLEd4AYyMsFUAjPFnD6AhfLaxPMgMAgEgDQ4AnoIQF41FGcjLHxnLP1AH+gIizxZQBs8WJfoCUAPPFslQBcwjkXKRceJQCKgToIIJycOAoBS88uLFBMmAQPsAECPIUAT6AljPFgHPFszJ7VQC9ztRND6APpA+kDUMAjTP/oAUVGgBfpA+kBTW8cFVHNtcFQgE1QUA8hQBPoCWM8WAc8WzMkiyMsBEvQA9ADLAMn5AHB0yMsCygfL/8nQUA3HBRyx8uLDCvoAUaihggiYloBmtgihggiYloCgGKEnlxBJEDg3XwTjDSXXCwGAPEADXO1E0PoA+kD6QNQwB9M/+gD6QDBRUaFSSccF8uLBJ8L/8uLCBYIJMS0AoBa88uLDghB73ZfeyMsfFcs/UAP6AiLPFgHPFslxgBjIywUkzxZw+gLLaszJgED7AEATyFAE+gJYzxYBzxbMye1UgAHBSeaAYoYIQc2LQnMjLH1Iwyz9Y+gJQB88WUAfPFslxgBDIywUkzxZQBvoCFctqFMzJcfsAECQQIwB8wwAjwgCwjiGCENUydttwgBDIywVQCM8WUAT6AhbLahLLHxLLP8ly+wCTNWwh4gPIUAT6AljPFgHPFszJ7VQ=\"}],[\"slice\",{\"bytes\":\"te6ccgEBAQEAJAAAQ4ACEmDUT/QbgHGOIg7ZSn6qZ40T0HG7HS9kcRjmgJi7YfA=\"}],[\"slice\",{\"bytes\":\"te6ccgEBAQEAJAAAQ4AKqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqrA=\"}],[\"num\",\"1000000000\"]]"

    @Test
    fun parsesGetWalletData() {
        val stack = JSONArray(getWalletDataStack)
        val n = stack.length()
        assertEquals(4, n)

        val balance = TosSource.stackReadBigInteger(stack, n - 1)
        assertEquals(BigInteger("1000000000"), balance)

        val masterBoc = TosSource.stackReadCellBytes(stack, n - 3)
        assertNotNull("master slice bytes present", masterBoc)

        val master = TosSource.parseAddressFromBoc(masterBoc!!)
        assertNotNull("master address parsed", master)
        assertTrue("master is a wc address, actual=$master", master!!.startsWith("0:") || master.startsWith("-1:"))
        println("[test] jetton balance=$balance master=$master")
    }
}
