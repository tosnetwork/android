package network.tos.blockchain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.ton.mnemonic.Mnemonic
import org.ton.crypto.hex
import network.tos.blockchain.ton.extensions.toAccountId
import network.tos.blockchain.ton.contract.TosWalletV5R1Contract
import network.tos.blockchain.ton.contract.WalletV5R1Contract

class TosV1MnemonicTest {
    private val legacy = "mansion chef affair ancient announce police snap machine vanish liberty peace tennis effort recall law limit mosquito tornado toward advance vibrant bachelor auction voice"

    private val fixture = "enhance depend evolve rotate creek total enable settle mammal margin round cube truck quote hold correct provide voyage north model sure off strategy pulse"

    @Test fun `TOS salts match independent SDK key and distinguish legacy recovery`() {
        val words = fixture.split(" ")
        assertEquals(TosV1Mnemonic.Profile.TOS, TosV1Mnemonic.recoveryProfile(words))
        assertFalse(TosV1Mnemonic.isLegacyValid(words))
        val key = TosV1Mnemonic.privateKey(words).publicKey()
        assertEquals("a71563f5709a827fad271813afc670403589781f4ac7259c7b0282b6686b2589", hex(key.key.toByteArray()))
        assertEquals("0:88269a9a5ecb30262608e6fb86f8ae3d534e8c77d5a9ae0140ce5217196a0cfe", TosWalletV5R1Contract(key, 3).address.toAccountId())
        assertEquals(TosV1Mnemonic.Profile.LEGACY, TosV1Mnemonic.recoveryProfile(legacy.split(" ")))
        assertEquals("54298c04ae729978cb7c988270e86b20e00a752562411b3511634ec59beebf26", hex(TosV1Mnemonic.recoveryPrivateKey(legacy.split(" ")).publicKey().key.toByteArray()))
        assertFalse(TosV1Mnemonic.isValid(legacy.split(" ")))
        assertThrows(IllegalArgumentException::class.java) { TosV1Mnemonic.privateKey(legacy.split(" ")) }
    }

    @Test fun `ambiguous phrase needs explicit profile and each choice preserves independent key`() {
        val words = "coffee glad rail dry pink piano allow announce system shrug term return vague crater silly state quick glow wrestle wink tail derive device recall".split(" ")
        assertTrue(TosV1Mnemonic.isValid(words))
        assertTrue(TosV1Mnemonic.isLegacyValid(words))
        assertEquals(null, TosV1Mnemonic.recoveryProfile(words))
        assertThrows(IllegalArgumentException::class.java) { TosV1Mnemonic.recoveryPrivateKey(words) }
        val native = TosV1Mnemonic.recoveryPrivateKey(words, TosV1Mnemonic.Profile.TOS).publicKey()
        val legacyKey = TosV1Mnemonic.recoveryPrivateKey(words, TosV1Mnemonic.Profile.LEGACY).publicKey()
        assertEquals("7c2c64e1dca71c1add3777ebaeb611ad56229995d435ad7bf6ba29909d816ceb", hex(native.key.toByteArray()))
        assertEquals("cfe05748559fea1f676ba2e6ef9d2e2bf5767a59fe66584c0fc5fea1cb772166", hex(legacyKey.key.toByteArray()))
        assertEquals("0:f1bf21eacb1b2725e4792d4eeae23dfd5402e8b060f67d2a72719aec49e5d727", TosWalletV5R1Contract(native, 3).address.toAccountId())
        assertEquals("0:b3b84ebb0b272a2ef7b9977fb625266651802eabb3a94237275feeec0b9c3842", WalletV5R1Contract(legacyKey, WalletV5R1Contract.W5Context.Client()).address.toAccountId())
    }

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

    @Test
    fun `generated recovery phrases are unique valid twenty four word phrases`() {
        val dictionary = Mnemonic.mnemonicWords().toSet()
        val generated = mutableListOf<List<String>>()
        runBlocking {
            repeat(3) { generated += TosV1Mnemonic.generate() }
        }

        assertEquals(3, generated.distinct().size)
        generated.forEach { words ->
            assertEquals(TosV1Mnemonic.WORD_COUNT, words.size)
            assertTrue(words.all(dictionary::contains))
            assertTrue(TosV1Mnemonic.isValid(words))
        }
    }
}
