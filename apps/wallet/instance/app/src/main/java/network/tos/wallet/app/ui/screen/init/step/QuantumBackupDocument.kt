package network.tos.wallet.app.ui.screen.init.step

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Untrusted provider input: bounded allocation and one active reader across recreated screens. */
internal object QuantumBackupDocument {
    private val reading = AtomicBoolean(false)

    fun read(open: () -> InputStream): ByteArray {
        check(reading.compareAndSet(false, true)) { "Another document read is active" }
        try {
            return open().use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 262201) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val count = stream.read(buffer, 0, minOf(buffer.size, 262202 - output.size()))
                    if (count < 0) break
                    check(count > 0)
                    output.write(buffer, 0, count)
                }
                require(output.size() in 58..262201)
                output.toByteArray()
            }
        } finally { reading.set(false) }
    }
}
