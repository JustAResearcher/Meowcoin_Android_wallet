package com.meowcoin.wallet.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WalletEntity::class, TransactionEntity::class, UtxoEntity::class, AssetEntity::class],
    version = 3,
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

        // v3 fixes parseTxFromElectrum (vin prev-output lookup + singular
        // scriptPubKey.address support). Wipe the cached, mis-parsed
        // transaction rows so the next refresh re-fetches with the corrected
        // logic. UTXOs are re-synced on every refresh, so we wipe them too to
        // avoid serving stale data from the old schema.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM transactions")
                db.execSQL("DELETE FROM utxos")
            }
        }

        fun getInstance(context: Context): WalletDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WalletDatabase::class.java,
                    "meowcoin_wallet.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
