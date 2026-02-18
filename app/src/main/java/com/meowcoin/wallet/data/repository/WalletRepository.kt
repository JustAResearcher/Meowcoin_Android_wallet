package com.meowcoin.wallet.data.repository

import android.util.Log
import com.google.gson.JsonObject
import com.meowcoin.wallet.crypto.*
import com.meowcoin.wallet.data.local.*
import com.meowcoin.wallet.data.remote.ElectrumClient
import com.meowcoin.wallet.data.remote.PriceService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Main wallet repository — light client powered by Electrum servers.
 *
 * Supports both legacy single-key and HD (BIP39/BIP44) wallets.
 * All blockchain data is fetched via the Stratum (Electrum) protocol.
 */
class WalletRepository(
    private val database: WalletDatabase,
    private val secureKeyStore: SecureKeyStore,
    val electrumClient: ElectrumClient = ElectrumClient()
) {
    companion object {
        private const val TAG = "WalletRepository"
        private const val HD_GAP_LIMIT = 20  // BIP44 gap limit

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
    private val assetDao = database.assetDao()

    // Connection state exposed from Electrum client
    val connectionState = electrumClient.connectionState
    val serverInfo = electrumClient.serverInfo

    // ═══════════════════════════════════════════
    //  Connection
    // ═══════════════════════════════════════════

    suspend fun connectToNetwork(): Boolean {
        return electrumClient.connect()
    }

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

    fun getAllWallets(): Flow<List<WalletEntity>> = walletDao.getAllWallets()

    /**
     * Create an HD wallet from a new BIP39 mnemonic.
     * Returns the mnemonic words for the user to back up.
     */
    suspend fun createHdWallet(
        wordCount: Int = 12,
        label: String = "Main Wallet"
    ): String = withContext(Dispatchers.IO) {
        val mnemonic = Bip39.generateMnemonic(wordCount)
        importHdWallet(mnemonic, label)
        mnemonic
    }

    /**
     * Import / restore an HD wallet from a BIP39 mnemonic.
     * Derives the first receiving address and stores everything securely.
     */
    suspend fun importHdWallet(
        mnemonic: String,
        label: String = "HD Wallet"
    ): String = withContext(Dispatchers.IO) {
        require(Bip39.validateMnemonic(mnemonic)) { "Invalid mnemonic phrase" }

        val hdWallet = HdWallet.fromMnemonic(mnemonic)

        // Store seed phrase and mark as HD
        secureKeyStore.storeSeedPhrase(mnemonic)
        secureKeyStore.setIsHdWallet(true)
        secureKeyStore.storeNextReceivingIndex(1) // we derive index 0 now
        secureKeyStore.storeNextChangeIndex(0)

        // Derive first receiving address (m/44'/1669'/0'/0/0)
        val keyPair = hdWallet.deriveReceivingKey(0)
        val address = keyPair.toAddress()

        // Store private key and primary address
        secureKeyStore.storePrivateKey(address, keyPair.privateKeyHex())
        secureKeyStore.storePrimaryAddress(address)

        walletDao.insertWallet(
            WalletEntity(
                address = address,
                label = label,
                createdAt = System.currentTimeMillis(),
                isActive = true,
                derivationPath = "m/44'/1669'/0'/0/0",
                derivationIndex = 0,
                isChange = false
            )
        )

        address
    }

    /**
     * Derive and add the next receiving address to the HD wallet.
     */
    suspend fun deriveNextAddress(label: String = ""): String = withContext(Dispatchers.IO) {
        require(secureKeyStore.isHdWallet()) { "Not an HD wallet" }

        val mnemonic = secureKeyStore.getSeedPhrase()
            ?: throw IllegalStateException("No seed phrase found")
        val hdWallet = HdWallet.fromMnemonic(mnemonic)

        val index = secureKeyStore.getNextReceivingIndex()
        val keyPair = hdWallet.deriveReceivingKey(index)
        val address = keyPair.toAddress()

        secureKeyStore.storePrivateKey(address, keyPair.privateKeyHex())
        secureKeyStore.storeNextReceivingIndex(index + 1)

        val addressLabel = label.ifEmpty { "Address #${index + 1}" }
        walletDao.insertWallet(
            WalletEntity(
                address = address,
                label = addressLabel,
                createdAt = System.currentTimeMillis(),
                isActive = true,
                derivationPath = "m/44'/1669'/0'/0/$index",
                derivationIndex = index,
                isChange = false
            )
        )

        address
    }

    /**
     * Legacy: create a single random key wallet (non-HD).
     */
    suspend fun createWallet(label: String = "Main Wallet"): String = withContext(Dispatchers.IO) {
        val keyPair = MeowcoinKeyPair.generate()
        val address = keyPair.toAddress()

        secureKeyStore.storePrivateKey(address, keyPair.privateKeyHex())
        secureKeyStore.storePrimaryAddress(address)
        secureKeyStore.setIsHdWallet(false)

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
            secureKeyStore.setIsHdWallet(false)

            walletDao.insertWallet(
                WalletEntity(address = address, label = label, createdAt = System.currentTimeMillis())
            )

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
        secureKeyStore.setIsHdWallet(false)

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

    fun isHdWallet(): Boolean = secureKeyStore.isHdWallet()

    fun getSeedPhrase(): String? = secureKeyStore.getSeedPhrase()

    // ═══════════════════════════════════════════
    //  Balance
    // ═══════════════════════════════════════════

    fun getBalance(address: String): Flow<Long?> = utxoDao.getBalance(address)

    fun getBalanceMEWC(address: String): Flow<String> =
        utxoDao.getBalance(address).map { formatMEWC(it ?: 0) }

    /**
     * Get total balance across all active wallet addresses (HD or single).
     */
    fun getTotalBalance(): Flow<Long?> = flow {
        val addresses = walletDao.getActiveAddresses()
        emitAll(utxoDao.getTotalBalance(addresses))
    }

    fun getTotalBalanceMEWC(): Flow<String> = getTotalBalance().map { formatMEWC(it ?: 0) }

    // ═══════════════════════════════════════════
    //  Fiat Conversion
    // ═══════════════════════════════════════════

    suspend fun fetchFiatPrice(): PriceService.PriceData {
        return PriceService.fetchPrice()
    }

    fun getFiatBalance(satoshis: Long, currency: String = "USD"): String {
        return PriceService.formatFiat(satoshis, currency)
    }

    // ═══════════════════════════════════════════
    //  Transactions
    // ═══════════════════════════════════════════

    fun getTransactions(address: String): Flow<List<TransactionEntity>> =
        transactionDao.getTransactionsForWallet(address)

    fun getRecentTransactions(address: String, limit: Int = 20): Flow<List<TransactionEntity>> =
        transactionDao.getRecentTransactions(address, limit)

    /**
     * Get transactions across all active addresses.
     */
    fun getAllTransactions(): Flow<List<TransactionEntity>> = flow {
        val addresses = walletDao.getActiveAddresses()
        emitAll(transactionDao.getTransactionsForAddresses(addresses))
    }

    // ═══════════════════════════════════════════
    //  Assets
    // ═══════════════════════════════════════════

    fun getAssets(): Flow<List<AssetEntity>> = flow {
        val addresses = walletDao.getActiveAddresses()
        emitAll(assetDao.getAssetsForAddresses(addresses))
    }

    fun getAssetsForAddress(address: String): Flow<List<AssetEntity>> =
        assetDao.getAssetsForWallet(address)

    // ═══════════════════════════════════════════
    //  UTXOs
    // ═══════════════════════════════════════════

    fun getUnspentUtxos(address: String): Flow<List<UtxoEntity>> =
        utxoDao.getUnspentUtxos(address)

    // ═══════════════════════════════════════════
    //  Send Transaction
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

            // For HD wallets, gather UTXOs across all addresses
            val utxoEntities = if (secureKeyStore.isHdWallet()) {
                val addresses = walletDao.getActiveAddresses()
                utxoDao.getUnspentUtxosForAddresses(addresses)
            } else {
                utxoDao.getUnspentUtxosSync(fromAddress)
            }
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

            // Use a change address for HD wallets
            val changeAddress = if (secureKeyStore.isHdWallet()) {
                getOrDeriveChangeAddress()
            } else {
                fromAddress
            }

            val signedTx = MeowcoinTransaction.buildTransaction(
                keyPair = keyPair,
                utxos = utxos,
                outputs = outputs,
                changeAddress = changeAddress,
                feeRate = feeRate
            )

            val txId = electrumClient.broadcastTransaction(signedTx.txHex)

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

            utxoEntities.forEach { utxoDao.markSpent(it.id) }

            Result.success(txId)
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Get or derive an HD change address.
     */
    private suspend fun getOrDeriveChangeAddress(): String {
        val mnemonic = secureKeyStore.getSeedPhrase() ?: throw IllegalStateException("No seed")
        val hdWallet = HdWallet.fromMnemonic(mnemonic)
        val index = secureKeyStore.getNextChangeIndex()
        val keyPair = hdWallet.deriveChangeKey(index)
        val address = keyPair.toAddress()

        // Store key if not already stored
        if (secureKeyStore.getPrivateKey(address) == null) {
            secureKeyStore.storePrivateKey(address, keyPair.privateKeyHex())
            secureKeyStore.storeNextChangeIndex(index + 1)
            walletDao.insertWallet(
                WalletEntity(
                    address = address,
                    label = "Change #${index + 1}",
                    createdAt = System.currentTimeMillis(),
                    isActive = true,
                    derivationPath = "m/44'/1669'/0'/1/$index",
                    derivationIndex = index,
                    isChange = true
                )
            )
        }
        return address
    }

    // ═══════════════════════════════════════════
    //  Sync from Electrum
    // ═══════════════════════════════════════════

    /**
     * Refresh a single address.
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
     * Refresh all active addresses (for HD wallets with multiple addresses).
     */
    suspend fun refreshAllAddresses(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val addresses = walletDao.getActiveAddresses()
            for (addr in addresses) {
                refreshWalletData(addr)
            }
            // Also fetch fiat price
            try { fetchFiatPrice() } catch (_: Exception) {}
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Refresh all failed: ${e.message}", e)
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
     * Subscribe to all active wallet addresses.
     */
    suspend fun subscribeToAllAddresses() {
        val addresses = walletDao.getActiveAddresses()
        for (addr in addresses) {
            subscribeToAddress(addr)
        }
    }

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
    //  Biometric
    // ═══════════════════════════════════════════

    fun isBiometricEnabled(): Boolean = secureKeyStore.isBiometricEnabled()

    fun setBiometricEnabled(enabled: Boolean) = secureKeyStore.setBiometricEnabled(enabled)

    // ═══════════════════════════════════════════
    //  Delete
    // ═══════════════════════════════════════════

    suspend fun deleteWallet(address: String) = withContext(Dispatchers.IO) {
        secureKeyStore.removePrivateKey(address)
        walletDao.deleteWallet(WalletEntity(address = address))
        transactionDao.deleteAllForWallet(address)
        utxoDao.deleteAllForWallet(address)
        assetDao.deleteAllForWallet(address)
    }

    /**
     * Delete the entire wallet (all addresses, keys, seed).
     */
    suspend fun deleteAllWalletData() = withContext(Dispatchers.IO) {
        val addresses = walletDao.getActiveAddresses()
        for (addr in addresses) {
            secureKeyStore.removePrivateKey(addr)
        }
        walletDao.deleteAll()
        transactionDao.deleteAllForAddresses(addresses)
        utxoDao.deleteAllForAddresses(addresses)
        assetDao.deleteAllForAddresses(addresses)
        secureKeyStore.clearAll()
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
