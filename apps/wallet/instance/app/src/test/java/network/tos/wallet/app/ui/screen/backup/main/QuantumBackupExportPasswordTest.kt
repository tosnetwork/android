package network.tos.wallet.app.ui.screen.backup.main

import org.junit.Assert.*
import org.junit.Test

class QuantumBackupExportPasswordTest {
    @Test fun pinAndShortUnicodeAreNotFilePasswords() {
        for (value in listOf("1234", "a".repeat(11), "😀".repeat(6))) assertFalse(QuantumBackupExportPassword.matches(value, value))
        assertTrue(QuantumBackupExportPassword.matches("😀".repeat(12), "😀".repeat(12)))
    }
    @Test fun exactInputIsNeitherTrimmedNorNormalized() {
        assertFalse(QuantumBackupExportPassword.matches("a".repeat(12) + " ", "a".repeat(12)))
        assertTrue(QuantumBackupExportPassword.matches(" a".repeat(12), " a".repeat(12)))
        assertFalse(QuantumBackupExportPassword.matches("é".repeat(12), "e\u0301".repeat(12)))
    }
    @Test fun strictUtf8SizeAndIllegalSequences() {
        for (value in listOf("a".repeat(12) + '\u0000', "a".repeat(12) + '\uD800', "😀".repeat(400), "a".repeat(1025))) {
            assertFalse(QuantumBackupExportPassword.matches(value, value))
        }
        assertTrue(QuantumBackupExportPassword.matches("é".repeat(512), "é".repeat(512)))
    }
}
