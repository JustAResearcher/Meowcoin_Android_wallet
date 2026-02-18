package com.meowcoin.wallet.crypto

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Biometric authentication helper for securing wallet access.
 * Uses AndroidX Biometric API (fingerprint, face unlock, etc.).
 */
class BiometricHelper(private val context: Context) {

    companion object {
        private const val TAG = "BiometricHelper"

        /**
         * Static convenience to check biometric availability without creating an instance.
         */
        fun isBiometricAvailable(context: Context): Boolean {
            return BiometricHelper(context).isBiometricAvailable()
        }
    }

    /**
     * Check if biometric authentication is available on this device.
     */
    fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                Log.d(TAG, "No biometric hardware")
                false
            }
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                Log.d(TAG, "Biometric hardware unavailable")
                false
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                Log.d(TAG, "No biometrics enrolled")
                false
            }
            else -> false
        }
    }

    /**
     * Show biometric prompt and authenticate the user.
     *
     * @param activity The FragmentActivity to show the prompt in
     * @param title Prompt title
     * @param subtitle Prompt subtitle
     * @param negativeButtonText Text for the cancel/negative button
     * @param onSuccess Called on successful authentication
     * @param onError Called on authentication error
     * @param onFailed Called on authentication failure (e.g., wrong fingerprint)
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = "Authenticate",
        subtitle: String = "Use biometrics to access your wallet",
        negativeButtonText: String = "Use PIN",
        onSuccess: () -> Unit,
        onError: (errorCode: Int, errorMessage: String) -> Unit = { _, _ -> },
        onFailed: () -> Unit = {}
    ) {
        val executor = ContextCompat.getMainExecutor(context)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                Log.d(TAG, "Authentication succeeded")
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Log.e(TAG, "Authentication error: $errString ($errorCode)")
                onError(errorCode, errString.toString())
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Log.d(TAG, "Authentication failed")
                onFailed()
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()

        val biometricPrompt = BiometricPrompt(activity, executor, callback)
        biometricPrompt.authenticate(promptInfo)
    }
}
