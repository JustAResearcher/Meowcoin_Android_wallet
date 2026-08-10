package com.meowcoin.wallet.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WalletDao {
    @Query("SELECT * FROM wallets WHERE coinId = :coinId AND isActive = 1 LIMIT 1")
    fun getActiveWallet(coinId: String): Flow<WalletEntity?>

    @Query(
        "SELECT * FROM wallets " +
            "WHERE coinId = :coinId AND derivationPurpose = :derivationPurpose " +
            "AND isActive = 1 LIMIT 1"
    )
    fun getActiveWalletByPurpose(
        coinId: String,
        derivationPurpose: Int
    ): Flow<WalletEntity?>

    @Query("SELECT * FROM wallets WHERE coinId = :coinId AND isActive = 1 LIMIT 1")
    suspend fun getActiveWalletSync(coinId: String): WalletEntity?

    @Query(
        "SELECT * FROM wallets " +
            "WHERE coinId = :coinId AND derivationPurpose = :derivationPurpose " +
            "AND isActive = 1 LIMIT 1"
    )
    suspend fun getActiveWalletByPurposeSync(
        coinId: String,
        derivationPurpose: Int
    ): WalletEntity?

    @Query("SELECT * FROM wallets WHERE coinId = :coinId AND address = :address LIMIT 1")
    suspend fun getWalletByAddress(coinId: String, address: String): WalletEntity?

    @Query("SELECT * FROM wallets WHERE coinId = :coinId")
    fun getAllWallets(coinId: String): Flow<List<WalletEntity>>

    @Query("SELECT * FROM wallets WHERE coinId = :coinId")
    suspend fun getAllWalletsSync(coinId: String): List<WalletEntity>

    @Query("SELECT * FROM wallets WHERE coinId = :coinId AND isActive = 1")
    suspend fun getActiveWalletsSync(coinId: String): List<WalletEntity>

    @Query(
        "SELECT * FROM wallets " +
            "WHERE coinId = :coinId AND derivationPurpose = :derivationPurpose AND isActive = 1"
    )
    suspend fun getActiveWalletsByPurposeSync(
        coinId: String,
        derivationPurpose: Int
    ): List<WalletEntity>

    @Query("SELECT address FROM wallets WHERE coinId = :coinId AND isActive = 1")
    suspend fun getActiveAddresses(coinId: String): List<String>

    @Query(
        "SELECT address FROM wallets " +
            "WHERE coinId = :coinId AND derivationPurpose = :derivationPurpose AND isActive = 1"
    )
    suspend fun getActiveAddressesByPurpose(
        coinId: String,
        derivationPurpose: Int
    ): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWallet(wallet: WalletEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWallets(wallets: List<WalletEntity>): List<Long>

    @Update
    suspend fun updateWallet(wallet: WalletEntity): Int

    @Delete
    suspend fun deleteWallet(wallet: WalletEntity): Int

    @Query("DELETE FROM wallets WHERE coinId = :coinId")
    suspend fun deleteAll(coinId: String): Int
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE coinId = :coinId AND walletAddress = :address ORDER BY timestamp DESC")
    fun getTransactionsForWallet(coinId: String, address: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE coinId = :coinId AND walletAddress IN (:addresses) ORDER BY timestamp DESC")
    fun getTransactionsForAddresses(coinId: String, addresses: List<String>): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE coinId = :coinId AND walletAddress = :address ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentTransactions(coinId: String, address: String, limit: Int = 20): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE coinId = :coinId AND walletAddress IN (:addresses) ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentTransactionsForAddresses(coinId: String, addresses: List<String>, limit: Int = 50): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE coinId = :coinId AND txId = :txId")
    suspend fun getTransaction(coinId: String, txId: String): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactions(transactions: List<TransactionEntity>): List<Long>

    @Update
    suspend fun updateTransaction(transaction: TransactionEntity): Int

    @Query("DELETE FROM transactions WHERE coinId = :coinId AND walletAddress = :address")
    suspend fun deleteAllForWallet(coinId: String, address: String): Int

    @Query("DELETE FROM transactions WHERE coinId = :coinId AND walletAddress IN (:addresses)")
    suspend fun deleteAllForAddresses(coinId: String, addresses: List<String>): Int
}

@Dao
interface UtxoDao {
    @Query("SELECT * FROM utxos WHERE coinId = :coinId AND walletAddress = :address AND isSpent = 0")
    fun getUnspentUtxos(coinId: String, address: String): Flow<List<UtxoEntity>>

    @Query("SELECT * FROM utxos WHERE coinId = :coinId AND walletAddress = :address AND isSpent = 0")
    suspend fun getUnspentUtxosSync(coinId: String, address: String): List<UtxoEntity>

    @Query("SELECT * FROM utxos WHERE coinId = :coinId AND walletAddress IN (:addresses) AND isSpent = 0")
    suspend fun getUnspentUtxosForAddresses(coinId: String, addresses: List<String>): List<UtxoEntity>

    @Query("SELECT SUM(value) FROM utxos WHERE coinId = :coinId AND walletAddress = :address AND isSpent = 0")
    fun getBalance(coinId: String, address: String): Flow<Long?>

    @Query("SELECT SUM(value) FROM utxos WHERE coinId = :coinId AND walletAddress IN (:addresses) AND isSpent = 0")
    fun getTotalBalance(coinId: String, addresses: List<String>): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUtxo(utxo: UtxoEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUtxos(utxos: List<UtxoEntity>): List<Long>

    @Query("UPDATE utxos SET isSpent = 1 WHERE coinId = :coinId AND id = :id")
    suspend fun markSpent(coinId: String, id: String): Int

    @Query("DELETE FROM utxos WHERE coinId = :coinId AND walletAddress = :address")
    suspend fun deleteAllForWallet(coinId: String, address: String): Int

    @Query("DELETE FROM utxos WHERE coinId = :coinId AND walletAddress IN (:addresses)")
    suspend fun deleteAllForAddresses(coinId: String, addresses: List<String>): Int
}

@Dao
interface AssetDao {
    @Query("SELECT * FROM assets WHERE coinId = :coinId AND walletAddress = :address")
    fun getAssetsForWallet(coinId: String, address: String): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE coinId = :coinId AND walletAddress IN (:addresses)")
    fun getAssetsForAddresses(coinId: String, addresses: List<String>): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE coinId = :coinId AND walletAddress IN (:addresses)")
    suspend fun getAssetsForAddressesSync(coinId: String, addresses: List<String>): List<AssetEntity>

    @Query("SELECT * FROM assets WHERE coinId = :coinId AND assetName = :name AND walletAddress = :address")
    suspend fun getAsset(coinId: String, name: String, address: String): AssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAsset(asset: AssetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssets(assets: List<AssetEntity>): List<Long>

    @Query("DELETE FROM assets WHERE coinId = :coinId AND walletAddress = :address")
    suspend fun deleteAllForWallet(coinId: String, address: String): Int

    @Query("DELETE FROM assets WHERE coinId = :coinId AND walletAddress IN (:addresses)")
    suspend fun deleteAllForAddresses(coinId: String, addresses: List<String>): Int
}
