package network.tos.wallet.app.ui.screen.backup.main

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

internal object QuantumBackupExportPassword {
    fun matches(first: CharSequence?, second: CharSequence?): Boolean {
        if (first == null || second == null || first.length != second.length || first.length > 1024 ||
            Character.codePointCount(first, 0, first.length) < 12 || first.any { it == '\u0000' }) return false
        var difference = 0
        for (index in first.indices) difference = difference or (first[index].code xor second[index].code)
        if (difference != 0) return false
        return try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(first))
            try { encoded.remaining() <= 1024 }
            finally { if (encoded.hasArray()) encoded.array().fill(0) }
        } catch (_: java.nio.charset.CharacterCodingException) { false }
    }
}
