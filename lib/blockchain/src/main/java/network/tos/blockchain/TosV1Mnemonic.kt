package network.tos.blockchain

import network.tos.blockchain.ton.extensions.hmac_sha512
import network.tos.blockchain.ton.extensions.pbkdf2_sha512
import org.ton.api.pk.PrivateKeyEd25519
import org.ton.mnemonic.Mnemonic
import java.security.SecureRandom
import kotlin.random.Random

/** TOS and inherited TON phrases use distinct validation and derivation salts. */
object TosV1Mnemonic {
    const val WORD_COUNT = 24
    enum class Profile { TOS, LEGACY }
    private val dictionary by lazy { Mnemonic.mnemonicWords() }
    private val wordsSet by lazy { dictionary.toSet() }

    fun normalize(words: List<String>): List<String> = words.map { it.trim().lowercase() }
    fun normalize(value: String): List<String> = value.trim().split(Regex("[\\s,;]+"))
        .filter(String::isNotBlank).map(String::lowercase)

    fun isValid(words: List<String>): Boolean {
        val normalized = normalize(words)
        if (normalized.size != WORD_COUNT || !normalized.all(wordsSet::contains)) return false
        val entropy = hmac_sha512(normalized.joinToString(" "), "")
        return try { pbkdf2_sha512(entropy, "TOS seed version".toByteArray(), 390, 512)[0] == 0.toByte() }
        finally { entropy.fill(0) }
    }
    fun isLegacyValid(words: List<String>): Boolean {
        val normalized = normalize(words)
        return normalized.size == WORD_COUNT && normalized.all(wordsSet::contains) && Mnemonic.isValid(normalized)
    }
    /** Ambiguous phrases need an explicit profile, so automatic import refuses them. */
    fun recoveryProfile(words: List<String>): Profile? {
        val native = isValid(words)
        val legacy = isLegacyValid(words)
        return when {
            native && !legacy -> Profile.TOS
            legacy && !native -> Profile.LEGACY
            else -> null
        }
    }
    fun privateKey(words: List<String>): PrivateKeyEd25519 {
        require(isValid(words)) { "Invalid native TOS phrase" }
        val entropy = hmac_sha512(normalize(words).joinToString(" "), "")
        val seed = try { pbkdf2_sha512(entropy, "TOS default seed".toByteArray(), 100000, 512) }
            finally { entropy.fill(0) }
        return try { PrivateKeyEd25519(seed.copyOfRange(0, 32)) } finally { seed.fill(0) }
    }
    fun recoveryPrivateKey(words: List<String>, profile: Profile? = null): PrivateKeyEd25519 = when (profile ?: recoveryProfile(words)) {
        Profile.TOS -> privateKey(words)
        Profile.LEGACY -> {
            require(isLegacyValid(words)) { "Invalid legacy phrase" }
            MnemonicHelper.privateKey(normalize(words))
        }
        null -> throw IllegalArgumentException("Invalid or ambiguous recovery phrase")
    }
    fun generate(random: Random? = null): List<String> {
        val secure = SecureRandom()
        while (true) {
            val candidate = List(WORD_COUNT) { dictionary[random?.nextInt(dictionary.size) ?: secure.nextInt(dictionary.size)] }
            if (recoveryProfile(candidate) == Profile.TOS) return candidate
        }
    }
}
