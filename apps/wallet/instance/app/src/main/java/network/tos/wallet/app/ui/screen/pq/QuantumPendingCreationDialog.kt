package network.tos.wallet.app.ui.screen.pq

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.tos.wallet.app.R
import network.tos.wallet.data.account.AccountRepository

/** Public pending identities only; the creation screen authenticates before accessing custody. */
object QuantumPendingCreationDialog {
    fun open(context: Context, owner: LifecycleOwner, repository: AccountRepository, proceed: (String?) -> Unit) {
        owner.lifecycleScope.launch {
            try {
                val pending = repository.pendingQuantumCreations()
                if (pending.isEmpty()) proceed(null)
                else AlertDialog.Builder(context).setTitle(R.string.quantum_resume_creation)
                    .setItems(pending.map { it.identity.address.toString() }.toTypedArray()) { _, index ->
                        proceed(pending[index].id.toString())
                    }.setNeutralButton(R.string.quantum_create_another) { _, _ -> proceed(null) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                AlertDialog.Builder(context).setTitle(R.string.quantum_creation_failed)
                    .setMessage(R.string.quantum_creation_failed_hint).setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }
}
