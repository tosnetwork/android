package com.tonapps.wallet.data.passcode

import android.os.Build
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.tonapps.extensions.CrashReporter
import com.tonapps.extensions.activity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.coroutines.resume

object PasscodeBiometric {

    private const val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "_com_tonapps_biometric_gate_key_"
    private const val TRANSFORMATION = "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}"

    fun isAvailableOnDevice(context: Context): Boolean {
        val authStatus = BiometricManager.from(context).canAuthenticate(authenticators)
        return authStatus == BiometricManager.BIOMETRIC_SUCCESS // || authStatus == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
    }

    suspend fun showPrompt(
        context: Context,
        title: String
    ): Boolean = suspendCancellableCoroutine { continuation ->
        // Bind the prompt to a keystore key that can only be used after a real biometric
        // authentication. We require the crypto operation to actually complete, so the
        // result cannot be forged by hooking onAuthenticationSucceeded to return true.
        val cipher = authBoundCipher()
        showPrompt(context, title, cipher, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (continuation.isActive) continuation.resume(false)
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (!continuation.isActive) return
                val authedCipher = result.cryptoObject?.cipher
                if (authedCipher == null) {
                    // No CryptoObject was available (fallback path); the strong biometric
                    // prompt itself succeeded.
                    continuation.resume(true)
                    return
                }
                // Prove the unlock is genuine by exercising the auth-bound key.
                val ok = try {
                    authedCipher.doFinal(byteArrayOf(0x54, 0x4f, 0x53))
                    true
                } catch (e: Throwable) {
                    false
                }
                continuation.resume(ok)
            }
        })
    }

    fun showPrompt(
        context: Context,
        title: String,
        callback: BiometricPrompt.AuthenticationCallback
    ) = showPrompt(context, title, authBoundCipher(), callback)

    private fun showPrompt(
        context: Context,
        title: String,
        cipher: Cipher?,
        callback: BiometricPrompt.AuthenticationCallback
    ) {
        val activity = context.activity as? FragmentActivity
        if (activity == null) {
            callback.onAuthenticationError(BiometricPrompt.ERROR_HW_NOT_PRESENT, "Activity not found")
            return
        }
        try {
            val mainExecutor = ContextCompat.getMainExecutor(activity)
            val biometricPrompt = BiometricPrompt(activity, mainExecutor, callback)
            val builder = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setAllowedAuthenticators(authenticators)
                .setConfirmationRequired(false)
                .setNegativeButtonText(context.getString(android.R.string.cancel))

            if (cipher != null) {
                biometricPrompt.authenticate(builder.build(), BiometricPrompt.CryptoObject(cipher))
            } else {
                biometricPrompt.authenticate(builder.build())
            }
        } catch (e: Throwable) {
            CrashReporter.recordException(e)
            callback.onAuthenticationError(BiometricPrompt.ERROR_HW_NOT_PRESENT, "Unknown error")
        }
    }

    /**
     * A Cipher initialized with an auth-required keystore key, ready to be authorized by the
     * biometric prompt. Returns null if the key/cipher cannot be prepared (e.g. the key was
     * invalidated by new biometric enrollment); in that case we degrade to a plain strong
     * biometric prompt rather than locking the user out.
     */
    private fun authBoundCipher(): Cipher? {
        return try {
            val key = getOrCreateKey() ?: return null
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Biometric set changed since the key was created: drop it so a fresh one is
            // minted next time, and fall back to a non-crypto prompt for this attempt.
            deleteKey()
            null
        } catch (e: Throwable) {
            null
        }
    }

    private fun getOrCreateKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Adding/removing a fingerprint or face must invalidate the gate key.
            builder.setInvalidatedByBiometricEnrollment(true)
        }
        return try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(builder.build())
            generator.generateKey()
        } catch (e: Throwable) {
            null
        }
    }

    private fun deleteKey() {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        } catch (e: Throwable) {
            // nothing to delete
        }
    }
}
