package network.tos.wallet.app.ui.screen.pq

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** UI-owned secret input. Callers must not retain or mutate supplied buffers after transfer. */
object V5R2RecoveryInput {
    fun rawMasterAndWipe(chars: CharArray): ByteArray {
        try {
            require(chars.size == 64 && chars.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
            // Validate the complete input before constructing any secret output.
            return ByteArray(32) { i -> ((chars[i * 2].digitToInt(16) shl 4) or chars[i * 2 + 1].digitToInt(16)).toByte() }
        } finally { chars.fill('\u0000') }
    }
    fun launchConsumed(scope: CoroutineScope, master: ByteArray, action: suspend (ByteArray) -> Unit): Job {
        val job = scope.launch { try { action(master) } finally { master.fill(0) } }
        // Completion registration also fires for a scope cancelled before the body starts.
        job.invokeOnCompletion { master.fill(0) }
        return job
    }
}
