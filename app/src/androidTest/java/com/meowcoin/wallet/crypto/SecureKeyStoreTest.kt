package com.meowcoin.wallet.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureKeyStoreTest {

    private lateinit var context: Context
    private lateinit var store: SecureKeyStore
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = encryptedPreferences(context)
        prefs.edit().clear().commit()
        store = SecureKeyStore(context)
    }

    @After
    fun tearDown() {
        store.clearAll()
    }

    @Test
    fun legacyMeowcoinValuesAreMigratedWithoutLeakingToOtherCoins() {
        prefs.edit()
            .putString("primary_address", "legacy-address")
            .putString("pk_legacy-address", "legacy-private-key")
            .putInt("hd_next_receiving_index", 4)
            .putInt("hd_next_change_index", 2)
            .putBoolean("is_hd_wallet", true)
            .commit()

        assertEquals("legacy-address", store.getPrimaryAddress("mewc"))
        assertEquals("legacy-private-key", store.getPrivateKey("mewc", "legacy-address"))
        assertEquals(4, store.getNextReceivingIndex("mewc"))
        assertEquals(2, store.getNextChangeIndex("mewc"))
        assertEquals(0, store.getNextBip84ReceivingIndex("mewc"))
        assertEquals(0, store.getNextBip84ChangeIndex("mewc"))
        assertTrue(store.isBip84DiscoveryPending("mewc"))
        assertTrue(store.isHdWallet("mewc"))

        assertTrue(prefs.contains("coin_mewc_primary_address"))
        assertTrue(prefs.contains("coin_mewc_pk_legacy-address"))
        assertTrue(prefs.contains("coin_mewc_hd_next_receiving_index"))
        assertTrue(prefs.contains("coin_mewc_hd_next_change_index"))
        assertTrue(prefs.contains("coin_mewc_is_hd_wallet"))

        assertNull(store.getPrimaryAddress("btc"))
        assertNull(store.getPrivateKey("btc", "legacy-address"))
        assertEquals(0, store.getNextReceivingIndex("btc"))
        assertEquals(0, store.getNextChangeIndex("btc"))
        assertFalse(store.isHdWallet("btc"))
    }

    @Test
    fun coinValuesAreIsolatedWhileSeedAndUserPreferencesStayGlobal() {
        store.storeSeedPhrase("global seed phrase")
        store.setBiometricEnabled(true)
        store.setFiatCurrency("EUR")

        store.storePrimaryAddress("btc", "shared-address")
        store.storePrivateKey("btc", "shared-address", "btc-key")
        store.storeNextReceivingIndex("btc", 10)
        store.storeNextChangeIndex("btc", 11)
        store.storeNextBip84ReceivingIndex("btc", 12)
        store.storeNextBip84ChangeIndex("btc", 13)
        store.setBip84DiscoveryPending("btc", false)
        store.setIsHdWallet("btc", true)

        store.storePrimaryAddress("ltc", "shared-address")
        store.storePrivateKey("ltc", "shared-address", "ltc-key")
        store.storeNextReceivingIndex("ltc", 20)
        store.storeNextChangeIndex("ltc", 21)
        store.storeNextBip84ReceivingIndex("ltc", 22)
        store.storeNextBip84ChangeIndex("ltc", 23)
        store.setIsHdWallet("ltc", false)

        assertEquals("btc-key", store.getPrivateKey("btc", "shared-address"))
        assertEquals("ltc-key", store.getPrivateKey("ltc", "shared-address"))
        assertEquals(10, store.getNextReceivingIndex("btc"))
        assertEquals(20, store.getNextReceivingIndex("ltc"))
        assertEquals(11, store.getNextChangeIndex("btc"))
        assertEquals(21, store.getNextChangeIndex("ltc"))
        assertEquals(12, store.getNextBip84ReceivingIndex("btc"))
        assertEquals(22, store.getNextBip84ReceivingIndex("ltc"))
        assertEquals(13, store.getNextBip84ChangeIndex("btc"))
        assertEquals(23, store.getNextBip84ChangeIndex("ltc"))
        assertFalse(store.isBip84DiscoveryPending("btc"))
        assertTrue(store.isBip84DiscoveryPending("ltc"))
        assertTrue(store.isHdWallet("btc"))
        assertFalse(store.isHdWallet("ltc"))

        assertEquals("global seed phrase", store.getSeedPhrase())
        assertTrue(store.isBiometricEnabled())
        assertEquals("EUR", store.getFiatCurrency())

        assertEquals("mewc", store.getSelectedCoinId())
        store.setSelectedCoinId("BTC")
        assertEquals("btc", store.getSelectedCoinId())

        store.clearCoin("btc")
        assertNull(store.getPrimaryAddress("btc"))
        assertNull(store.getPrivateKey("btc", "shared-address"))
        assertEquals(0, store.getNextBip84ReceivingIndex("btc"))
        assertEquals(0, store.getNextBip84ChangeIndex("btc"))
        assertTrue(store.isBip84DiscoveryPending("btc"))
        assertEquals("shared-address", store.getPrimaryAddress("ltc"))
        assertEquals("ltc-key", store.getPrivateKey("ltc", "shared-address"))
        assertEquals("global seed phrase", store.getSeedPhrase())
        assertTrue(store.isBiometricEnabled())
        assertEquals("EUR", store.getFiatCurrency())
    }

    @Test
    fun mnemonicBackupRequiredIsGlobalAndClearedWithWalletData() {
        assertFalse(store.isMnemonicBackupRequired())

        store.setMnemonicBackupRequired(true)
        assertTrue(store.isMnemonicBackupRequired())

        store.clearCoin("mewc")
        assertTrue(store.isMnemonicBackupRequired())

        store.clearAll()
        assertFalse(store.isMnemonicBackupRequired())
    }

    @Test
    fun newSeedsRecordCanonicalDerivationVersion() {
        store.storeSeedPhrase(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            backupRequired = false
        )

        assertEquals(Bip39.CANONICAL_DERIVATION_VERSION, store.getSeedDerivationVersion())
    }

    @Test
    fun existingUnmarkedSeedsKeepLegacyDerivationVersion() {
        prefs.edit()
            .putString(
                "seed_phrase",
                "abandon  abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            )
            .commit()

        assertEquals(Bip39.LEGACY_DERIVATION_VERSION, store.getSeedDerivationVersion())
        assertEquals(
            Bip39.LEGACY_DERIVATION_VERSION,
            prefs.getInt("seed_derivation_version", -1)
        )
    }

    private fun encryptedPreferences(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "meowcoin_secure_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
