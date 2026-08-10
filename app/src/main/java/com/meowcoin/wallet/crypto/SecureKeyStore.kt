package com.meowcoin.wallet.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.Locale

/**
 * Secure storage for wallet private keys using Android EncryptedSharedPreferences.
 * Keys are encrypted at rest using AES-256 backed by the Android Keystore.
 *
 * Supports both legacy single-key wallets and HD (BIP39/BIP44) wallets.
 */
class SecureKeyStore(context: Context) {

    companion object {
        const val DEFAULT_COIN_ID = "mewc"

        private const val SELECTED_COIN_ID_KEY = "selected_coin_id"
        private const val MNEMONIC_BACKUP_REQUIRED_KEY = "mnemonic_backup_required"
        private const val SEED_DERIVATION_VERSION_KEY = "seed_derivation_version"
        private const val BIP84_RECEIVING_INDEX_SUFFIX = "hd_bip84_next_receiving_index"
        private const val BIP84_CHANGE_INDEX_SUFFIX = "hd_bip84_next_change_index"
        private const val BIP84_DISCOVERY_PENDING_SUFFIX = "hd_bip84_discovery_pending"
    }

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

    private fun normalizeCoinId(coinId: String): String {
        val normalized = coinId.trim().lowercase(Locale.ROOT)
        require(normalized.isNotEmpty()) { "coinId must not be blank" }
        return normalized
    }

    private fun coinKey(coinId: String, suffix: String): String {
        return "coin_${normalizeCoinId(coinId)}_$suffix"
    }

    private fun getCoinString(coinId: String, suffix: String, legacyKey: String): String? {
        val normalizedCoinId = normalizeCoinId(coinId)
        val key = coinKey(normalizedCoinId, suffix)
        prefs.getString(key, null)?.let { return it }

        if (normalizedCoinId != DEFAULT_COIN_ID) return null
        return prefs.getString(legacyKey, null)?.also { legacyValue ->
            prefs.edit().putString(key, legacyValue).apply()
        }
    }

    private fun getCoinInt(
        coinId: String,
        suffix: String,
        legacyKey: String,
        defaultValue: Int
    ): Int {
        val normalizedCoinId = normalizeCoinId(coinId)
        val key = coinKey(normalizedCoinId, suffix)
        if (prefs.contains(key)) return prefs.getInt(key, defaultValue)
        if (normalizedCoinId != DEFAULT_COIN_ID || !prefs.contains(legacyKey)) return defaultValue

        return prefs.getInt(legacyKey, defaultValue).also { legacyValue ->
            prefs.edit().putInt(key, legacyValue).apply()
        }
    }

    private fun getCoinBoolean(
        coinId: String,
        suffix: String,
        legacyKey: String,
        defaultValue: Boolean
    ): Boolean {
        val normalizedCoinId = normalizeCoinId(coinId)
        val key = coinKey(normalizedCoinId, suffix)
        if (prefs.contains(key)) return prefs.getBoolean(key, defaultValue)
        if (normalizedCoinId != DEFAULT_COIN_ID || !prefs.contains(legacyKey)) return defaultValue

        return prefs.getBoolean(legacyKey, defaultValue).also { legacyValue ->
            prefs.edit().putBoolean(key, legacyValue).apply()
        }
    }

    // ═══════════════════════════════════════════
    //  Private Key Storage (per address)
    // ═══════════════════════════════════════════

    fun storePrivateKey(address: String, privateKeyHex: String) {
        storePrivateKey(DEFAULT_COIN_ID, address, privateKeyHex)
    }

    fun storePrivateKey(coinId: String, address: String, privateKeyHex: String) {
        prefs.edit().putString(coinKey(coinId, "pk_$address"), privateKeyHex).apply()
    }

    fun getPrivateKey(address: String): String? {
        return getPrivateKey(DEFAULT_COIN_ID, address)
    }

    fun getPrivateKey(coinId: String, address: String): String? {
        return getCoinString(coinId, "pk_$address", "pk_$address")
    }

    fun removePrivateKey(address: String) {
        removePrivateKey(DEFAULT_COIN_ID, address)
    }

    fun removePrivateKey(coinId: String, address: String) {
        val normalizedCoinId = normalizeCoinId(coinId)
        val editor = prefs.edit().remove(coinKey(normalizedCoinId, "pk_$address"))
        if (normalizedCoinId == DEFAULT_COIN_ID) editor.remove("pk_$address")
        editor.apply()
    }

