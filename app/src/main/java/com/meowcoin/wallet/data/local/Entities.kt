package com.meowcoin.wallet.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wallets")
data class WalletEntity(
    @PrimaryKey
    val address: String,
    val label: String = "Main Wallet",
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    val derivationPath: String = "",   // e.g. "m/44'/1669'/0'/0/0"
    val derivationIndex: Int = 0,      // BIP44 address_index
    val isChange: Boolean = false      // true = change chain (m/.../1/x)
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

/**
 * Meowcoin asset (MEWC uses RVN-style asset layer).
 * Assets are tokens created on the Meowcoin blockchain.
 */
@Entity(tableName = "assets")
data class AssetEntity(
    @PrimaryKey
    val id: String,                     // assetName:walletAddress
    val assetName: String,              // e.g. "MY_TOKEN"
    val walletAddress: String,
    val amount: Long = 0,               // Quantity in smallest unit
    val units: Int = 0,                 // Decimal places (0-8)
    val reissuable: Boolean = false,
    val hasIpfs: Boolean = false,
    val ipfsHash: String = "",
    val lastUpdated: Long = System.currentTimeMillis()
)
