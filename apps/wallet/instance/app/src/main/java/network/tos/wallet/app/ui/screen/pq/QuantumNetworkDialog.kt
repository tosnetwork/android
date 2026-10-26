package network.tos.wallet.app.ui.screen.pq

import android.content.Context
import android.text.InputType
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import network.tos.wallet.app.R
import network.tos.wallet.data.account.pq.QuantumNetworkProfile

/** Provisioning is a user action; no value is fetched from the selected RPC. */
object QuantumNetworkDialog {
    fun nodeEditor(context: Context, endpoint: View): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(endpoint)
        addView(Button(context).apply {
            setText(R.string.quantum_trusted_network)
            setOnClickListener { show(context) }
        })
    }

    fun show(context: Context) {
        val current = runCatching { QuantumNetworkProfile.selected(context) }.getOrNull()
        fun field(hintId: Int, value: String?, signedNumber: Boolean = false) = AppCompatEditText(context).apply {
            hint = context.getString(hintId)
            contentDescription = hint
            setText(value.orEmpty())
            inputType = if (signedNumber) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
        }
        val id = field(R.string.quantum_global_id, current?.globalId?.toString(), true)
        val root = field(R.string.quantum_root_hash, current?.rootHash)
        val file = field(R.string.quantum_file_hash, current?.fileHash)
        val fields = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(id); addView(root); addView(file)
        }
        val dialog = AlertDialog.Builder(context).setTitle(R.string.quantum_trusted_network)
            .setMessage(R.string.quantum_trusted_network_hint)
            .setView(ScrollView(context).apply { addView(fields) })
            .setPositiveButton(R.string.rpc_node_save, null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val globalId = requireNotNull(id.text.toString().trim().toIntOrNull())
                    QuantumNetworkProfile(globalId, root.text.toString(), file.text.toString()).save(context)
                    dialog.dismiss()
                } catch (_: Exception) { id.error = context.getString(R.string.quantum_network_invalid) }
            }
        }
        dialog.show()
    }
}
