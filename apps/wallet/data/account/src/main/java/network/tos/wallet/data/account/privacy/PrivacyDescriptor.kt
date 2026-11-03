package network.tos.wallet.data.account.privacy

import java.math.BigInteger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** SHD1 wire format from shielded-pool-wallet::descriptor at d8e8f46c8.
 * Format/domain validation does not authenticate a payee or prove key ownership.
 * Copy all byte arrays at the boundary so a reviewed recipient cannot be mutated.
 */
@OptIn(ExperimentalEncodingApi::class)
class PrivacyDescriptor private constructor(private val encoded: ByteArray) {
    val bytes: ByteArray get() = encoded.copyOf()
    val base64: String get() = Base64.Default.encode(encoded)
    val executionDomain: ByteArray get() = encoded.copyOfRange(5, 37)
    val noteKeyIndex: BigInteger get() = BigInteger(1, encoded.copyOfRange(37, 45))

    companion object {
        const val BYTE_COUNT = 2573
        private val header = byteArrayOf(0x53, 0x48, 0x44, 0x31, 1)
        private val modulus = BigInteger("73eda753299d7d483339d80809a1d80553bda402fffe5bfeffffffff00000001", 16)

        fun parse(base64: String, expectedDomain: ByteArray): PrivacyDescriptor {
            val text = base64.trim()
            require(text.length == 3432) { "Invalid privacy descriptor encoding length" }
            val bytes = Base64.Default.decode(text)
            require(Base64.Default.encode(bytes) == text) { "Noncanonical privacy descriptor base64" }
            return parse(bytes, expectedDomain)
        }

        fun parse(bytes: ByteArray, expectedDomain: ByteArray): PrivacyDescriptor {
            val snapshot = bytes.copyOf()
            require(snapshot.size == BYTE_COUNT) { "Invalid privacy descriptor length" }
            require(snapshot.copyOfRange(0, 5).contentEquals(header)) { "Invalid privacy descriptor version" }
            require(BigInteger(1, snapshot.copyOfRange(5, 37)) < modulus &&
                BigInteger(1, snapshot.copyOfRange(45, 77)) < modulus) { "Noncanonical privacy descriptor field" }
            require(expectedDomain.size == 32 &&
                snapshot.copyOfRange(5, 37).contentEquals(expectedDomain)) { "Privacy descriptor belongs to another pool" }
            return PrivacyDescriptor(snapshot)
        }
    }
}