    fun storeKeyCompression(
        coinId: String,
        address: String,
        isCompressed: Boolean
    ) {
        prefs.edit()
            .putBoolean(coinKey(coinId, "compressed_$address"), isCompressed)
            .apply()
    }

    /** Existing wallet records predate this flag and always used compressed public keys. */
    fun isKeyCompressed(coinId: String, address: String): Boolean {
        val key = coinKey(coinId, "compressed_$address")
        return if (prefs.contains(key)) prefs.getBoolean(key, true) else true
    }

    fun hasKeyCompressionMetadata(coinId: String, address: String): Boolean {
        return prefs.contains(coinKey(coinId, "compressed_$address"))
    }

    // ═══════════════════════════════════════════
    //  Primary Address
    // ═══════════════════════════════════════════

    fun storePrimaryAddress(address: String) {
        storePrimaryAddress(DEFAULT_COIN_ID, address)
    }

    fun storePrimaryAddress(coinId: String, address: String) {
        prefs.edit().putString(coinKey(coinId, "primary_address"), address).apply()
    }

    fun getPrimaryAddress(): String? {
        return getPrimaryAddress(DEFAULT_COIN_ID)
    }

    fun getPrimaryAddress(coinId: String): String? {
        return getCoinString(coinId, "primary_address", "primary_address")
    }

    // ═══════════════════════════════════════════
    //  Seed Phrase (HD Wallet)
    // ═══════════════════════════════════════════

    fun storeSeedPhrase(seedPhrase: String) {
        storeSeedPhrase(seedPhrase, backupRequired = false)
    }

    fun storeSeedPhrase(
        seedPhrase: String,
        backupRequired: Boolean,
        derivationVersion: Int = Bip39.CANONICAL_DERIVATION_VERSION
    ) {
        require(
            derivationVersion == Bip39.LEGACY_DERIVATION_VERSION ||
                derivationVersion == Bip39.CANONICAL_DERIVATION_VERSION
        ) { "Unsupported seed derivation version" }
        prefs.edit()
            .putString("seed_phrase", seedPhrase)
            .putBoolean(MNEMONIC_BACKUP_REQUIRED_KEY, backupRequired)
            .putInt(SEED_DERIVATION_VERSION_KEY, derivationVersion)
            .apply()
    }

    fun getSeedPhrase(): String? {
        return prefs.getString("seed_phrase", null)
    }

    fun hasSeedPhrase(): Boolean {
        return prefs.getString("seed_phrase", null) != null
    }

    fun getSeedDerivationVersion(): Int {
        if (prefs.contains(SEED_DERIVATION_VERSION_KEY)) {
            return prefs.getInt(
                SEED_DERIVATION_VERSION_KEY,
                Bip39.LEGACY_DERIVATION_VERSION
            )
        }
        if (hasSeedPhrase()) {
            // Unmarked seeds were created by the pre-canonicalization wallet.
            prefs.edit()
                .putInt(SEED_DERIVATION_VERSION_KEY, Bip39.LEGACY_DERIVATION_VERSION)
                .apply()
            return Bip39.LEGACY_DERIVATION_VERSION
        }
        return Bip39.CANONICAL_DERIVATION_VERSION
    }

    fun setMnemonicBackupRequired(required: Boolean) {
        prefs.edit().putBoolean(MNEMONIC_BACKUP_REQUIRED_KEY, required).apply()
    }

    fun isMnemonicBackupRequired(): Boolean {
        return prefs.getBoolean(MNEMONIC_BACKUP_REQUIRED_KEY, false)
    }

    // ═══════════════════════════════════════════
    //  HD Wallet Address Index Tracking
    // ═══════════════════════════════════════════

    /**
     * Store the next receiving address index for derivation.
     */
    fun storeNextReceivingIndex(index: Int) {
        storeNextReceivingIndex(DEFAULT_COIN_ID, index)
    }

    fun storeNextReceivingIndex(coinId: String, index: Int) {
        prefs.edit().putInt(coinKey(coinId, "hd_next_receiving_index"), index).apply()
    }

    fun getNextReceivingIndex(): Int {
        return getNextReceivingIndex(DEFAULT_COIN_ID)
    }

    fun getNextReceivingIndex(coinId: String): Int {
        return getCoinInt(coinId, "hd_next_receiving_index", "hd_next_receiving_index", 0)
    }

    /**
     * Store the next change address index for derivation.
     */
    fun storeNextChangeIndex(index: Int) {
        storeNextChangeIndex(DEFAULT_COIN_ID, index)
    }

