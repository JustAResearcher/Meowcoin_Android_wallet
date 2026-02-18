package com.meowcoin.wallet.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WalletDao {
    @Query("SELECT * FROM wallets WHERE isActive = 1 LIMIT 1")
    fun getActiveWallet(): Flow<WalletEntity?>

    @Query("SELECT * FROM wallets WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveWalletSync(): WalletEntity?

    @Query("SELECT * FROM wallets")
    fun getAllWallets(): Flow<List<WalletEntity>>

    @Query("SELECT * FROM wallets")
    suspend fun getAllWalletsSync(): List<WalletEntity>

    @Query("SELECT * FROM wallets WHERE isActive = 1")
    suspend fun getActiveWalletsSync(): List<WalletEntity>

    @Query("SELECT address FROM wallets WHERE isActive = 1")
    suspend fun getActiveAddresses(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWallet(wallet: WalletEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWallets(wallets: List<WalletEntity>): List<Long>

    @Update
    suspend fun updateWallet(wallet: WalletEntity): Int

    @Delete
    suspend fun deleteWallet(wallet: WalletEntity): Int

    @Query("DELETE FROM wallets")
    suspend fun deleteAll(): Int
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE walletAddress = :address ORDER BY timestamp DESC")
    fun getTransactionsForWallet(address: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE walletAddress IN (:addresses) ORDER BY timestamp DESC")
    fun getTransactionsForAddresses(addresses: List<String>): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE walletAddress = :address ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentTransactions(address: String, limit: Int = 20): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE walletAddress IN (:addresses) ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentTransactionsForAddresses(addresses: List<String>, limit: Int = 50): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE txId = :txId")
    suspend fun getTransaction(txId: String): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactions(transactions: List<TransactionEntity>): List<Long>

    @Update
    suspend fun updateTransaction(transaction: TransactionEntity): Int

    @Query("DELETE FROM transactions WHERE walletAddress = :address")
    suspend fun deleteAllForWallet(address: String): Int

    @Query("DELETE FROM transactions WHERE walletAddress IN (:addresses)")
    suspend fun deleteAllForAddresses(addresses: List<String>): Int
}

@Dao
interface UtxoDao {
    @Query("SELECT * FROM utxos WHERE walletAddress = :address AND isSpent = 0")
    fun getUnspentUtxos(address: String): Flow<List<UtxoEntity>>

    @Query("SELECT * FROM utxos WHERE walletAddress = :address AND isSpent = 0")
    suspend fun getUnspentUtxosSync(address: String): List<UtxoEntity>

    @Query("SELECT * FROM utxos WHERE walletAddress IN (:addresses) AND isSpent = 0")
    suspend fun getUnspentUtxosForAddresses(addresses: List<String>): List<UtxoEntity>

    @Query("SELECT SUM(value) FROM utxos WHERE walletAddress = :address AND isSpent = 0")
    fun getBalance(address: String): Flow<Long?>

    @Query("SELECT SUM(value) FROM utxos WHERE walletAddress IN (:addresses) AND isSpent = 0")
    fun getTotalBalance(addresses: List<String>): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUtxo(utxo: UtxoEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUtxos(utxos: List<UtxoEntity>): List<Long>

    @Query("UPDATE utxos SET isSpent = 1 WHERE id = :id")
    suspend fun markSpent(id: String): Int

    @Query("DELETE FROM utxos WHERE walletAddress = :address")
    suspend fun deleteAllForWallet(address: String): Int

    @Query("DELETE FROM utxos WHERE walletAddress IN (:addresses)")
    suspend fun deleteAllForAddresses(addresses: List<String>): Int
}

@Dao
interface AssetDao {
    @Query("SELECT * FROM assets WHERE walletAddress = :address")
    fun getAssetsForWallet(address: String): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE walletAddress IN (:addresses)")
    fun getAssetsForAddresses(addresses: List<String>): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE walletAddress IN (:addresses)")
    suspend fun getAssetsForAddressesSync(addresses: List<String>): List<AssetEntity>

    @Query("SELECT * FROM assets WHERE assetName = :name AND walletAddress = :address")
    suspend fun getAsset(name: String, address: String): AssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAsset(asset: AssetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssets(assets: List<AssetEntity>): List<Long>

    @Query("DELETE FROM assets WHERE walletAddress = :address")
    suspend fun deleteAllForWallet(address: String): Int

    @Query("DELETE FROM assets WHERE walletAddress IN (:addresses)")
    suspend fun deleteAllForAddresses(addresses: List<String>): Int
}
