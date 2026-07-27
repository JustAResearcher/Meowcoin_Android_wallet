package com.meowcoin.wallet.data.repository

import android.util.Log
import com.google.gson.JsonObject
import com.meowcoin.wallet.crypto.*
import com.meowcoin.wallet.data.local.*
import com.meowcoin.wallet.data.remote.ElectrumClient
import com.meowcoin.wallet.data.remote.PriceService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    data class ConsolidationPreview(
        val sourceAddress: String,
        val destinationAddress: String,
        val inputCount: Int,
        val totalInput: Long,
        val estimatedFee: Long,
        val outputAmount: Long,
        val remainingUtxoCount: Int
    )

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
    private val historySyncMutex = Mutex()

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
     * BIP44 address discovery for a restored HD wallet.
     *
     * Walks both the receiving (change=0) and change (change=1) chains, querying
     * Electrum for each derived address's history and stopping a chain once
     * [HD_GAP_LIMIT] consecutive addresses have no on-chain activity. Any used
     * address found is persisted with its private key so balances, history, and
     * future signing work.
     *
     * Requires an active Electrum connection. Idempotent — already-stored
     * addresses are skipped, not duplicated.
     *
     * @return Total number of newly-stored addresses
     */
    suspend fun discoverHdAddresses(): Int = withContext(Dispatchers.IO) {
        require(secureKeyStore.isHdWallet()) { "Not an HD wallet" }
        val mnemonic = secureKeyStore.getSeedPhrase()
            ?: throw IllegalStateException("No seed phrase found")
        val hdWallet = HdWallet.fromMnemonic(mnemonic)

        var totalDiscovered = 0
        var maxReceiving = -1
        var maxChange = -1

        for (chain in 0..1) {
            var gap = 0
            var index = 0
            while (gap < HD_GAP_LIMIT) {
                val keyPair = if (chain == 0) hdWallet.deriveReceivingKey(index)
                else hdWallet.deriveChangeKey(index)
                val address = keyPair.toAddress()

                val hasHistory = try {
                    val scriptHash = electrumClient.addressToScriptHash(address)
                    electrumClient.getHistory(scriptHash).isNotEmpty()
                } catch (e: Exception) {
                    Log.w(TAG, "Discovery history query failed for $chain/$index: ${e.message}")
                    false
                }

                if (hasHistory) {
                    gap = 0
                    if (chain == 0) maxReceiving = index else maxChange = index

                    if (secureKeyStore.getPrivateKey(address) == null) {
                        secureKeyStore.storePrivateKey(address, keyPair.privateKeyHex())
                        walletDao.insertWallet(
                            WalletEntity(
                                address = address,
                                label = if (chain == 0) "Address #${index + 1}" else "Change #${index + 1}",
                                createdAt = System.currentTimeMillis(),
                                isActive = true,
                                derivationPath = "m/44'/1669'/0'/$chain/$index",
                                derivationIndex = index,
                                isChange = chain == 1
                            )
                        )
                        totalDiscovered++
                        Log.i(TAG, "Discovered $address at m/44'/1669'/0'/$chain/$index")
                    }
                } else {
                    gap++
                }
                index++
            }
        }

        // Advance next-derivation pointers past the discovered range so future
        // "New Address" / change-output derivations don't collide with used ones.
        if (maxReceiving >= 0) {
            val next = maxReceiving + 1
            if (secureKeyStore.getNextReceivingIndex() < next) {
                secureKeyStore.storeNextReceivingIndex(next)
            }
        }
        if (maxChange >= 0) {
            val next = maxChange + 1
            if (secureKeyStore.getNextChangeIndex() < next) {
                secureKeyStore.storeNextChangeIndex(next)
            }
        }

        Log.i(TAG, "Discovery done: +$totalDiscovered addresses (recv max=$maxReceiving, change max=$maxChange)")
        totalDiscovered
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

    suspend fun getConsolidationPreview(): Result<ConsolidationPreview> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(selectConsolidationBatch().preview)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun consolidateUtxos(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val batch = selectConsolidationBatch()
            val privateKey = secureKeyStore.getPrivateKey(batch.preview.sourceAddress)
                ?: throw IllegalStateException("Private key not found for consolidation address")
            val keyPair = MeowcoinKeyPair.fromPrivateKey(privateKey)
            val inputs = batch.utxos.map { utxo ->
                MeowcoinTransaction.UTXO(
                    txHash = utxo.txHash,
                    outputIndex = utxo.outputIndex,
                    value = utxo.value,
                    scriptPubKey = utxo.scriptPubKey
                )
            }
            val signedTx = MeowcoinTransaction.buildConsolidationTransaction(
                keyPair = keyPair,
                utxos = inputs,
                destinationAddress = batch.preview.destinationAddress
            )
            val txId = electrumClient.broadcastTransaction(signedTx.txHex)

            transactionDao.insertTransaction(
                TransactionEntity(
                    txId = txId,
                    walletAddress = batch.preview.sourceAddress,
                    amount = -batch.preview.estimatedFee,
                    fee = batch.preview.estimatedFee,
                    toAddress = batch.preview.destinationAddress,
                    fromAddress = batch.preview.sourceAddress,
                    status = "pending",
                    timestamp = System.currentTimeMillis()
                )
            )
            batch.utxos.forEach { utxoDao.markSpent(it.id) }
            Result.success(txId)
        } catch (e: Exception) {
            Log.e(TAG, "Consolidation failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private data class ConsolidationBatch(
        val preview: ConsolidationPreview,
        val utxos: List<UtxoEntity>
    )

    private suspend fun selectConsolidationBatch(): ConsolidationBatch {
        val addresses = walletDao.getActiveAddresses()
        val allUtxos = utxoDao.getUnspentUtxosForAddresses(addresses)
            .filter { it.confirmations > 0 }
        val destinationAddress = secureKeyStore.getPrimaryAddress()
            ?: throw IllegalStateException("Wallet address not found")

        val candidates = allUtxos
            .groupBy { it.walletAddress }
            .filterValues { it.size >= 2 }
            .mapNotNull { (sourceAddress, addressUtxos) ->
                val batch = addressUtxos
                    .sortedBy { it.value }
                    .take(MeowcoinTransaction.MAX_TX_INPUTS)
                val totalInput = batch.sumOf { it.value }
                val estimatedFee = MeowcoinTransaction.estimateFee(batch.size, 1)
                val outputAmount = totalInput - estimatedFee
                if (outputAmount <= MeowcoinTransaction.DUST_THRESHOLD) {
                    null
                } else {
                    ConsolidationBatch(
                        preview = ConsolidationPreview(
                            sourceAddress = sourceAddress,
                            destinationAddress = destinationAddress,
                            inputCount = batch.size,
                            totalInput = totalInput,
                            estimatedFee = estimatedFee,
                            outputAmount = outputAmount,
                            remainingUtxoCount = allUtxos.size - batch.size + 1
                        ),
                        utxos = batch
                    )
                }
            }

        return candidates.maxByOrNull { it.preview.inputCount }
            ?: throw IllegalStateException(
                if (allUtxos.size < 2) {
                    "Nothing to consolidate yet. At least two confirmed UTXOs are required."
                } else {
                    "No same-address UTXO batch is large enough to cover the network fee."
                }
            )
    }

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
            val utxoCount = refreshUtxos(address)
            val historyCount = refreshHistory(address)
            Log.i(TAG, "Refresh complete: $utxoCount UTXOs, $historyCount txs")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Refresh failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Refresh spendable coins without waiting for transaction-history parsing.
     *
     * History parsing can require several Electrum lookups per transaction. Keeping
     * it separate lets large wallets reach the usable home screen as soon as their
     * UTXOs and balance are current.
     */
    suspend fun refreshWalletBalance(address: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val utxoCount = refreshUtxos(address)
            Log.i(TAG, "Balance refresh complete: $utxoCount UTXOs")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Balance refresh failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun refreshWalletHistory(address: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val historyCount = refreshHistory(address)
            Log.i(TAG, "History refresh complete: $historyCount txs")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "History refresh failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun refreshAllBalances(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            for (address in walletDao.getActiveAddresses()) {
                refreshWalletBalance(address).getOrThrow()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Refresh all balances failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun refreshAllHistories(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            for (address in walletDao.getActiveAddresses()) {
                refreshWalletHistory(address).getOrThrow()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Refresh all histories failed: ${e.message}", e)
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
                refreshWalletData(addr).getOrThrow()
            }
            // Also fetch fiat price
            try { fetchFiatPrice() } catch (_: Exception) {}
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Refresh all failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun refreshUtxos(address: String): Int {
        val scriptHash = electrumClient.addressToScriptHash(address)
        Log.d(TAG, "Refreshing balance for $address (scriptHash: $scriptHash)")
        val unspent = electrumClient.listUnspent(scriptHash)
        val hash160 = MeowcoinAddress.toHash160(address)
        val scriptPubKey = buildP2PKHScriptHex(hash160)
        val utxoEntities = unspent.map { utxo ->
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
        return utxoEntities.size
    }

    private suspend fun refreshHistory(address: String): Int = historySyncMutex.withLock {
        val scriptHash = electrumClient.addressToScriptHash(address)
        val myAddresses = (walletDao.getActiveAddresses() + address).toSet()
        val prevTxCache = mutableMapOf<String, JsonObject>()
        val history = electrumClient.getHistory(scriptHash)

        for (item in history) {
            val existing = transactionDao.getTransaction(item.txHash)
            if (existing != null && existing.confirmations > 0) continue

            try {
                val txJson = electrumClient.getTransaction(item.txHash, verbose = true)
                val txObj = txJson.asJsonObject
                val txEntity = parseTxFromElectrum(
                    txObj,
                    address,
                    myAddresses,
                    item.height,
                    prevTxCache
                )
                transactionDao.insertTransaction(txEntity)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch tx ${item.txHash}: ${e.message}")
            }
        }
        history.size
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
            Log.w(TAG, "Subscribe failed: ${e.message}", e)
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
            Log.w(TAG, "Block subscribe failed: ${e.message}", e)
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

    private suspend fun parseTxFromElectrum(
        txObj: JsonObject,
        walletAddress: String,
        myAddresses: Set<String>,
        height: Int,
        prevTxCache: MutableMap<String, JsonObject>
    ): TransactionEntity {
        val txId = txObj.get("txid").asString
        val time = txObj.get("time")?.asLong ?: (System.currentTimeMillis() / 1000)
        val confirmations = txObj.get("confirmations")?.asInt ?: 0

        val vin = txObj.getAsJsonArray("vin")
        val vout = txObj.getAsJsonArray("vout")

        // Standard electrum/electrs servers do NOT enrich vin entries with the
        // previous output's address/value — those fields live in the prev tx.
        // Resolve each input by fetching the prev tx (cached) and reading vout[n].
        var isSent = false
        var sentFromAmount = 0L
        var firstInputAddress = ""

        vin?.forEach { input ->
            val obj = input.asJsonObject
            val prevTxid = obj.get("txid")?.asString ?: return@forEach  // coinbase
            val prevVoutIdx = obj.get("vout")?.asInt ?: return@forEach

            val prevTxObj = try {
                prevTxCache.getOrPut(prevTxid) {
                    electrumClient.getTransaction(prevTxid, verbose = true).asJsonObject
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch prev tx $prevTxid: ${e.message}")
                return@forEach
            }

            val prevVouts = prevTxObj.getAsJsonArray("vout") ?: return@forEach
            if (prevVoutIdx < 0 || prevVoutIdx >= prevVouts.size()) return@forEach
            val prevOut = prevVouts.get(prevVoutIdx).asJsonObject
            val prevAddr = extractAddress(prevOut)
            val prevValueSat = extractValueSat(prevOut)

            if (prevAddr != null && firstInputAddress.isEmpty()) {
                firstInputAddress = prevAddr
            }
            if (prevAddr != null && prevAddr in myAddresses) {
                isSent = true
                sentFromAmount += prevValueSat
            }
        }

        var receivedAmount = 0L
        var sentToAddress = ""
        vout?.forEach { output ->
            val obj = output.asJsonObject
            val addr = extractAddress(obj)
            val valueSat = extractValueSat(obj)

            if (addr != null && addr in myAddresses) {
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

        val fromAddress = if (isSent) walletAddress else firstInputAddress

        return TransactionEntity(
            txId = txId,
            walletAddress = walletAddress,
            amount = amount,
            fee = 0,
            toAddress = if (isSent) sentToAddress else walletAddress,
            fromAddress = fromAddress,
            confirmations = confirmations,
            blockHeight = height,
            timestamp = time * 1000,
            status = if (confirmations > 0) "confirmed" else "pending"
        )
    }

    /**
     * Read the destination address from a vout object. Supports both the modern
     * Bitcoin Core 22+ singular `scriptPubKey.address` field and the legacy
     * plural `scriptPubKey.addresses` array. Returns null for non-standard
     * outputs (OP_RETURN, bare multisig without a representative address, etc).
     */
    private fun extractAddress(voutObj: JsonObject): String? {
        val scriptPubKey = voutObj.getAsJsonObject("scriptPubKey") ?: return null
        scriptPubKey.get("address")?.let {
            if (!it.isJsonNull) return it.asString
        }
        return scriptPubKey.getAsJsonArray("addresses")?.firstOrNull()?.asString
    }

    /**
     * Read the satoshi value from a vout object. Prefers integer `valueSat`
     * when present, otherwise converts decimal `value` (MEWC) to satoshis.
     */
    private fun extractValueSat(voutObj: JsonObject): Long {
        voutObj.get("valueSat")?.let {
            if (!it.isJsonNull) return it.asLong
        }
        val valEl = voutObj.get("value") ?: return 0L
        if (valEl.isJsonNull) return 0L
        val asDouble = try {
            valEl.asDouble
        } catch (_: Exception) {
            valEl.asString?.toDoubleOrNull() ?: 0.0
        }
        return (asDouble * 100_000_000).toLong()
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
