package network.tos.blockchain

import org.ton.mnemonic.Mnemonic

/** Native TOS V1 accepts only the chain's canonical 24-word mnemonic format. */
object TosV1Mnemonic {
    const val WORD_COUNT = 24

    fun normalize(words: List<String>): List<String> = words.map {
        it.trim().lowercase()
    }

    fun normalize(value: String): List<String> = value
        .trim()
        .split(Regex("[\\s,;]+"))
        .filter(String::isNotBlank)
        .map(String::lowercase)

    fun isValid(words: List<String>): Boolean {
        val normalized = normalize(words)
        return normalized.size == WORD_COUNT && Mnemonic.isValid(normalized)
    }
}