    fun storeNextChangeIndex(coinId: String, index: Int) {
        prefs.edit().putInt(coinKey(coinId, "hd_next_change_index"), index).apply()
    }

    fun getNextChangeIndex(): Int {
        return getNextChangeIndex(DEFAULT_COIN_ID)
    }

    fun getNextChangeIndex(coinId: String): Int {
        return getCoinInt(coinId, "hd_next_change_index", "hd_next_change_index", 0)
    }

    /**
     * BIP84 indexes deliberately do not fall back to the legacy BIP44 keys.
     * Reusing a BIP44 index here would skip native SegWit addresses during recovery.
     */
    fun storeNextBip84ReceivingIndex(coinId: String, index: Int) {
        prefs.edit().putInt(coinKey(coinId, BIP84_RECEIVING_INDEX_SUFFIX), index).apply()
    }

    fun getNextBip84ReceivingIndex(coinId: String): Int {
        return prefs.getInt(coinKey(coinId, BIP84_RECEIVING_INDEX_SUFFIX), 0)
    }

    fun storeNextBip84ChangeIndex(coinId: String, index: Int) {
        prefs.edit().putInt(coinKey(coinId, BIP84_CHANGE_INDEX_SUFFIX), index).apply()
    }

    fun getNextBip84ChangeIndex(coinId: String): Int {
        return prefs.getInt(coinKey(coinId, BIP84_CHANGE_INDEX_SUFFIX), 0)
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
        setIsHdWallet(DEFAULT_COIN_ID, isHd)
    }

    fun setIsHdWallet(coinId: String, isHd: Boolean) {
        prefs.edit().putBoolean(coinKey(coinId, "is_hd_wallet"), isHd).apply()
    }

    fun isHdWallet(): Boolean {
        return isHdWallet(DEFAULT_COIN_ID)
    }

    fun isHdWallet(coinId: String): Boolean {
        return getCoinBoolean(coinId, "is_hd_wallet", "is_hd_wallet", false)
    }

    fun setHdDiscoveryPending(coinId: String, pending: Boolean) {
        prefs.edit().putBoolean(coinKey(coinId, "hd_discovery_pending"), pending).apply()
    }

    fun isHdDiscoveryPending(coinId: String): Boolean {
        val key = coinKey(coinId, "hd_discovery_pending")
        return if (prefs.contains(key)) {
            prefs.getBoolean(key, true)
        } else {
            // Existing HD wallets predate this marker and need one complete recovery scan.
            isHdWallet(coinId)
        }
    }

    fun setBip84DiscoveryPending(coinId: String, pending: Boolean) {
        prefs.edit().putBoolean(coinKey(coinId, BIP84_DISCOVERY_PENDING_SUFFIX), pending).apply()
    }

    /**
     * A missing marker means an upgraded wallet has never scanned its BIP84
     * branches. Callers may mark it complete only after a successful gap scan.
     */
    fun isBip84DiscoveryPending(coinId: String): Boolean {
        return prefs.getBoolean(coinKey(coinId, BIP84_DISCOVERY_PENDING_SUFFIX), true)
    }

    // ═══════════════════════════════════════════
    //  General
    // ═══════════════════════════════════════════

    fun hasWallet(): Boolean {
        return hasWallet(DEFAULT_COIN_ID)
    }

    fun hasWallet(coinId: String): Boolean {
        return getPrimaryAddress(coinId) != null
    }

    fun setSelectedCoinId(coinId: String) {
        prefs.edit().putString(SELECTED_COIN_ID_KEY, normalizeCoinId(coinId)).apply()
    }

    fun getSelectedCoinId(): String {
        val stored = prefs.getString(SELECTED_COIN_ID_KEY, null)
        return stored?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_COIN_ID
    }

    fun clearCoin(coinId: String) {
        val normalizedCoinId = normalizeCoinId(coinId)
        val prefix = "coin_${normalizedCoinId}_"
        val editor = prefs.edit()

        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        if (normalizedCoinId == DEFAULT_COIN_ID) {
            prefs.all.keys.filter { key ->
                key.startsWith("pk_") || key == "primary_address" ||
                    key == "hd_next_receiving_index" || key == "hd_next_change_index" ||
                    key == "is_hd_wallet"
            }.forEach(editor::remove)
        }
        editor.apply()
    }

    fun clearAll() {
        check(prefs.edit().clear().commit()) { "Failed to clear secure wallet storage" }
    }
}
