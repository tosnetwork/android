package network.tos.blockchain.ton.contract

import org.junit.Assert.*
import org.junit.Test
import org.ton.cell.buildCell
import network.tos.blockchain.ton.extensions.loadAddress
import network.tos.blockchain.ton.extensions.loadCoins

class TosPqWalletTest {
    @Test fun transportContainsCanonicalTaggedAddressesAndBoundRequests() {
        for (algorithm in listOf(1,2)) {
            val key = ByteArray(if(algorithm==1)1312 else 897) { 1 }
            val wallet = TosPqWallet(algorithm,key,3)
            assertEquals(16, wallet.minimumVm)
            val original = wallet.address;key.fill(2);wallet.publicKey.fill(3)
            assertEquals(original,wallet.address);assertEquals(1,wallet.publicKey[0].toInt())
            val relay = TosPqRelay(wallet)
            val plan = relay.deployment(5_000_000_000,20_000_000_000)
            for ((index,message) in plan.messages.withIndex()) {
                val slice = message.beginParse();assertEquals(16,slice.loadUInt(6).toInt())
                assertEquals(if(index==0)wallet.moduleAddress else wallet.address,slice.loadAddress())
                assertEquals(if(index==0)5_000_000_000L else 20_000_000_000L,slice.loadCoins().amount.toLong())
            }
            val request = wallet.request(0u,0u,2100,2000,buildCell { storeUInt(123,32) })
            val submission = relay.submission(request,ByteArray(if(algorithm==1)2420 else 666),2_000_000_000) { message, _ ->
                assertArrayEquals(wallet.signingMessage(request),message);true
            }
            val slice = submission.messages.single().beginParse();assertEquals(16,slice.loadUInt(6).toInt());assertEquals(wallet.moduleAddress,slice.loadAddress())
        }
    }
    @Test fun independentChainWireGoldenCommitments() {
        val lines = requireNotNull(javaClass.getResourceAsStream("/tos-pq-auth-vectors.tsv"))
            .bufferedReader().use { it.readLines() }.filter { !it.startsWith("#") }
        assertEquals(2, lines.size)
        for (line in lines) {
            val v = line.split('\t')
            val wallet = TosPqWallet(v[0].toInt(), org.ton.crypto.hex(v[1]), 3)
            assertEquals(v[2], "${wallet.moduleAddress.workchainId}:${wallet.moduleAddress.address.toByteArray().joinToString("") { "%02x".format(it) }}")
            assertEquals(v[3], "${wallet.address.workchainId}:${wallet.address.address.toByteArray().joinToString("") { "%02x".format(it) }}")
            val request = wallet.request(0u, 0u, 2000000600L, 2000000000L, buildCell { storeUInt(123, 32) })
            assertArrayEquals(org.ton.crypto.hex(v[4]), wallet.signingMessage(request))
            assertArrayEquals(org.ton.crypto.hex(v[6]), wallet.submission(request, org.ton.crypto.hex(v[5])).hash().toByteArray())
        }
    }
    @Test fun strictPqFromGenesisAndCounterBinding() {
        for (algorithm in listOf(1, 2)) {
            val wallet = TosPqWallet(algorithm, ByteArray(if (algorithm == 1) 1312 else 897) { 1 }, 3)
            assertEquals(0uL to 0uL, wallet.authCounters(wallet.walletCode, wallet.walletData))
            assertNotEquals(wallet.address, wallet.moduleAddress)
            assertThrows(IllegalArgumentException::class.java) {
                wallet.authCounters(wallet.moduleCode, wallet.walletData)
            }
            val payload = buildCell { storeUInt(123, 32) }
            assertThrows(IllegalArgumentException::class.java) { wallet.request(0u, 0u, 2000L, 2000L, payload) }
            assertThrows(IllegalArgumentException::class.java) { wallet.request(0u, ULong.MAX_VALUE, 2100L, 2000L, payload) }
            val request = wallet.request(0u, 0u, 2100L, 2000L, payload)
            assertEquals(if (algorithm == 1) 32 else 97, wallet.signingMessage(request).size)
            assertThrows(IllegalArgumentException::class.java) { wallet.submission(request, ByteArray(64)) }
        }
    }
}
