package com.meowcoin.wallet.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for wallet private keys using Android EncryptedSharedPreferences.
 * Keys are encrypted at rest using AES-256 backed by the Android Keystore.
 *
 * Supports both legacy single-key wallets and HD (BIP39/BIP44) wallets.
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

    // ═══════════════════════════════════════════
    //  Private Key Storage (per address)
    // ═══════════════════════════════════════════

    fun storePrivateKey(address: String, privateKeyHex: String) {
        prefs.edit().putString("pk_$address", privateKeyHex).apply()
    }

    fun getPrivateKey(address: String): String? {
        return prefs.getString("pk_$address", null)
    }

    fun removePrivateKey(address: String) {
        prefs.edit().remove("pk_$address").apply()
    }

    // ═══════════════════════════════════════════
    //  Primary Address
    // ═══════════════════════════════════════════

    fun storePrimaryAddress(address: String) {
        prefs.edit().putString("primary_address", address).apply()
    }

    fun getPrimaryAddress(): String? {
        return prefs.getString("primary_address", null)
    }

    // ═══════════════════════════════════════════
    //  Seed Phrase (HD Wallet)
    // ═══════════════════════════════════════════

    fun storeSeedPhrase(seedPhrase: String) {
        prefs.edit().putString("seed_phrase", seedPhrase).apply()
    }

    fun getSeedPhrase(): String? {
        return prefs.getString("seed_phrase", null)
    }

    fun hasSeedPhrase(): Boolean {
        return prefs.getString("seed_phrase", null) != null
    }

    // ═══════════════════════════════════════════
    //  HD Wallet Address Index Tracking
    // ═══════════════════════════════════════════

    /**
     * Store the next receiving address index for derivation.
     */
    fun storeNextReceivingIndex(index: Int) {
        prefs.edit().putInt("hd_next_receiving_index", index).apply()
    }

    fun getNextReceivingIndex(): Int {
        return prefs.getInt("hd_next_receiving_index", 0)
    }

    /**
     * Store the next change address index for derivation.
     */
    fun storeNextChangeIndex(index: Int) {
        prefs.edit().putInt("hd_next_change_index", index).apply()
    }

    fun getNextChangeIndex(): Int {
        return prefs.getInt("hd_next_change_index", 0)
    }

    // ═══════════════════════════════════════════
    //  Biometric Preference
    // ═══════════════════════════════════════════

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("biometric_enabled", enabled).apply()
    }

    fun isBiometricEnabled(): Boolean {
        return prefs.getBoolean("biometric_enabled", false)
    }

    // ═══════════════════════════════════════════
    //  Fiat Currency Preference
    // ═══════════════════════════════════════════

    fun setFiatCurrency(currency: String) {
        prefs.edit().putString("fiat_currency", currency).apply()
    }

    fun getFiatCurrency(): String {
        return prefs.getString("fiat_currency", "USD") ?: "USD"
    }

    // ═══════════════════════════════════════════
    //  HD Wallet Flag
    // ═══════════════════════════════════════════

    fun setIsHdWallet(isHd: Boolean) {
        prefs.edit().putBoolean("is_hd_wallet", isHd).apply()
    }

    fun isHdWallet(): Boolean {
        return prefs.getBoolean("is_hd_wallet", false)
    }

    // ═══════════════════════════════════════════
    //  General
    // ═══════════════════════════════════════════

    fun hasWallet(): Boolean {
        return prefs.getString("primary_address", null) != null
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
