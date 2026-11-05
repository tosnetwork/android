package network.tos.wallet.data.account.privacy

import network.tos.wallet.data.account.pq.QuantumNetworkProfile
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PrivacyPoolSelectionTest {
    private val profile = QuantumNetworkProfile(3, "11".repeat(32), "22".repeat(32))
    private fun code() = Base64.getDecoder().decode("te6ccgEBAQEAAgAAAA==")
    private fun selection(code: ByteArray = code()) = PrivacyPoolSelection(profile, "0:" + "33".repeat(32), code,
        "44".repeat(32), "55".repeat(32), "66".repeat(32))
    @Test fun explicitPinsRoundTripWithoutFollowingMutableInputOrAnotherNetwork() {
        val source = code()
        val selected = selection(source)
        val expected = selected.expectedCode().hash().toByteArray()
        source.fill(0)
        val restored = PrivacyPoolSelection.parse(selected.json())
        assertArrayEquals(expected, restored.expectedCode().hash().toByteArray())
        assertEquals(selected.pool, restored.pool)
        restored.requireProfile(profile)
        for (wrong in listOf(QuantumNetworkProfile(4, profile.rootHash, profile.fileHash),
            QuantumNetworkProfile(3, "77".repeat(32), profile.fileHash), QuantumNetworkProfile(3, profile.rootHash, "88".repeat(32)))) {
            assertThrows(IllegalArgumentException::class.java) { restored.requireProfile(wrong) }
        }
    }
    @Test fun absentOrUnsupportedPinsCannotBecomeAnImplicitSelection() {
        assertThrows(IllegalArgumentException::class.java) { PrivacyPoolSelection.parse(selection().json().also { it.remove("manifest_hash") }) }
        assertThrows(IllegalArgumentException::class.java) { PrivacyPoolSelection.parse(selection().json().put("version", 2)) }
        assertThrows(IllegalArgumentException::class.java) { PrivacyPoolSelection(profile, "0:" + "33".repeat(32), code(), "00".repeat(32), "55".repeat(32), "66".repeat(32)) }
        assertThrows(IllegalArgumentException::class.java) { selection(ByteArray(131073)) }
    }
}
