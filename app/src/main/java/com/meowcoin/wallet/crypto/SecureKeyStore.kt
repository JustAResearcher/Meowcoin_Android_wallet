package com.meowcoin.wallet.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for wallet private keys using Android EncryptedSharedPreferences.
 * Keys are encrypted at rest using AES-256 backed by the Android Keystore.
 */
class SecureKeyStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "meowcoin_secure_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Store a private key for a given address.
     */
    fun storePrivateKey(address: String, privateKeyHex: String) {
        prefs.edit().putString("pk_$address", privateKeyHex).apply()
    }

    /**
     * Retrieve a private key for a given address.
     */
    fun getPrivateKey(address: String): String? {
        return prefs.getString("pk_$address", null)
    }

    /**
     * Remove a private key for a given address.
     */
    fun removePrivateKey(address: String) {
        prefs.edit().remove("pk_$address").apply()
    }

    /**
     * Store the primary wallet address.
     */
    fun storePrimaryAddress(address: String) {
        prefs.edit().putString("primary_address", address).apply()
    }

    /**
     * Get the primary wallet address.
     */
    fun getPrimaryAddress(): String? {
        return prefs.getString("primary_address", null)
    }

    /**
     * Store a mnemonic seed phrase (encrypted).
     */
    fun storeSeedPhrase(seedPhrase: String) {
        prefs.edit().putString("seed_phrase", seedPhrase).apply()
    }

    /**
     * Get the stored seed phrase.
     */
    fun getSeedPhrase(): String? {
        return prefs.getString("seed_phrase", null)
    }

    /**
     * Check if a wallet exists.
     */
    fun hasWallet(): Boolean {
        return prefs.getString("primary_address", null) != null
    }

    /**
     * Clear all stored keys (wallet reset).
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
