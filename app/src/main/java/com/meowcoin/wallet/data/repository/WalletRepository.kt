package com.meowcoin.wallet.data.repository

import android.util.Log
import com.google.gson.JsonObject
import com.meowcoin.wallet.crypto.*
import com.meowcoin.wallet.data.local.*
import com.meowcoin.wallet.data.remote.ElectrumClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Main wallet repository — light client powered by Electrum servers.
 *
 * All blockchain data is fetched via the Stratum (Electrum) protocol,
 * meaning this wallet never needs to download or verify the full chain.
 */
class WalletRepository(
    private val database: WalletDatabase,
    private val secureKeyStore: SecureKeyStore,
    val electrumClient: ElectrumClient = ElectrumClient()
) {
    companion object {
        private const val TAG = "WalletRepository"

        fun formatMEWC(satoshis: Long): String {
            val mewc = satoshis / 100_000_000.0
            return "%.8f".format(mewc)
        }

        fun formatMEWCShort(satoshis: Long): String {
            val mewc = satoshis / 100_000_000.0
            return "%.2f".format(mewc)
        }

        fun parseMEWCtoSatoshis(mewcString: String): Long {
            val mewc = mewcString.toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid amount")
            return (mewc * 100_000_000).toLong()
        }
    }

    private val walletDao = database.walletDao()
    private val transactionDao = database.transactionDao()
    private val utxoDao = database.utxoDao()

    // Connection state exposed from Electrum client
    val connectionState = electrumClient.connectionState
    val serverInfo = electrumClient.serverInfo

    // ═══════════════════════════════════════════
    //  Connection
    // ═══════════════════════════════════════════

    /**
     * Connect to the Meowcoin Electrum network.
     */
    suspend fun connectToNetwork(): Boolean {
        return electrumClient.connect()
    }

    /**
     * Connect to a custom Electrum server (e.g. user's own node).
     */
    suspend fun connectToCustomServer(host: String, port: Int, useSSL: Boolean = true): Boolean {
        return electrumClient.connectToCustomServer(host, port, useSSL)
    }

    fun disconnect() {
        electrumClient.disconnect()
    }

    // ═══════════════════════════════════════════
    //  Wallet Management
    // ═══════════════════════════════════════════

    fun hasWallet(): Boolean = secureKeyStore.hasWallet()

    fun getActiveWallet(): Flow<WalletEntity?> = walletDao.getActiveWallet()

    suspend fun createWallet(label: String = "Main Wallet"): String = withContext(Dispatchers.IO) {
        val keyPair = MeowcoinKeyPair.generate()
        val address = keyPair.toAddress()

        secureKeyStore.storePrivateKey(address, keyPair.privateKeyHex())
        secureKeyStore.storePrimaryAddress(address)

        walletDao.insertWallet(
            WalletEntity(address = address, label = label, createdAt = System.currentTimeMillis())
        )

        address
    }

    suspend fun importWalletFromWIF(wif: String, label: String = "Imported Wallet"): String =
        withContext(Dispatchers.IO) {
            val keyPair = MeowcoinKeyPair.fromWIF(wif)
            val address = keyPair.toAddress()

            secureKeyStore.storePrivateKey(address, keyPair.privateKeyHex())
            secureKeyStore.storePrimaryAddress(address)

            walletDao.insertWallet(
                WalletEntity(address = address, label = label, createdAt = System.currentTimeMillis())
            )

            // Sync from Electrum
            refreshWalletData(address)

            address
        }

    suspend fun importWalletFromPrivateKey(
        privateKeyHex: String,
        label: String = "Imported Wallet"
    ): String = withContext(Dispatchers.IO) {
        val keyPair = MeowcoinKeyPair.fromPrivateKey(privateKeyHex)
        val address = keyPair.toAddress()

        secureKeyStore.storePrivateKey(address, keyPair.privateKeyHex())
        secureKeyStore.storePrimaryAddress(address)

        walletDao.insertWallet(
            WalletEntity(address = address, label = label, createdAt = System.currentTimeMillis())
        )

        refreshWalletData(address)
        address
    }

    fun getWIF(address: String): String? {
        val pkHex = secureKeyStore.getPrivateKey(address) ?: return null
        return MeowcoinKeyPair.fromPrivateKey(pkHex).toWIF()
    }

    // ═══════════════════════════════════════════
    //  Balance (from local DB, synced via Electrum)
    // ═══════════════════════════════════════════

    fun getBalance(address: String): Flow<Long?> = utxoDao.getBalance(address)

    fun getBalanceMEWC(address: String): Flow<String> =
        utxoDao.getBalance(address).map { formatMEWC(it ?: 0) }

    // ═══════════════════════════════════════════
    //  Transactions
    // ═══════════════════════════════════════════

    fun getTransactions(address: String): Flow<List<TransactionEntity>> =
        transactionDao.getTransactionsForWallet(address)

    fun getRecentTransactions(address: String, limit: Int = 20): Flow<List<TransactionEntity>> =
        transactionDao.getRecentTransactions(address, limit)

    // ═══════════════════════════════════════════
    //  UTXOs
    // ═══════════════════════════════════════════

    fun getUnspentUtxos(address: String): Flow<List<UtxoEntity>> =
        utxoDao.getUnspentUtxos(address)

    // ═══════════════════════════════════════════
    //  Send Transaction (via Electrum broadcast)
    // ═══════════════════════════════════════════

    suspend fun sendTransaction(
        fromAddress: String,
        toAddress: String,
        amountSatoshis: Long,
        feeRate: Long = MeowcoinTransaction.DEFAULT_FEE_RATE
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            require(MeowcoinAddress.isValid(toAddress)) { "Invalid destination address" }
            require(amountSatoshis > MeowcoinTransaction.DUST_THRESHOLD) {
                "Amount below dust threshold"
            }

            val pkHex = secureKeyStore.getPrivateKey(fromAddress)
                ?: return@withContext Result.failure(Exception("No private key found"))

            val keyPair = MeowcoinKeyPair.fromPrivateKey(pkHex)

            // Get UTXOs from local DB (synced from Electrum)
            val utxoEntities = utxoDao.getUnspentUtxosSync(fromAddress)
            require(utxoEntities.isNotEmpty()) { "No spendable UTXOs" }

            val utxos = utxoEntities.map { entity ->
                MeowcoinTransaction.UTXO(
                    txHash = entity.txHash,
                    outputIndex = entity.outputIndex,
                    value = entity.value,
                    scriptPubKey = entity.scriptPubKey
                )
            }

            val outputs = listOf(MeowcoinTransaction.TxOutput(toAddress, amountSatoshis))

            // Build and sign
            val signedTx = MeowcoinTransaction.buildTransaction(
                keyPair = keyPair,
                utxos = utxos,
                outputs = outputs,
                changeAddress = fromAddress,
                feeRate = feeRate
            )

            // Broadcast via Electrum
            val txId = electrumClient.broadcastTransaction(signedTx.txHex)

            // Record locally
            transactionDao.insertTransaction(
                TransactionEntity(
                    txId = txId,
                    walletAddress = fromAddress,
                    amount = -amountSatoshis,
                    fee = signedTx.size * feeRate,
                    toAddress = toAddress,
                    fromAddress = fromAddress,
                    status = "pending",
                    timestamp = System.currentTimeMillis()
                )
            )

            // Mark spent UTXOs
            utxoEntities.forEach { utxoDao.markSpent(it.id) }

            Result.success(txId)
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ═══════════════════════════════════════════
    //  Sync from Electrum Servers
    // ═══════════════════════════════════════════

    /**
     * Full refresh of wallet data from the Electrum server.
     * Fetches UTXOs and transaction history.
     */
    suspend fun refreshWalletData(address: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val scriptHash = electrumClient.addressToScriptHash(address)
            Log.d(TAG, "Refreshing $address (scriptHash: $scriptHash)")

            // ── Fetch UTXOs ──
            val unspent = electrumClient.listUnspent(scriptHash)
            val utxoEntities = unspent.map { utxo ->
                val hash160 = MeowcoinAddress.toHash160(address)
                val scriptPubKey = buildP2PKHScriptHex(hash160)

                UtxoEntity(
                    id = "${utxo.txHash}:${utxo.txPos}",
                    txHash = utxo.txHash,
                    outputIndex = utxo.txPos,
                    walletAddress = address,
                    value = utxo.value,
                    scriptPubKey = scriptPubKey,
                    confirmations = if (utxo.height > 0) 1 else 0,
                    isSpent = false
                )
            }
            utxoDao.deleteAllForWallet(address)
            if (utxoEntities.isNotEmpty()) {
                utxoDao.insertUtxos(utxoEntities)
            }

            // ── Fetch Transaction History ──
            val history = electrumClient.getHistory(scriptHash)
            for (item in history) {
                val existing = transactionDao.getTransaction(item.txHash)
                if (existing != null && existing.confirmations > 0) continue

                try {
                    val txJson = electrumClient.getTransaction(item.txHash, verbose = true)
                    val txObj = txJson.asJsonObject
                    val txEntity = parseTxFromElectrum(txObj, address, item.height)
                    transactionDao.insertTransaction(txEntity)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch tx ${item.txHash}: ${e.message}")
                }
            }

            Log.i(TAG, "Refresh complete: ${utxoEntities.size} UTXOs, ${history.size} txs")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Refresh failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Subscribe to real-time updates for an address.
     */
    suspend fun subscribeToAddress(address: String) {
        try {
            val scriptHash = electrumClient.addressToScriptHash(address)
            electrumClient.subscribeToAddress(scriptHash) { _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    refreshWalletData(address)
                }
            }
            Log.d(TAG, "Subscribed to updates for $address")
        } catch (e: Exception) {
            Log.w(TAG, "Subscribe failed: ${e.message}")
        }
    }

    /**
     * Subscribe to new block headers.
     */
    suspend fun subscribeToBlocks(onNewBlock: (Int) -> Unit) {
        try {
            val header = electrumClient.subscribeToHeaders { blockHeader ->
                onNewBlock(blockHeader.height)
            }
            Log.d(TAG, "Subscribed to blocks, current height: ${header.height}")
        } catch (e: Exception) {
            Log.w(TAG, "Block subscribe failed: ${e.message}")
        }
    }

    /**
     * Get estimated fee rate from network.
     */
    suspend fun getEstimatedFeeRate(blocks: Int = 2): Long {
        return try {
            val feePerKb = electrumClient.estimateFee(blocks)
            if (feePerKb > 0) {
                (feePerKb * 100_000_000 / 1000).toLong()
            } else {
                MeowcoinTransaction.DEFAULT_FEE_RATE
            }
        } catch (e: Exception) {
            MeowcoinTransaction.DEFAULT_FEE_RATE
        }
    }

    /**
     * SPV verification via Merkle proof.
     */
    suspend fun verifyTransaction(txId: String, blockHeight: Int): Boolean {
        return try {
            val proof = electrumClient.getMerkleProof(txId, blockHeight)
            proof.blockHeight == blockHeight
        } catch (e: Exception) {
            Log.w(TAG, "SPV verify failed for $txId: ${e.message}")
            false
        }
    }

    // ═══════════════════════════════════════════
    //  Delete
    // ═══════════════════════════════════════════

    suspend fun deleteWallet(address: String) = withContext(Dispatchers.IO) {
        secureKeyStore.removePrivateKey(address)
        walletDao.deleteWallet(WalletEntity(address = address))
        transactionDao.deleteAllForWallet(address)
        utxoDao.deleteAllForWallet(address)
    }

    // ═══════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════

    private fun parseTxFromElectrum(
        txObj: JsonObject,
        myAddress: String,
        height: Int
    ): TransactionEntity {
        val txId = txObj.get("txid").asString
        val time = txObj.get("time")?.asLong ?: (System.currentTimeMillis() / 1000)
        val confirmations = txObj.get("confirmations")?.asInt ?: 0

        val vin = txObj.getAsJsonArray("vin")
        val vout = txObj.getAsJsonArray("vout")

        var isSent = false
        var sentFromAmount = 0L
        vin?.forEach { input ->
            val obj = input.asJsonObject
            val addr = obj.get("address")?.asString
                ?: obj.getAsJsonObject("scriptSig")?.get("address")?.asString
            if (addr == myAddress) {
                isSent = true
                sentFromAmount += (obj.get("valueSat")?.asLong
                    ?: ((obj.get("value")?.asDouble ?: 0.0) * 100_000_000).toLong())
            }
        }

        var receivedAmount = 0L
        var sentToAddress = ""
        vout?.forEach { output ->
            val obj = output.asJsonObject
            val scriptPubKey = obj.getAsJsonObject("scriptPubKey")
            val addresses = scriptPubKey?.getAsJsonArray("addresses")
            val addr = addresses?.firstOrNull()?.asString
            val valueSat = ((obj.get("value")?.asString?.toDoubleOrNull()
                ?: 0.0) * 100_000_000).toLong()

            if (addr == myAddress) {
                receivedAmount += valueSat
            } else if (addr != null && sentToAddress.isEmpty()) {
                sentToAddress = addr
            }
        }

        val amount = if (isSent) {
            -(sentFromAmount - receivedAmount)
        } else {
            receivedAmount
        }

        val fromAddress = if (isSent) myAddress else {
            vin?.firstOrNull()?.asJsonObject?.get("address")?.asString ?: ""
        }

        return TransactionEntity(
            txId = txId,
            walletAddress = myAddress,
            amount = amount,
            fee = 0,
            toAddress = if (isSent) sentToAddress else myAddress,
            fromAddress = fromAddress,
            confirmations = confirmations,
            blockHeight = height,
            timestamp = time * 1000,
            status = if (confirmations > 0) "confirmed" else "pending"
        )
    }

    private fun buildP2PKHScriptHex(hash160: ByteArray): String {
        val script = byteArrayOf(
            0x76.toByte(), 0xA9.toByte(), 0x14.toByte()
        ) + hash160 + byteArrayOf(
            0x88.toByte(), 0xAC.toByte()
        )
        return script.joinToString("") { "%02x".format(it) }
    }
}
