package network.tos.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import network.tos.wallet.api.tos.TosTokenOffchainMetadataClient
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit live HTTPS check; does not read or mutate wallet custody or payment state. */
@RunWith(AndroidJUnit4::class)
class TokenMetadataDeviceTest {
    @Test fun productionReaderFetchesImmutableHTTPSMetadata() {
        val url = InstrumentationRegistry.getArguments().getString("tokenMetadataTestUrl")
        assumeNotNull(url)
        require(url in setOf(
            "https://raw.githubusercontent.com/tosnetwork/android/0bc78a9d935af833447fe1d7025d9cc9e5889d4a/apps/wallet/api/src/test/resources/token-https-metadata.json",
            "https://cdn.jsdelivr.net/gh/tosnetwork/android@0bc78a9d935af833447fe1d7025d9cc9e5889d4a/apps/wallet/api/src/test/resources/token-https-metadata.json",
        ))
        val metadata = TosTokenOffchainMetadataClient().read(requireNotNull(url))
        assertEquals("TOS HTTPS QA", metadata.name)
        assertEquals("HTTPSQA", metadata.symbol)
        assertEquals(6, metadata.decimals)
    }
}
