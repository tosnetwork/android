package network.tos.blockchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TosV1MnemonicTest {
    private val fixture = "mansion chef affair ancient announce police snap machine vanish liberty peace tennis effort recall law limit mosquito tornado toward advance vibrant bachelor auction voice"

    @Test
    fun `canonical fixture is valid and normalizes whitespace and case`() {
        val decorated = "  " + fixture.uppercase().replace(" ", "  \n") + "  "
        val normalized = TosV1Mnemonic.normalize(decorated)

        assertEquals(24, normalized.size)
        assertEquals(fixture.split(" "), normalized)
        assertTrue(TosV1Mnemonic.isValid(normalized))
    }

    @Test
    fun `wrong count unknown word and invalid checksum are rejected`() {
        val words = fixture.split(" ")

        assertFalse(TosV1Mnemonic.isValid(words.dropLast(1)))
        assertFalse(TosV1Mnemonic.isValid(words.toMutableList().apply { this[0] = "notaword" }))
        assertFalse(TosV1Mnemonic.isValid(List(24) { "abandon" }))
        assertFalse(TosV1Mnemonic.isValid(words.take(12)))
    }
}
