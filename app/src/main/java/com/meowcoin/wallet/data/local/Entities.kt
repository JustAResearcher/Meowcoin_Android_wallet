package com.meowcoin.wallet.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity

const val DEFAULT_COIN_ID = "mewc"
const val ADDRESS_TYPE_P2PKH = "P2PKH"
const val ADDRESS_TYPE_P2WPKH = "P2WPKH"
const val DEFAULT_ADDRESS_TYPE = ADDRESS_TYPE_P2PKH
const val DEFAULT_DERIVATION_PURPOSE = 44

@Entity(
    tableName = "wallets",
    primaryKeys = ["coinId", "address"]
)
data class WalletEntity(
    val address: String,
    val label: String = "Main Wallet",
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    val derivationPath: String = "",   // e.g. "m/44'/1669'/0'/0/0"
    val derivationIndex: Int = 0,      // address_index within the stored purpose
    val isChange: Boolean = false,     // true = change chain (m/.../1/x)
    @ColumnInfo(defaultValue = "'P2PKH'")
    val addressType: String = DEFAULT_ADDRESS_TYPE,
    @ColumnInfo(defaultValue = "44")
    val derivationPurpose: Int = DEFAULT_DERIVATION_PURPOSE,
    val coinId: String = DEFAULT_COIN_ID
)

@Entity(
    tableName = "transactions",
    primaryKeys = ["coinId", "txId"]
)
data class TransactionEntity(
    val txId: String,
    val walletAddress: String,
    val amount: Long,           // In satoshis, positive = received, negative = sent
    val fee: Long = 0,          // Fee in satoshis
    val toAddress: String,
    val fromAddress: String = "",
    val confirmations: Int = 0,
    val blockHeight: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "pending", // pending, confirmed, failed
    val coinId: String = DEFAULT_COIN_ID
)

@Entity(
    tableName = "utxos",
    primaryKeys = ["coinId", "id"]
)
data class UtxoEntity(
    val id: String,             // txHash:outputIndex
    val txHash: String,
    val outputIndex: Int,
    val walletAddress: String,
    val value: Long,            // In satoshis
    val scriptPubKey: String,
    val confirmations: Int = 0,
    val isSpent: Boolean = false,
    val coinId: String = DEFAULT_COIN_ID
)

/**
 * Meowcoin asset (MEWC uses RVN-style asset layer).
 * Assets are tokens created on the Meowcoin blockchain.
 */
@Entity(
    tableName = "assets",
    primaryKeys = ["coinId", "id"]
)
data class AssetEntity(
    val id: String,                     // assetName:walletAddress
    val assetName: String,              // e.g. "MY_TOKEN"
    val walletAddress: String,
    val amount: Long = 0,               // Quantity in smallest unit
    val units: Int = 0,                 // Decimal places (0-8)
    val reissuable: Boolean = false,
    val hasIpfs: Boolean = false,
    val ipfsHash: String = "",
    val lastUpdated: Long = System.currentTimeMillis(),
    val coinId: String = DEFAULT_COIN_ID
)
