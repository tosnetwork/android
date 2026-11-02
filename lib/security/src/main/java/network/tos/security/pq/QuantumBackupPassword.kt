package network.tos.security.pq

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Exact UTF-8: no trimming or Unicode normalization. Caller clears returned bytes. */
internal object QuantumBackupPassword {
    fun encode(password: CharArray): ByteArray {
        require(password.size in 12..1024) { "Invalid backup password length" }
        var scalars = 0
        var index = 0
        while (index < password.size) {
            val char = password[index++]
            require(char != '\u0000') { "Backup password contains NUL" }
            if (Character.isHighSurrogate(char)) {
                require(index < password.size && Character.isLowSurrogate(password[index])) {
                    "Invalid backup password Unicode"
                }
                index++
            } else {
                require(!Character.isLowSurrogate(char)) { "Invalid backup password Unicode" }
            }
            scalars++
        }
        require(scalars >= 12) { "Backup password must contain at least 12 Unicode scalars" }
        val buffer = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(password))
        return try {
            require(buffer.remaining() <= 1024) { "Backup password exceeds 1024 UTF-8 bytes" }
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } finally {
            if (buffer.hasArray()) buffer.array().fill(0)
        }
    }
}
