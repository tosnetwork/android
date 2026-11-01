package network.tos.wallet.app.ui.screen.init.step

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class QuantumBackupDocumentTest {
    @Test fun exactBoundsAndClosure() {
        for (size in listOf(58, 262201)) {
            var closed = false
            val bytes = ByteArray(size) { it.toByte() }
            assertArrayEquals(bytes, QuantumBackupDocument.read {
                object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } }
            })
            assertTrue(closed)
        }
    }

    @Test fun unendingProviderIsBoundedAndClosed() {
        var consumed = 0; var closed = false
        assertThrows(IllegalArgumentException::class.java) {
            QuantumBackupDocument.read {
                object : InputStream() {
                    override fun read(): Int = 1
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        consumed += length; return length
                    }
                    override fun close() { closed = true }
                }
            }
        }
        assertEquals(262202, consumed); assertTrue(closed)
        assertEquals(58, QuantumBackupDocument.read { ByteArrayInputStream(ByteArray(58)) }.size)
    }

    @Test fun shortOrStalledStreamRejectedAndClosed() {
        for (stalled in listOf(false, true)) {
            var closed = false
            assertThrows(Exception::class.java) {
                QuantumBackupDocument.read {
                    object : InputStream() {
                        override fun read(): Int = if (stalled) 0 else -1
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = if (stalled) 0 else -1
                        override fun close() { closed = true }
                    }
                }
            }
            assertTrue(closed)
        }
    }

    @Test fun overlappingReadRejectedBeforeOpeningProvider() {
        var nestedOpened = false
        QuantumBackupDocument.read {
            assertThrows(IllegalStateException::class.java) {
                QuantumBackupDocument.read { nestedOpened = true; ByteArrayInputStream(ByteArray(58)) }
            }
            ByteArrayInputStream(ByteArray(58))
        }
        assertFalse(nestedOpened)
    }
}
