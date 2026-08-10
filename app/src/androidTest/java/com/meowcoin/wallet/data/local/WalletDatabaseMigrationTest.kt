package com.meowcoin.wallet.data.local

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meowcoin.wallet.crypto.Bip32
import com.meowcoin.wallet.crypto.Bip39
import com.meowcoin.wallet.crypto.CoinRegistry
import com.meowcoin.wallet.crypto.HdWallet
import com.meowcoin.wallet.crypto.SecureKeyStore
import com.meowcoin.wallet.data.repository.WalletRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletDatabaseMigrationTest {

    private lateinit var context: Context
    private var database: WalletDatabase? = null

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE)
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migration3To5PreservesEveryLegacyTableAsMeowcoinP2pkh() {
        createVersion3Database()

        database = Room.databaseBuilder(context, WalletDatabase::class.java, TEST_DATABASE)
            .addMigrations(WalletDatabase.MIGRATION_3_4, WalletDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()

        val sqlite = database!!.openHelper.writableDatabase

        sqlite.query("SELECT * FROM wallets").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("mewc", cursor.getString(cursor.getColumnIndexOrThrow("coinId")))
            assertEquals("legacy-address", cursor.getString(cursor.getColumnIndexOrThrow("address")))
            assertEquals("Legacy Wallet", cursor.getString(cursor.getColumnIndexOrThrow("label")))
            assertEquals(123L, cursor.getLong(cursor.getColumnIndexOrThrow("createdAt")))
            assertEquals(7, cursor.getInt(cursor.getColumnIndexOrThrow("derivationIndex")))
            assertEquals("P2PKH", cursor.getString(cursor.getColumnIndexOrThrow("addressType")))
            assertEquals(44, cursor.getInt(cursor.getColumnIndexOrThrow("derivationPurpose")))
        }

        sqlite.query("SELECT * FROM transactions").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("mewc", cursor.getString(cursor.getColumnIndexOrThrow("coinId")))
            assertEquals("legacy-tx", cursor.getString(cursor.getColumnIndexOrThrow("txId")))
            assertEquals(42_000L, cursor.getLong(cursor.getColumnIndexOrThrow("amount")))
            assertEquals("confirmed", cursor.getString(cursor.getColumnIndexOrThrow("status")))
        }

        sqlite.query("SELECT * FROM utxos").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("mewc", cursor.getString(cursor.getColumnIndexOrThrow("coinId")))
            assertEquals("legacy-utxo", cursor.getString(cursor.getColumnIndexOrThrow("id")))
            assertEquals(41_000L, cursor.getLong(cursor.getColumnIndexOrThrow("value")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("isSpent")))
        }

        sqlite.query("SELECT * FROM assets").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("mewc", cursor.getString(cursor.getColumnIndexOrThrow("coinId")))
            assertEquals("LEGACY:legacy-address", cursor.getString(cursor.getColumnIndexOrThrow("id")))
            assertEquals(99L, cursor.getLong(cursor.getColumnIndexOrThrow("amount")))
            assertEquals("legacy-ipfs", cursor.getString(cursor.getColumnIndexOrThrow("ipfsHash")))
        }
    }

    @Test
    fun migration1To5PreservesWalletAndAddsCoinScopeAndDerivationMetadata() {
        createVersion1Database()

        database = Room.databaseBuilder(context, WalletDatabase::class.java, TEST_DATABASE)
            .addMigrations(
                WalletDatabase.MIGRATION_1_2,
                WalletDatabase.MIGRATION_2_3,
                WalletDatabase.MIGRATION_3_4,
                WalletDatabase.MIGRATION_4_5
            )
            .allowMainThreadQueries()
            .build()

        val sqlite = database!!.openHelper.writableDatabase
        sqlite.query("SELECT * FROM wallets").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("legacy-v1", cursor.getString(cursor.getColumnIndexOrThrow("address")))
            assertEquals("mewc", cursor.getString(cursor.getColumnIndexOrThrow("coinId")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("derivationPath")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("derivationIndex")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("isChange")))
            assertEquals("P2PKH", cursor.getString(cursor.getColumnIndexOrThrow("addressType")))
            assertEquals(44, cursor.getInt(cursor.getColumnIndexOrThrow("derivationPurpose")))
        }

        sqlite.query("SELECT COUNT(*) FROM assets").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migration4To5PreservesCompositeCoinRowsWithLegacyDefaults() {
        createVersion4Database()

        database = Room.databaseBuilder(context, WalletDatabase::class.java, TEST_DATABASE)
            .addMigrations(WalletDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()

        val sqlite = database!!.openHelper.writableDatabase
        sqlite.query("SELECT * FROM wallets ORDER BY coinId").use { cursor ->
            assertEquals(2, cursor.count)

            cursor.moveToFirst()
            assertEquals("ltc", cursor.getString(cursor.getColumnIndexOrThrow("coinId")))
            assertEquals("shared-address", cursor.getString(cursor.getColumnIndexOrThrow("address")))
            assertEquals("P2PKH", cursor.getString(cursor.getColumnIndexOrThrow("addressType")))
            assertEquals(44, cursor.getInt(cursor.getColumnIndexOrThrow("derivationPurpose")))

            cursor.moveToNext()
            assertEquals("mewc", cursor.getString(cursor.getColumnIndexOrThrow("coinId")))
            assertEquals("shared-address", cursor.getString(cursor.getColumnIndexOrThrow("address")))
            assertEquals("P2PKH", cursor.getString(cursor.getColumnIndexOrThrow("addressType")))
            assertEquals(44, cursor.getInt(cursor.getColumnIndexOrThrow("derivationPurpose")))
        }
    }

    @Test
    fun upgradedVersion3MeowcoinHdStateInitializesLitecoinAccount() = runBlocking {
        createVersion3Database()
        database = Room.databaseBuilder(context, WalletDatabase::class.java, TEST_DATABASE)
            .addMigrations(WalletDatabase.MIGRATION_3_4, WalletDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()

        val prefs = encryptedPreferences(context)
        prefs.edit().clear().commit()
        val mnemonic =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon about"
        prefs.edit()
            .putString("seed_phrase", mnemonic)
            .putString("primary_address", "legacy-mewc-address")
            .putBoolean("is_hd_wallet", true)
            .putInt("hd_next_receiving_index", 7)
            .putInt("hd_next_change_index", 3)
            .commit()

        val keyStore = SecureKeyStore(context)
        assertEquals("legacy-mewc-address", keyStore.getPrimaryAddress(CoinRegistry.MEWC.id))
        assertTrue(keyStore.isHdWallet(CoinRegistry.MEWC.id))
        assertEquals(7, keyStore.getNextReceivingIndex(CoinRegistry.MEWC.id))
        assertEquals(3, keyStore.getNextChangeIndex(CoinRegistry.MEWC.id))

        val repository = WalletRepository(database!!, keyStore, CoinRegistry.LTC)
        try {
            val expectedWallet = HdWallet.fromMnemonic(
                mnemonic,
                CoinRegistry.LTC,
                derivationVersion = Bip39.LEGACY_DERIVATION_VERSION
            )
            val expectedLegacy = expectedWallet.deriveReceivingAddress(0, Bip32.BIP44_PURPOSE)
            val expectedSegwit = expectedWallet.deriveReceivingAddress(0, Bip32.BIP84_PURPOSE)

            assertEquals(expectedLegacy, repository.initializeFromStoredSeedIfNeeded())
            assertEquals(expectedSegwit, repository.getDefaultReceiveAddress())
            assertEquals(expectedLegacy, keyStore.getPrimaryAddress(CoinRegistry.LTC.id))
            assertEquals("legacy-mewc-address", keyStore.getPrimaryAddress(CoinRegistry.MEWC.id))
            assertEquals(1, keyStore.getNextReceivingIndex(CoinRegistry.LTC.id))
            assertEquals(1, keyStore.getNextBip84ReceivingIndex(CoinRegistry.LTC.id))

            val litecoinRows = database!!.walletDao().getAllWalletsSync(CoinRegistry.LTC.id)
            assertEquals(
                setOf(Bip32.BIP44_PURPOSE, Bip32.BIP84_PURPOSE),
                litecoinRows.mapTo(mutableSetOf(), WalletEntity::derivationPurpose)
            )
            assertEquals(
                setOf(ADDRESS_TYPE_P2PKH, ADDRESS_TYPE_P2WPKH),
                litecoinRows.mapTo(mutableSetOf(), WalletEntity::addressType)
            )
            litecoinRows.forEach { row ->
                assertNotNull(keyStore.getPrivateKey(CoinRegistry.LTC.id, row.address))
            }
        } finally {
            repository.close()
            keyStore.clearAll()
        }
    }

    @Test
    fun daoReadsAndDeletesAreIsolatedByCoinId() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, WalletDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val db = database!!
        db.walletDao().insertWallet(WalletEntity(address = "shared", coinId = "btc"))
        db.walletDao().insertWallet(
            WalletEntity(
                address = "shared",
                coinId = "ltc",
                addressType = "P2WPKH",
                derivationPurpose = 84
            )
        )
        assertEquals(1, db.walletDao().getAllWalletsSync("btc").size)
        assertEquals(1, db.walletDao().getAllWalletsSync("ltc").size)
        assertEquals(
            "P2WPKH",
            db.walletDao().getWalletByAddress("ltc", "shared")?.addressType
        )
        assertEquals(
            listOf("shared"),
            db.walletDao().getActiveAddressesByPurpose("ltc", 84)
        )
        assertEquals(
            84,
            db.walletDao().getActiveWalletByPurposeSync("ltc", 84)?.derivationPurpose
        )
        assertEquals(0, db.walletDao().getActiveWalletsByPurposeSync("ltc", 44).size)
        db.walletDao().deleteAll("btc")
        assertEquals(0, db.walletDao().getAllWalletsSync("btc").size)
        assertEquals(1, db.walletDao().getAllWalletsSync("ltc").size)

        db.transactionDao().insertTransaction(transaction("btc"))
        db.transactionDao().insertTransaction(transaction("ltc"))
        assertNotNull(db.transactionDao().getTransaction("btc", "shared-tx"))
        assertNotNull(db.transactionDao().getTransaction("ltc", "shared-tx"))
        db.transactionDao().deleteAllForWallet("btc", "shared")
        assertNull(db.transactionDao().getTransaction("btc", "shared-tx"))
        assertNotNull(db.transactionDao().getTransaction("ltc", "shared-tx"))

        db.utxoDao().insertUtxo(utxo("btc"))
        db.utxoDao().insertUtxo(utxo("ltc"))
        db.utxoDao().markSpent("btc", "shared-utxo")
        assertEquals(0, db.utxoDao().getUnspentUtxosSync("btc", "shared").size)
        assertEquals(1, db.utxoDao().getUnspentUtxosSync("ltc", "shared").size)

        db.assetDao().insertAsset(asset("btc"))
        db.assetDao().insertAsset(asset("ltc"))
        db.assetDao().deleteAllForWallet("btc", "shared")
        assertNull(db.assetDao().getAsset("btc", "SHARED", "shared"))
        assertNotNull(db.assetDao().getAsset("ltc", "SHARED", "shared"))
    }

    private fun createVersion3Database() {
        val path = context.getDatabasePath(TEST_DATABASE)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE wallets (
                    address TEXT NOT NULL PRIMARY KEY,
                    label TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    isActive INTEGER NOT NULL,
                    derivationPath TEXT NOT NULL,
                    derivationIndex INTEGER NOT NULL,
                    isChange INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE transactions (
                    txId TEXT NOT NULL PRIMARY KEY,
                    walletAddress TEXT NOT NULL,
                    amount INTEGER NOT NULL,
                    fee INTEGER NOT NULL,
                    toAddress TEXT NOT NULL,
                    fromAddress TEXT NOT NULL,
                    confirmations INTEGER NOT NULL,
                    blockHeight INTEGER NOT NULL,
                    timestamp INTEGER NOT NULL,
                    status TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE utxos (
                    id TEXT NOT NULL PRIMARY KEY,
                    txHash TEXT NOT NULL,
                    outputIndex INTEGER NOT NULL,
                    walletAddress TEXT NOT NULL,
                    value INTEGER NOT NULL,
                    scriptPubKey TEXT NOT NULL,
                    confirmations INTEGER NOT NULL,
                    isSpent INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE assets (
                    id TEXT NOT NULL PRIMARY KEY,
                    assetName TEXT NOT NULL,
                    walletAddress TEXT NOT NULL,
                    amount INTEGER NOT NULL,
                    units INTEGER NOT NULL,
                    reissuable INTEGER NOT NULL,
                    hasIpfs INTEGER NOT NULL,
                    ipfsHash TEXT NOT NULL,
                    lastUpdated INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                "INSERT INTO wallets VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf("legacy-address", "Legacy Wallet", 123L, 1, "m/44'/1669'/0'/0/7", 7, 0)
            )
            db.execSQL(
                "INSERT INTO transactions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("legacy-tx", "legacy-address", 42_000L, 1_000L, "to", "from", 8, 100, 456L, "confirmed")
            )
            db.execSQL(
                "INSERT INTO utxos VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("legacy-utxo", "legacy-tx", 0, "legacy-address", 41_000L, "76a9", 8, 0)
            )
            db.execSQL(
                "INSERT INTO assets VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("LEGACY:legacy-address", "LEGACY", "legacy-address", 99L, 2, 1, 1, "legacy-ipfs", 789L)
            )
            db.version = 3
        }
    }

    private fun createVersion1Database() {
        val path = context.getDatabasePath(TEST_DATABASE)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE wallets (
                    address TEXT NOT NULL PRIMARY KEY,
                    label TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    isActive INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE transactions (
                    txId TEXT NOT NULL PRIMARY KEY,
                    walletAddress TEXT NOT NULL,
                    amount INTEGER NOT NULL,
                    fee INTEGER NOT NULL,
                    toAddress TEXT NOT NULL,
                    fromAddress TEXT NOT NULL,
                    confirmations INTEGER NOT NULL,
                    blockHeight INTEGER NOT NULL,
                    timestamp INTEGER NOT NULL,
                    status TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE utxos (
                    id TEXT NOT NULL PRIMARY KEY,
                    txHash TEXT NOT NULL,
                    outputIndex INTEGER NOT NULL,
                    walletAddress TEXT NOT NULL,
                    value INTEGER NOT NULL,
                    scriptPubKey TEXT NOT NULL,
                    confirmations INTEGER NOT NULL,
                    isSpent INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO wallets VALUES (?, ?, ?, ?)",
                arrayOf("legacy-v1", "Legacy v1", 1L, 1)
            )
            db.version = 1
        }
    }

    private fun createVersion4Database() {
        val path = context.getDatabasePath(TEST_DATABASE)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE wallets (
                    address TEXT NOT NULL,
                    label TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    isActive INTEGER NOT NULL,
                    derivationPath TEXT NOT NULL,
                    derivationIndex INTEGER NOT NULL,
                    isChange INTEGER NOT NULL,
                    coinId TEXT NOT NULL,
                    PRIMARY KEY (coinId, address)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE transactions (
                    txId TEXT NOT NULL,
                    walletAddress TEXT NOT NULL,
                    amount INTEGER NOT NULL,
                    fee INTEGER NOT NULL,
                    toAddress TEXT NOT NULL,
                    fromAddress TEXT NOT NULL,
                    confirmations INTEGER NOT NULL,
                    blockHeight INTEGER NOT NULL,
                    timestamp INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    coinId TEXT NOT NULL,
                    PRIMARY KEY (coinId, txId)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE utxos (
                    id TEXT NOT NULL,
                    txHash TEXT NOT NULL,
                    outputIndex INTEGER NOT NULL,
                    walletAddress TEXT NOT NULL,
                    value INTEGER NOT NULL,
                    scriptPubKey TEXT NOT NULL,
                    confirmations INTEGER NOT NULL,
                    isSpent INTEGER NOT NULL,
                    coinId TEXT NOT NULL,
                    PRIMARY KEY (coinId, id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE assets (
                    id TEXT NOT NULL,
                    assetName TEXT NOT NULL,
                    walletAddress TEXT NOT NULL,
                    amount INTEGER NOT NULL,
                    units INTEGER NOT NULL,
                    reissuable INTEGER NOT NULL,
                    hasIpfs INTEGER NOT NULL,
                    ipfsHash TEXT NOT NULL,
                    lastUpdated INTEGER NOT NULL,
                    coinId TEXT NOT NULL,
                    PRIMARY KEY (coinId, id)
                )
                """.trimIndent()
            )

            db.execSQL(
                "INSERT INTO wallets VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(
                    "shared-address",
                    "Litecoin",
                    10L,
                    1,
                    "m/44'/2'/0'/0/3",
                    3,
                    0,
                    "ltc"
                )
            )
            db.execSQL(
                "INSERT INTO wallets VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(
                    "shared-address",
                    "Meowcoin",
                    20L,
                    1,
                    "m/44'/1669'/0'/1/4",
                    4,
                    1,
                    "mewc"
                )
            )
            db.version = 4
        }
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

    private fun transaction(coinId: String) = TransactionEntity(
        txId = "shared-tx",
        walletAddress = "shared",
        amount = 1,
        toAddress = "destination",
        coinId = coinId
    )

    private fun utxo(coinId: String) = UtxoEntity(
        id = "shared-utxo",
        txHash = "shared-tx",
        outputIndex = 0,
        walletAddress = "shared",
        value = 1,
        scriptPubKey = "76a9",
        coinId = coinId
    )

    private fun asset(coinId: String) = AssetEntity(
        id = "SHARED:shared",
        assetName = "SHARED",
        walletAddress = "shared",
        coinId = coinId
    )

    private companion object {
        const val TEST_DATABASE = "wallet-migration-test.db"
    }
}
