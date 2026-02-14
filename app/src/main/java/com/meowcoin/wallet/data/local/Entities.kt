package com.meowcoin.wallet.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wallets")
data class WalletEntity(
    @PrimaryKey
    val address: String,
    val label: String = "Main Wallet",
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
)

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey
    val txId: String,
    val walletAddress: String,
    val amount: Long,           // In satoshis, positive = received, negative = sent
    val fee: Long = 0,          // Fee in satoshis
    val toAddress: String,
    val fromAddress: String = "",
    val confirmations: Int = 0,
    val blockHeight: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "pending" // pending, confirmed, failed
)

@Entity(tableName = "utxos")
data class UtxoEntity(
    @PrimaryKey
    val id: String,             // txHash:outputIndex
    val txHash: String,
    val outputIndex: Int,
    val walletAddress: String,
    val value: Long,            // In satoshis
    val scriptPubKey: String,
    val confirmations: Int = 0,
    val isSpent: Boolean = false
)
