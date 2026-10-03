package network.tos.signer.vault

import android.content.Context
import network.tos.blockchain.TosV1Mnemonic
import network.tos.blockchain.MnemonicHelper
import org.json.JSONObject
import network.tos.signer.extensions.securePrefs
import org.ton.api.pk.PrivateKeyEd25519
import org.ton.mnemonic.Mnemonic
import network.tos.security.clear
import network.tos.security.safeDestroy
import network.tos.security.tryCallGC
import network.tos.security.vault.Vault
import network.tos.security.vault.getString
import network.tos.security.vault.putString
import javax.crypto.SecretKey

class SignerVault(
    context: Context,
    name: String,
): Vault(context.securePrefs(name)) {
    private val profiles = context.securePrefs(name)

    constructor(context: Context): this(context, "signer")

    suspend fun setMnemonic(secret: SecretKey, id: Long, mnemonic: List<String>, nativeTos: Boolean = false) {
        val value = if (nativeTos) JSONObject().put("derivation", "TOS")
            .put("words", mnemonic.joinToString(",")).toString() else mnemonic.joinToString(",")
        putString(secret, id, value)
        profiles.edit().putBoolean("native_tos_$id", nativeTos).apply()
        secret.safeDestroy()
    }

    /** Published legacy CSV vaults have no marker and remain legacy. */
    fun isNativeTosKey(id: Long): Boolean = profiles.getBoolean("native_tos_$id", false)

    suspend fun getMnemonic(secret: SecretKey, id: Long): List<String> {
        val stored = getString(secret, id)
        val list = if (stored.startsWith("{")) JSONObject(stored).getString("words").split(",") else stored.split(",")
        secret.safeDestroy()
        return list
    }

    suspend fun getPrivateKey(secret: SecretKey, id: Long): PrivateKeyEd25519 {
        val stored = getString(secret, id)
        secret.safeDestroy()
        val json = if (stored.startsWith("{")) JSONObject(stored) else null
        require((json != null) == isNativeTosKey(id)) { "Signer derivation metadata mismatch" }
        val mnemonic = (json?.getString("words") ?: stored).split(",")
        val privateKey = if (json != null) {
            require(json.getString("derivation") == "TOS") { "Unknown recovery derivation" }
            TosV1Mnemonic.privateKey(mnemonic)
        } else MnemonicHelper.privateKey(mnemonic)
        tryCallGC()
        return privateKey
    }
}
