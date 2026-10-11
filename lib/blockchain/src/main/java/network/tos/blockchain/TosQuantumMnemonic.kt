package network.tos.blockchain

import network.tos.blockchain.ton.extensions.hmac_sha512
import network.tos.blockchain.ton.extensions.pbkdf2_sha512
import org.ton.mnemonic.Mnemonic
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Native TOS master derivation for R2. This never constructs a classical signing key.
 * A twelve-word restore retains its original entropy; creation uses the existing 24-word generator. */
object TosQuantumMnemonic {
    private val dictionary by lazy { Mnemonic.mnemonicWords().toSet() }
    /** Password is an exact input and is consumed on success and failure. Caller must wipe the returned master. */
    fun masterAndWipePassword(words: List<String>, password: CharArray): ByteArray {
        var phrase = ByteArray(0)
        var pass = ByteArray(0)
        var entropy = ByteArray(0)
        var validation = ByteArray(0)
        var derived = ByteArray(0)
        try {
            val normalized = words.map { it.trim().lowercase(Locale.ROOT) }
            require(normalized.size == 12 || normalized.size == 24)
            require(normalized.all(dictionary::contains)) { "Invalid native mnemonic word" }
            phrase = normalized.joinToString(" ").toByteArray(Charsets.UTF_8)
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(password))
            try { pass = ByteArray(encoded.remaining()); encoded.get(pass) }
            finally { if (encoded.hasArray()) encoded.array().fill(0) }
            entropy = hmac_sha512(phrase, pass)
            validation = pbkdf2_sha512(entropy, "TOS seed version".toByteArray(Charsets.US_ASCII), 390, 512)
            require(validation.size == 64 && validation[0] == 0.toByte()) { "Invalid native basic-seed check" }
            derived = pbkdf2_sha512(entropy, "TOS default seed".toByteArray(Charsets.US_ASCII), 100000, 512)
            check(derived.size == 64)
            return derived.copyOfRange(0, 32)
        } finally {
            password.fill('\u0000'); phrase.fill(0); pass.fill(0); entropy.fill(0); validation.fill(0); derived.fill(0)
        }
    }
}
