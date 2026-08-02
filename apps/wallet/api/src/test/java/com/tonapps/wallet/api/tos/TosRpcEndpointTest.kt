package com.tonapps.wallet.api.tos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TosRpcEndpointTest {

    @Test
    fun `adds http scheme to host and IP addresses`() {
        assertEquals("http://node.example:18545", TosRpcEndpoint.normalizeOrNull("node.example:18545"))
        assertEquals("http://10.0.2.2:18545", TosRpcEndpoint.normalizeOrNull("10.0.2.2:18545"))
    }

    @Test
    fun `removes jsonRPC suffix and trailing slash`() {
        assertEquals(
            "https://rpc.example/base",
            TosRpcEndpoint.normalizeOrNull("https://rpc.example/base/jsonRPC/"),
        )
    }

    @Test
    fun `accepts IPv6 node addresses`() {
        assertEquals("http://[::1]:18545", TosRpcEndpoint.normalizeOrNull("http://[::1]:18545"))
    }

    @Test
    fun `rejects unsafe or malformed addresses`() {
        assertNull(TosRpcEndpoint.normalizeOrNull("ftp://rpc.example"))
        assertNull(TosRpcEndpoint.normalizeOrNull("https://user:password@rpc.example"))
        assertNull(TosRpcEndpoint.normalizeOrNull("https://rpc.example?token=secret"))
        assertNull(TosRpcEndpoint.normalizeOrNull("not a host"))
    }
}
