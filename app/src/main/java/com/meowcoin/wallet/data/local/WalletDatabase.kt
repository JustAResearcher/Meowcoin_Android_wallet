package com.meowcoin.wallet.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WalletEntity::class, TransactionEntity::class, UtxoEntity::class, AssetEntity::class],
    version = 5,
    exportSchema = false
)
abstract class WalletDatabase : RoomDatabase() {
    abstract fun walletDao(): WalletDao
    abstract fun transactionDao(): TransactionDao
    abstract fun utxoDao(): UtxoDao
    abstract fun assetDao(): AssetDao

    companion object {
        @Volatile
        private var INSTANCE: WalletDatabase? = null

        /** Preserve v1 wallets while adding the HD address columns and assets table from v2. */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE wallets ADD COLUMN derivationPath TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE wallets ADD COLUMN derivationIndex INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE wallets ADD COLUMN isChange INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS assets (
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
            }
        }

        // v3 fixes parseTxFromElectrum (vin prev-output lookup + singular
        // scriptPubKey.address support). Wipe the cached, mis-parsed
        // transaction rows so the next refresh re-fetches with the corrected
        // logic. UTXOs are re-synced on every refresh, so we wipe them too to
        // avoid serving stale data from the old schema.
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM transactions")
                db.execSQL("DELETE FROM utxos")
            }
        }

        /**
         * Adds coin isolation without losing existing single-coin data. Rebuilding
         * the tables is required because each legacy primary key becomes part of
         * a composite (coinId, legacyKey) primary key.
         */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE wallets_new (
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
                    INSERT INTO wallets_new (
                        address, label, createdAt, isActive, derivationPath,
                        derivationIndex, isChange, coinId
                    )
                    SELECT address, label, createdAt, isActive, derivationPath,
                           derivationIndex, isChange, 'mewc'
                    FROM wallets
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE wallets")
                db.execSQL("ALTER TABLE wallets_new RENAME TO wallets")

                db.execSQL(
                    """
                    CREATE TABLE transactions_new (
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
                    INSERT INTO transactions_new (
                        txId, walletAddress, amount, fee, toAddress, fromAddress,
                        confirmations, blockHeight, timestamp, status, coinId
                    )
                    SELECT txId, walletAddress, amount, fee, toAddress, fromAddress,
                           confirmations, blockHeight, timestamp, status, 'mewc'
                    FROM transactions
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE transactions")
                db.execSQL("ALTER TABLE transactions_new RENAME TO transactions")

                db.execSQL(
                    """
                    CREATE TABLE utxos_new (
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
                    INSERT INTO utxos_new (
                        id, txHash, outputIndex, walletAddress, value, scriptPubKey,
                        confirmations, isSpent, coinId
                    )
                    SELECT id, txHash, outputIndex, walletAddress, value, scriptPubKey,
                           confirmations, isSpent, 'mewc'
                    FROM utxos
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE utxos")
                db.execSQL("ALTER TABLE utxos_new RENAME TO utxos")

                db.execSQL(
                    """
                    CREATE TABLE assets_new (
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
                    """
                    INSERT INTO assets_new (
                        id, assetName, walletAddress, amount, units, reissuable,
                        hasIpfs, ipfsHash, lastUpdated, coinId
                    )
                    SELECT id, assetName, walletAddress, amount, units, reissuable,
                           hasIpfs, ipfsHash, lastUpdated, 'mewc'
                    FROM assets
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE assets")
                db.execSQL("ALTER TABLE assets_new RENAME TO assets")
            }
        }

        /**
         * Records how each owned address must be derived and spent. All rows
         * created by earlier versions are legacy BIP44 P2PKH addresses.
         */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE wallets ADD COLUMN addressType TEXT NOT NULL DEFAULT 'P2PKH'"
                )
                db.execSQL(
                    "ALTER TABLE wallets ADD COLUMN derivationPurpose INTEGER NOT NULL DEFAULT 44"
                )
            }
        }

        fun getInstance(context: Context): WalletDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WalletDatabase::class.java,
                    "meowcoin_wallet.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
