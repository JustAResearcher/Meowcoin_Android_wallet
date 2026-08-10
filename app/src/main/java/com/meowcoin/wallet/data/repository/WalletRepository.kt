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
@OptIn(ExperimentalCoroutinesApi::class)
class WalletRepository(
    private val database: WalletDatabase,
    private val secureKeyStore: SecureKeyStore,
    val profile: CoinProfile = CoinRegistry.MEWC,
    val electrumClient: ElectrumClient = ElectrumClient(profile)
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

    data class PreparedSend(
        val coinId: String,
        val fromAddress: String,
        val toAddress: String,
        val destinationType: String,
        val paymentRequestSource: PaymentRequestSource,
        val amount: Long,
        val fee: Long,
        val signedTransaction: MeowcoinTransaction.SignedTransaction,
        val spentUtxoIds: List<String>,
        val changeIndex: Int? = null,
        val changePurpose: Int? = null
    )

    data class PreparedConsolidation(
        val coinId: String,
        val preview: ConsolidationPreview,
        val signedTransaction: MeowcoinTransaction.SignedTransaction,
        val spentUtxoIds: List<String>
    )

    companion object {
        private const val TAG = "WalletRepository"
        private const val HD_GAP_LIMIT = 20  // BIP44 gap limit

        fun formatMEWC(satoshis: Long): String {
            return AmountCodec.formatAtomic(satoshis, CoinRegistry.MEWC, trimTrailingZeros = false)
        }

        fun formatMEWCShort(satoshis: Long): String {
            return AmountCodec.fromAtomic(satoshis, CoinRegistry.MEWC)
                .setScale(2, java.math.RoundingMode.DOWN)
                .toPlainString()
        }

        fun parseMEWCtoSatoshis(mewcString: String): Long {
            return AmountCodec.parseAtomic(mewcString, CoinRegistry.MEWC)
        }
    }

    private val coinId = profile.id

    private val walletDao = database.walletDao()
    private val transactionDao = database.transactionDao()
    private val utxoDao = database.utxoDao()
    private val assetDao = database.assetDao()
    private val historySyncMutex = Mutex()
    private val repositoryJob = SupervisorJob()
    private val repositoryScope = CoroutineScope(Dispatchers.IO + repositoryJob)

    private fun storeOwnedKey(address: String, keyPair: MeowcoinKeyPair) {
        secureKeyStore.storePrivateKey(coinId, address, keyPair.privateKeyHex())
        secureKeyStore.storeKeyCompression(coinId, address, keyPair.isCompressed)
    }

    private fun loadOwnedKey(address: String): MeowcoinKeyPair? {
        val privateKey = secureKeyStore.getPrivateKey(coinId, address) ?: return null
        if (secureKeyStore.hasKeyCompressionMetadata(coinId, address)) {
            val keyPair = MeowcoinKeyPair.fromPrivateKey(
                privateKey,
                profile,
                compressed = secureKeyStore.isKeyCompressed(coinId, address)
            )
            require(keyMatchesAddress(keyPair, address)) {
                "Stored private key does not match $address"
            }
            return keyPair
        }

        val compressed = MeowcoinKeyPair.fromPrivateKey(privateKey, profile, compressed = true)
        val keyPair = if (keyMatchesAddress(compressed, address)) {
            compressed
        } else {
            val uncompressed = MeowcoinKeyPair.fromPrivateKey(
                privateKey,
                profile,
                compressed = false
            )
            require(keyMatchesAddress(uncompressed, address)) {
                "Stored private key does not match $address"
            }
            uncompressed
        }
        secureKeyStore.storeKeyCompression(coinId, address, keyPair.isCompressed)
        return keyPair
    }

    private fun keyMatchesAddress(keyPair: MeowcoinKeyPair, address: String): Boolean {
        return when (MeowcoinAddress.parse(address, profile)?.type) {
            MeowcoinAddress.Type.P2PKH -> keyPair.toAddress(profile) == address
            MeowcoinAddress.Type.P2WPKH ->
                keyPair.isCompressed && keyPair.toP2WPKHAddress(profile) == address
            else -> false
        }
    }

    private fun supportsNativeSegwitReceiving(): Boolean =
        profile.id == CoinRegistry.MEWC.id || profile.id == CoinRegistry.LTC.id

    private fun canUseNativeSegwitReceiving(): Boolean {
        if (!supportsNativeSegwitReceiving()) return false
        if (secureKeyStore.getSeedDerivationVersion() != Bip39.LEGACY_DERIVATION_VERSION) {
            return true
        }
        val seed = secureKeyStore.getSeedPhrase() ?: return true
        val legacyNormalized = seed.trim().lowercase(java.util.Locale.ROOT)
        return legacyNormalized == Bip39.canonicalizeMnemonic(seed)
    }

    private fun defaultHdPurpose(): Int = if (canUseNativeSegwitReceiving()) {
        Bip32.BIP84_PURPOSE
    } else {
        Bip32.BIP44_PURPOSE
    }

    private fun addressForPurpose(keyPair: MeowcoinKeyPair, purpose: Int): String = when (purpose) {
        Bip32.BIP44_PURPOSE -> keyPair.toAddress(profile)
        Bip32.BIP84_PURPOSE -> keyPair.toP2WPKHAddress(profile)
        else -> throw IllegalArgumentException("Unsupported derivation purpose: $purpose")
    }

    private fun addressTypeForPurpose(purpose: Int): String = when (purpose) {
        Bip32.BIP44_PURPOSE -> ADDRESS_TYPE_P2PKH
        Bip32.BIP84_PURPOSE -> ADDRESS_TYPE_P2WPKH
        else -> throw IllegalArgumentException("Unsupported derivation purpose: $purpose")
    }

    private fun derivationPath(purpose: Int, chain: Int, index: Int): String =
        Bip32.derivationPath(
            profile = profile,
            purpose = purpose,
            account = 0,
            change = chain,
            addressIndex = index
        )

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

    /** Permanently stop this repository instance and wait for notification refreshes to finish. */
    suspend fun close() {
        electrumClient.disconnect()
        repositoryJob.cancelAndJoin()
    }

    /** Non-blocking lifecycle shutdown for Activity/ViewModel destruction. */
    fun shutdown() {
        electrumClient.disconnect()
        repositoryJob.cancel()
    }

    // ═══════════════════════════════════════════
    //  Wallet Management
    // ═══════════════════════════════════════════

    fun hasWallet(): Boolean = secureKeyStore.hasWallet(coinId)

    fun getActiveWallet(): Flow<WalletEntity?> = walletDao.getActiveWallet(coinId)

    fun getAllWallets(): Flow<List<WalletEntity>> = walletDao.getAllWallets(coinId)

    /**
     * Create an HD wallet from a new BIP39 mnemonic.
     * Returns the mnemonic words for the user to back up.
     */
    suspend fun createHdWallet(
        wordCount: Int = 12,
        label: String = "Main Wallet"
    ): String = withContext(Dispatchers.IO) {
        val mnemonic = Bip39.generateMnemonic(wordCount)
        importHdWallet(
            mnemonic,
            label,
            mnemonicBackupRequired = true,
            hdDiscoveryPending = false
        )
        mnemonic
    }

    /**
     * Import / restore an HD wallet from a BIP39 mnemonic.
     * Derives the first receiving address and stores everything securely.
     */
    suspend fun importHdWallet(
        mnemonic: String,
        label: String = "HD Wallet",
        mnemonicBackupRequired: Boolean = false,
        hdDiscoveryPending: Boolean = true,
        seedDerivationVersion: Int = Bip39.CANONICAL_DERIVATION_VERSION
    ): String = withContext(Dispatchers.IO) {
        val storedMnemonic = if (seedDerivationVersion == Bip39.CANONICAL_DERIVATION_VERSION) {
            Bip39.canonicalizeMnemonic(mnemonic)
        } else {
            mnemonic
        }
        require(Bip39.validateMnemonic(storedMnemonic)) { "Invalid mnemonic phrase" }

        val hdWallet = HdWallet.fromMnemonic(
            storedMnemonic,
            profile,
            derivationVersion = seedDerivationVersion
        )

        // Store seed phrase and mark as HD
        secureKeyStore.storeSeedPhrase(
            storedMnemonic,
            mnemonicBackupRequired,
            seedDerivationVersion
        )
        secureKeyStore.setIsHdWallet(coinId, true)
        secureKeyStore.setHdDiscoveryPending(coinId, hdDiscoveryPending)
        secureKeyStore.storeNextReceivingIndex(coinId, 1) // we derive index 0 now
        secureKeyStore.storeNextChangeIndex(coinId, 0)
        if (canUseNativeSegwitReceiving()) {
            secureKeyStore.setBip84DiscoveryPending(coinId, hdDiscoveryPending)
            secureKeyStore.storeNextBip84ReceivingIndex(coinId, 0)
            secureKeyStore.storeNextBip84ChangeIndex(coinId, 0)
        }

        // Derive first receiving address (m/44'/1669'/0'/0/0)
        val keyPair = hdWallet.deriveReceivingKey(0)
        val address = keyPair.toAddress(profile)

        // Store private key and primary address
        storeOwnedKey(address, keyPair)
        secureKeyStore.storePrimaryAddress(coinId, address)

        walletDao.insertWallet(
            WalletEntity(
                address = address,
                label = label,
                createdAt = System.currentTimeMillis(),
                isActive = true,
                derivationPath = "m/44'/${profile.bip44CoinType}'/0'/0/0",
                derivationIndex = 0,
                isChange = false,
                addressType = ADDRESS_TYPE_P2PKH,
                derivationPurpose = Bip32.BIP44_PURPOSE,
                coinId = coinId
            )
        )

        if (canUseNativeSegwitReceiving()) {
            ensureNativeSegwitReceiveAddress(hdWallet, label)
        }

        address
    }

    /** Ensure index 0 exists for the default BIP84 receive branch and return its latest address. */
    private suspend fun ensureNativeSegwitReceiveAddress(
        hdWallet: HdWallet,
        label: String = "${profile.name} Wallet"
    ): String {
        require(canUseNativeSegwitReceiving()) {
            "Native SegWit receiving is not enabled for ${profile.ticker}"
        }
        val keyPair = hdWallet.deriveReceivingKey(0, Bip32.BIP84_PURPOSE)
        val address = addressForPurpose(keyPair, Bip32.BIP84_PURPOSE)
        if (secureKeyStore.getPrivateKey(coinId, address) == null) {
            storeOwnedKey(address, keyPair)
        }
        if (walletDao.getWalletByAddress(coinId, address) == null) {
            walletDao.insertWallet(
                WalletEntity(
                    address = address,
                    label = "$label (Native SegWit)",
                    createdAt = System.currentTimeMillis(),
                    isActive = true,
                    derivationPath = derivationPath(Bip32.BIP84_PURPOSE, 0, 0),
                    derivationIndex = 0,
                    isChange = false,
                    addressType = ADDRESS_TYPE_P2WPKH,
                    derivationPurpose = Bip32.BIP84_PURPOSE,
                    coinId = coinId
                )
            )
        }
        if (secureKeyStore.getNextBip84ReceivingIndex(coinId) < 1) {
            secureKeyStore.storeNextBip84ReceivingIndex(coinId, 1)
        }
        return walletDao.getActiveWalletsByPurposeSync(coinId, Bip32.BIP84_PURPOSE)
            .filterNot(WalletEntity::isChange)
            .maxByOrNull(WalletEntity::derivationIndex)
            ?.address
            ?: address
    }

    suspend fun getDefaultReceiveAddress(): String = withContext(Dispatchers.IO) {
        val primary = secureKeyStore.getPrimaryAddress(coinId)
            ?: throw IllegalStateException("Wallet address not found")
        if (!secureKeyStore.isHdWallet(coinId) || !canUseNativeSegwitReceiving()) {
            return@withContext primary
        }
        val mnemonic = secureKeyStore.getSeedPhrase()
            ?: throw IllegalStateException("No seed phrase found")
        val hdWallet = HdWallet.fromMnemonic(
            mnemonic,
            profile,
            derivationVersion = secureKeyStore.getSeedDerivationVersion()
        )
        ensureNativeSegwitReceiveAddress(hdWallet)
    }

    /**
     * Derive and add the next receiving address to the HD wallet.
     */
    suspend fun deriveNextAddress(label: String = ""): String = withContext(Dispatchers.IO) {
        require(secureKeyStore.isHdWallet(coinId)) { "Not an HD wallet" }

        val mnemonic = secureKeyStore.getSeedPhrase()
            ?: throw IllegalStateException("No seed phrase found")
        val hdWallet = HdWallet.fromMnemonic(
            mnemonic,
            profile,
            derivationVersion = secureKeyStore.getSeedDerivationVersion()
        )

        val purpose = defaultHdPurpose()
        val index = if (purpose == Bip32.BIP84_PURPOSE) {
            secureKeyStore.getNextBip84ReceivingIndex(coinId)
        } else {
            secureKeyStore.getNextReceivingIndex(coinId)
        }
        val keyPair = hdWallet.deriveReceivingKey(index, purpose)
        val address = addressForPurpose(keyPair, purpose)

        storeOwnedKey(address, keyPair)
        if (purpose == Bip32.BIP84_PURPOSE) {
            secureKeyStore.storeNextBip84ReceivingIndex(coinId, index + 1)
        } else {
            secureKeyStore.storeNextReceivingIndex(coinId, index + 1)
        }

        val addressLabel = label.ifEmpty { "Address #${index + 1}" }
        walletDao.insertWallet(
            WalletEntity(
                address = address,
                label = addressLabel,
                createdAt = System.currentTimeMillis(),
                isActive = true,
                derivationPath = derivationPath(purpose, 0, index),
                derivationIndex = index,
                isChange = false,
                addressType = addressTypeForPurpose(purpose),
                derivationPurpose = purpose,
                coinId = coinId
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
    suspend fun discoverHdAddresses(force: Boolean = false): Int = withContext(Dispatchers.IO) {
        require(secureKeyStore.isHdWallet(coinId)) { "Not an HD wallet" }
        val mnemonic = secureKeyStore.getSeedPhrase()
            ?: throw IllegalStateException("No seed phrase found")
        val hdWallet = HdWallet.fromMnemonic(
            mnemonic,
            profile,
            derivationVersion = secureKeyStore.getSeedDerivationVersion()
        )
        val storedAddresses = walletDao.getAllWalletsSync(coinId)
            .mapTo(mutableSetOf()) { it.address }

        val purposes = buildList {
            if (force || secureKeyStore.isHdDiscoveryPending(coinId)) {
                add(Bip32.BIP44_PURPOSE)
            }
            if (canUseNativeSegwitReceiving() &&
                (force || secureKeyStore.isBip84DiscoveryPending(coinId))
            ) {
                add(Bip32.BIP84_PURPOSE)
            }
        }
        if (purposes.isEmpty()) return@withContext 0

        var totalDiscovered = 0
        val maximumUsedIndex = mutableMapOf<Pair<Int, Int>, Int>()

        for (purpose in purposes) {
            for (chain in 0..1) {
                var gap = 0
                var index = 0
                while (gap < HD_GAP_LIMIT) {
                    val keyPair = if (chain == 0) {
                        hdWallet.deriveReceivingKey(index, purpose)
                    } else {
                        hdWallet.deriveChangeKey(index, purpose)
                    }
                    val address = addressForPurpose(keyPair, purpose)

                    val hasHistory = try {
                        val scriptHash = electrumClient.addressToScriptHash(address)
                        electrumClient.getHistory(scriptHash).isNotEmpty()
                    } catch (e: Exception) {
                        Log.w(
                            TAG,
                            "Discovery query failed for purpose=$purpose chain=$chain index=$index: " +
                                e.message
                        )
                        throw IllegalStateException(
                            "Address discovery stopped at m/$purpose'/${profile.bip44CoinType}'/" +
                                "0'/$chain/$index because the server query failed",
                            e
                        )
                    }

                    if (hasHistory) {
                        gap = 0
                        maximumUsedIndex[purpose to chain] = index

                        if (secureKeyStore.getPrivateKey(coinId, address) == null) {
                            storeOwnedKey(address, keyPair)
                        }
                        if (address !in storedAddresses) {
                            walletDao.insertWallet(
                                WalletEntity(
                                    address = address,
                                    label = when {
                                        purpose == Bip32.BIP84_PURPOSE && chain == 0 ->
                                            "Native SegWit #${index + 1}"
                                        purpose == Bip32.BIP84_PURPOSE ->
                                            "Native SegWit Change #${index + 1}"
                                        chain == 0 -> "Address #${index + 1}"
                                        else -> "Change #${index + 1}"
                                    },
                                    createdAt = System.currentTimeMillis(),
                                    isActive = true,
                                    derivationPath = derivationPath(purpose, chain, index),
                                    derivationIndex = index,
                                    isChange = chain == 1,
                                    addressType = addressTypeForPurpose(purpose),
                                    derivationPurpose = purpose,
                                    coinId = coinId
                                )
                            )
                            storedAddresses += address
                            totalDiscovered++
                            Log.i(TAG, "Discovered $address at ${derivationPath(purpose, chain, index)}")
                        }
                    } else {
                        gap++
                    }
                    index++
                }
            }
        }

        for (purpose in purposes) {
            maximumUsedIndex[purpose to 0]?.plus(1)?.let { next ->
                if (purpose == Bip32.BIP84_PURPOSE) {
                    if (secureKeyStore.getNextBip84ReceivingIndex(coinId) < next) {
                        secureKeyStore.storeNextBip84ReceivingIndex(coinId, next)
                    }
                } else if (secureKeyStore.getNextReceivingIndex(coinId) < next) {
                    secureKeyStore.storeNextReceivingIndex(coinId, next)
                }
            }
            maximumUsedIndex[purpose to 1]?.plus(1)?.let { next ->
                if (purpose == Bip32.BIP84_PURPOSE) {
                    if (secureKeyStore.getNextBip84ChangeIndex(coinId) < next) {
                        secureKeyStore.storeNextBip84ChangeIndex(coinId, next)
                    }
                } else if (secureKeyStore.getNextChangeIndex(coinId) < next) {
                    secureKeyStore.storeNextChangeIndex(coinId, next)
                }
            }
            if (purpose == Bip32.BIP84_PURPOSE) {
                secureKeyStore.setBip84DiscoveryPending(coinId, false)
            } else {
                secureKeyStore.setHdDiscoveryPending(coinId, false)
            }
        }
        Log.i(TAG, "Discovery done: +$totalDiscovered addresses for purposes=$purposes")
        totalDiscovered
    }

    /**
     * Legacy: create a single random key wallet (non-HD).
     */
    suspend fun createWallet(label: String = "Main Wallet"): String = withContext(Dispatchers.IO) {
        val keyPair = MeowcoinKeyPair.generate(profile)
        val address = keyPair.toAddress(profile)

        storeOwnedKey(address, keyPair)
        secureKeyStore.storePrimaryAddress(coinId, address)
        secureKeyStore.setIsHdWallet(coinId, false)

        walletDao.insertWallet(
            WalletEntity(
                address = address,
                label = label,
                createdAt = System.currentTimeMillis(),
                coinId = coinId
            )
        )

        address
    }

    suspend fun importWalletFromWIF(wif: String, label: String = "Imported Wallet"): String =
        withContext(Dispatchers.IO) {
            val keyPair = MeowcoinKeyPair.fromWIF(wif, profile)
            val address = keyPair.toAddress(profile)

            storeOwnedKey(address, keyPair)
            secureKeyStore.storePrimaryAddress(coinId, address)
            secureKeyStore.setIsHdWallet(coinId, false)

            walletDao.insertWallet(
                WalletEntity(
                    address = address,
                    label = label,
                    createdAt = System.currentTimeMillis(),
                    coinId = coinId
                )
            )

            refreshWalletData(address)
            address
        }

    suspend fun importWalletFromPrivateKey(
        privateKeyHex: String,
        label: String = "Imported Wallet"
    ): String = withContext(Dispatchers.IO) {
        val keyPair = MeowcoinKeyPair.fromPrivateKey(privateKeyHex, profile)
        val address = keyPair.toAddress(profile)

        storeOwnedKey(address, keyPair)
        secureKeyStore.storePrimaryAddress(coinId, address)
        secureKeyStore.setIsHdWallet(coinId, false)

        walletDao.insertWallet(
            WalletEntity(
                address = address,
                label = label,
                createdAt = System.currentTimeMillis(),
                coinId = coinId
            )
        )

        refreshWalletData(address)
        address
    }

    fun getWIF(address: String): String? {
        return loadOwnedKey(address)?.toWIF()
    }

    fun isHdWallet(): Boolean = secureKeyStore.isHdWallet(coinId)

    fun isHdDiscoveryPending(): Boolean =
        secureKeyStore.isHdDiscoveryPending(coinId) ||
            (canUseNativeSegwitReceiving() && secureKeyStore.isBip84DiscoveryPending(coinId))

    fun getSeedPhrase(): String? = secureKeyStore.getSeedPhrase()

    /** Lazily creates this coin's BIP44 account from the wallet's global seed. */
    suspend fun initializeFromStoredSeedIfNeeded(): String? = withContext(Dispatchers.IO) {
        secureKeyStore.getPrimaryAddress(coinId)?.let { address ->
            val isHd = secureKeyStore.isHdWallet(coinId)
            if (walletDao.getAllWalletsSync(coinId).none { it.address == address }) {
                walletDao.insertWallet(
                    WalletEntity(
                        address = address,
                        label = "${profile.name} Wallet",
                        derivationPath = if (isHd) {
                            "m/44'/${profile.bip44CoinType}'/0'/0/0"
                        } else {
                            ""
                        },
                        addressType = ADDRESS_TYPE_P2PKH,
                        derivationPurpose = Bip32.BIP44_PURPOSE,
                        coinId = coinId
                    )
                )
            }
            if (isHd && canUseNativeSegwitReceiving()) {
                val mnemonic = secureKeyStore.getSeedPhrase()
                    ?: throw IllegalStateException("No seed phrase found")
                val hdWallet = HdWallet.fromMnemonic(
                    mnemonic,
                    profile,
                    derivationVersion = secureKeyStore.getSeedDerivationVersion()
                )
                ensureNativeSegwitReceiveAddress(hdWallet)
            }
            return@withContext address
        }
        val mnemonic = secureKeyStore.getSeedPhrase() ?: return@withContext null
        importHdWallet(
            mnemonic = mnemonic,
            label = "${profile.name} Wallet",
            mnemonicBackupRequired = secureKeyStore.isMnemonicBackupRequired(),
            hdDiscoveryPending = true,
            seedDerivationVersion = secureKeyStore.getSeedDerivationVersion()
        )
    }

    // ═══════════════════════════════════════════
    //  Balance
    // ═══════════════════════════════════════════

    fun getBalance(address: String): Flow<Long?> = utxoDao.getBalance(coinId, address)

    fun getBalanceFormatted(address: String): Flow<String> =
        utxoDao.getBalance(coinId, address).map {
            AmountCodec.formatAtomic(it ?: 0, profile, trimTrailingZeros = false)
        }

    fun getBalanceMEWC(address: String): Flow<String> = getBalanceFormatted(address)

    /**
     * Get total balance across all active wallet addresses (HD or single).
     */
    fun getTotalBalance(): Flow<Long?> = walletDao.getAllWallets(coinId)
        .map { wallets -> wallets.filter { it.isActive }.map { it.address } }
        .distinctUntilChanged()
        .flatMapLatest { addresses ->
            if (addresses.isEmpty()) flowOf(0L)
            else utxoDao.getTotalBalance(coinId, addresses)
        }

    fun getTotalBalanceFormatted(): Flow<String> = getTotalBalance().map {
        AmountCodec.formatAtomic(it ?: 0, profile, trimTrailingZeros = false)
    }

    fun getTotalBalanceMEWC(): Flow<String> = getTotalBalanceFormatted()

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
        transactionDao.getTransactionsForWallet(coinId, address)

    fun getRecentTransactions(address: String, limit: Int = 20): Flow<List<TransactionEntity>> =
        transactionDao.getRecentTransactions(coinId, address, limit)

    /**
     * Get transactions across all active addresses.
     */
    fun getAllTransactions(): Flow<List<TransactionEntity>> = walletDao.getAllWallets(coinId)
        .map { wallets -> wallets.filter { it.isActive }.map { it.address } }
        .distinctUntilChanged()
        .flatMapLatest { addresses ->
            if (addresses.isEmpty()) flowOf(emptyList())
            else transactionDao.getTransactionsForAddresses(coinId, addresses)
        }

    // ═══════════════════════════════════════════
    //  Assets
    // ═══════════════════════════════════════════

    fun getAssets(): Flow<List<AssetEntity>> {
        if (coinId != CoinRegistry.MEWC.id) return flowOf(emptyList())
        return walletDao.getAllWallets(coinId)
            .map { wallets -> wallets.filter { it.isActive }.map { it.address } }
            .distinctUntilChanged()
            .flatMapLatest { addresses ->
                if (addresses.isEmpty()) flowOf(emptyList())
                else assetDao.getAssetsForAddresses(coinId, addresses)
            }
    }

    fun getAssetsForAddress(address: String): Flow<List<AssetEntity>> =
        if (coinId == CoinRegistry.MEWC.id) assetDao.getAssetsForWallet(coinId, address)
        else flowOf(emptyList())

    // ═══════════════════════════════════════════
    //  UTXOs
    // ═══════════════════════════════════════════

    fun getUnspentUtxos(address: String): Flow<List<UtxoEntity>> =
        utxoDao.getUnspentUtxos(coinId, address)

    suspend fun prepareConsolidation(): Result<PreparedConsolidation> = withContext(Dispatchers.IO) {
        try {
            val batch = selectConsolidationBatch()
            verifyPreviousOutputs(batch.utxos)
            val keyPair = loadOwnedKey(batch.preview.sourceAddress)
                ?: throw IllegalStateException("Private key not found for consolidation address")
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
                destinationAddress = batch.preview.destinationAddress,
                profile = profile
            )

            val exactPreview = batch.preview.copy(
                estimatedFee = signedTx.actualFee,
                outputAmount = batch.preview.totalInput - signedTx.actualFee
            )
            Result.success(
                PreparedConsolidation(
                    coinId = coinId,
                    preview = exactPreview,
                    signedTransaction = signedTx,
                    spentUtxoIds = batch.utxos.map { it.id }
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Consolidation preparation failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getConsolidationPreview(): Result<ConsolidationPreview> =
        prepareConsolidation().map { it.preview }

    suspend fun broadcastPreparedConsolidation(
        prepared: PreparedConsolidation
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            require(prepared.coinId == coinId) {
                "Prepared consolidation belongs to another coin"
            }
            val serverTxId = electrumClient.broadcastTransaction(
                prepared.signedTransaction.txHex
            )
            require(serverTxId.equals(prepared.signedTransaction.txId, ignoreCase = true)) {
                "Electrum server returned an unexpected transaction ID"
            }

            transactionDao.insertTransaction(
                TransactionEntity(
                    txId = prepared.signedTransaction.txId,
                    walletAddress = prepared.preview.sourceAddress,
                    amount = -prepared.preview.estimatedFee,
                    fee = prepared.preview.estimatedFee,
                    toAddress = prepared.preview.destinationAddress,
                    fromAddress = prepared.preview.sourceAddress,
                    status = "pending",
                    timestamp = System.currentTimeMillis(),
                    coinId = coinId
                )
            )
            prepared.spentUtxoIds.forEach { utxoDao.markSpent(coinId, it) }
            Result.success(prepared.signedTransaction.txId)
        } catch (e: Exception) {
            Log.e(TAG, "Consolidation broadcast failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun consolidateUtxos(): Result<String> {
        val prepared = prepareConsolidation().getOrElse { return Result.failure(it) }
        return broadcastPreparedConsolidation(prepared)
    }

    private data class ConsolidationBatch(
        val preview: ConsolidationPreview,
        val utxos: List<UtxoEntity>
    )

    private suspend fun selectConsolidationBatch(): ConsolidationBatch {
        val addresses = walletDao.getActiveAddresses(coinId)
        val allUtxos = utxoDao.getUnspentUtxosForAddresses(coinId, addresses)
            .filter { it.confirmations > 0 }
        val destinationAddress = getDefaultReceiveAddress()

        val candidates = allUtxos
            .groupBy { it.walletAddress }
            .filterValues { it.size >= 2 }
            .mapNotNull { (sourceAddress, addressUtxos) ->
                val batch = addressUtxos
                    .sortedBy { it.value }
                    .take(MeowcoinTransaction.MAX_TX_INPUTS)
                val totalInput = batch.sumOf { it.value }
                val ownerKey = loadOwnedKey(sourceAddress) ?: return@mapNotNull null
                val estimatedFee = MeowcoinTransaction.estimateFee(
                    spendableUtxos = batch.map { utxo ->
                        MeowcoinTransaction.SpendableUTXO(
                            utxo = MeowcoinTransaction.UTXO(
                                txHash = utxo.txHash,
                                outputIndex = utxo.outputIndex,
                                value = utxo.value,
                                scriptPubKey = utxo.scriptPubKey
                            ),
                            ownerKey = ownerKey
                        )
                    },
                    destinationAddress = destinationAddress,
                    profile = profile,
                    feeRate = profile.defaultFeeRate
                )
                val outputAmount = totalInput - estimatedFee
                if (outputAmount <= profile.dustThreshold) {
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

    suspend fun prepareTransaction(
        fromAddress: String,
        toAddress: String,
        amountSatoshis: Long,
        sendAll: Boolean = false,
        feeRate: Long = profile.defaultFeeRate
    ): Result<PreparedSend> = withContext(Dispatchers.IO) {
        try {
            val paymentRequest = PaymentUriCodec.parseSendTarget(toAddress, profile)
            require(paymentRequest.amountAtomic == null || !sendAll) {
                "MAX cannot be used with a payment request that specifies an amount"
            }
            require(
                paymentRequest.amountAtomic == null || paymentRequest.amountAtomic == amountSatoshis
            ) {
                "Entered amount does not match the payment request"
            }
            val destinationAddress = paymentRequest.address
            require(sendAll || amountSatoshis >= profile.dustThreshold) {
                "Amount below dust threshold"
            }

            // For HD wallets, gather UTXOs across all addresses
            val utxoEntities = if (secureKeyStore.isHdWallet(coinId)) {
                val addresses = walletDao.getActiveAddresses(coinId)
                utxoDao.getUnspentUtxosForAddresses(coinId, addresses)
            } else {
                utxoDao.getUnspentUtxosSync(coinId, fromAddress)
            }
            require(utxoEntities.isNotEmpty()) { "No spendable UTXOs" }

            val spendableUtxos = utxoEntities.map { entity ->
                val ownerKey = loadOwnedKey(entity.walletAddress)
                    ?: throw IllegalStateException(
                        "Missing private key for ${entity.walletAddress.take(12)}..."
                    )
                MeowcoinTransaction.SpendableUTXO(
                    utxo = MeowcoinTransaction.UTXO(
                        txHash = entity.txHash,
                        outputIndex = entity.outputIndex,
                        value = entity.value,
                        scriptPubKey = entity.scriptPubKey
                    ),
                    ownerKey = ownerKey
                )
            }

            var pendingChange: PendingChangeAddress? = null
            val signedTx = if (sendAll) {
                MeowcoinTransaction.buildSendAllTransaction(
                    spendableUtxos = spendableUtxos,
                    destinationAddress = destinationAddress,
                    profile = profile,
                    feeRate = feeRate
                )
            } else {
                val changeAddress = if (secureKeyStore.isHdWallet(coinId)) {
                    getOrDerivePendingChangeAddress().also { pendingChange = it }.address
                } else {
                    fromAddress
                }
                MeowcoinTransaction.buildTransaction(
                    spendableUtxos = spendableUtxos,
                    outputs = listOf(
                        MeowcoinTransaction.TxOutput(destinationAddress, amountSatoshis)
                    ),
                    changeAddress = changeAddress,
                    profile = profile,
                    feeRate = feeRate
                )
            }

            val selected = signedTx.selectedOutpoints.toSet()
            val spentEntities = utxoEntities.filter {
                MeowcoinTransaction.OutPoint(it.txHash, it.outputIndex) in selected
            }
            verifyPreviousOutputs(spentEntities)
            val sentAmount = if (sendAll) {
                spentEntities.sumOf { it.value } - signedTx.actualFee
            } else {
                amountSatoshis
            }
            val hasChangeOutput = !sendAll && pendingChange != null &&
                spentEntities.sumOf { it.value } - amountSatoshis - signedTx.actualFee > 0L

            Result.success(
                PreparedSend(
                    coinId = coinId,
                    fromAddress = fromAddress,
                    toAddress = destinationAddress,
                    destinationType = MeowcoinAddress.parse(destinationAddress, profile)
                        ?.type?.name
                        ?: throw IllegalStateException("Destination address became invalid"),
                    paymentRequestSource = paymentRequest.source,
                    amount = sentAmount,
                    fee = signedTx.actualFee,
                    signedTransaction = signedTx,
                    spentUtxoIds = spentEntities.map { it.id },
                    changeIndex = pendingChange?.index?.takeIf { hasChangeOutput },
                    changePurpose = pendingChange?.purpose?.takeIf { hasChangeOutput }
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Send preparation failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun verifyPreviousOutputs(utxos: List<UtxoEntity>) {
        val rawTransactionCache = mutableMapOf<String, String>()
        for (utxo in utxos) {
            val rawTransaction = rawTransactionCache[utxo.txHash] ?: run {
                val response = electrumClient.getTransaction(utxo.txHash, verbose = false)
                val raw = when {
                    response.isJsonPrimitive -> response.asString
                    response.isJsonObject -> response.asJsonObject.get("hex")?.asString
                        ?: throw IllegalStateException("Electrum response did not contain raw transaction hex")
                    else -> throw IllegalStateException("Electrum returned an invalid raw transaction")
                }
                rawTransactionCache[utxo.txHash] = raw
                raw
            }
            RawTransactionParser.verifyOutput(
                transactionHex = rawTransaction,
                expectedTxId = utxo.txHash,
                outputIndex = utxo.outputIndex,
                expectedValue = utxo.value,
                expectedScriptPubKeyHex = utxo.scriptPubKey
            )
        }
    }

    suspend fun broadcastPreparedTransaction(prepared: PreparedSend): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                require(prepared.coinId == coinId) { "Prepared transaction belongs to another coin" }
                val serverTxId = electrumClient.broadcastTransaction(
                    prepared.signedTransaction.txHex
                )
                require(serverTxId.equals(prepared.signedTransaction.txId, ignoreCase = true)) {
                    "Electrum server returned an unexpected transaction ID"
                }
                prepared.changeIndex?.let { index ->
                    if (prepared.changePurpose == Bip32.BIP84_PURPOSE) {
                        if (secureKeyStore.getNextBip84ChangeIndex(coinId) <= index) {
                            secureKeyStore.storeNextBip84ChangeIndex(coinId, index + 1)
                        }
                    } else if (secureKeyStore.getNextChangeIndex(coinId) <= index) {
                        secureKeyStore.storeNextChangeIndex(coinId, index + 1)
                    }
                }

                transactionDao.insertTransaction(
                    TransactionEntity(
                        txId = prepared.signedTransaction.txId,
                        walletAddress = prepared.fromAddress,
                        amount = -prepared.amount,
                        fee = prepared.fee,
                        toAddress = prepared.toAddress,
                        fromAddress = prepared.fromAddress,
                        status = "pending",
                        timestamp = System.currentTimeMillis(),
                        coinId = coinId
                    )
                )

                prepared.spentUtxoIds.forEach { utxoDao.markSpent(coinId, it) }

                Result.success(prepared.signedTransaction.txId)
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast failed: ${e.message}", e)
                Result.failure(e)
            }
        }

    suspend fun sendTransaction(
        fromAddress: String,
        toAddress: String,
        amountSatoshis: Long,
        sendAll: Boolean = false,
        feeRate: Long = profile.defaultFeeRate
    ): Result<String> {
        val prepared = prepareTransaction(
            fromAddress,
            toAddress,
            amountSatoshis,
            sendAll,
            feeRate
        ).getOrElse { return Result.failure(it) }
        return broadcastPreparedTransaction(prepared)
    }

    /**
     * Get or derive an HD change address.
     */
    private data class PendingChangeAddress(
        val address: String,
        val index: Int,
        val purpose: Int
    )

    /**
     * Derive the current change slot without consuming it. Preview cancellation and failed
     * broadcasts reuse this slot; the index advances only after the signed transaction is accepted.
     */
    private suspend fun getOrDerivePendingChangeAddress(): PendingChangeAddress {
        val mnemonic = secureKeyStore.getSeedPhrase() ?: throw IllegalStateException("No seed")
        val hdWallet = HdWallet.fromMnemonic(
            mnemonic,
            profile,
            derivationVersion = secureKeyStore.getSeedDerivationVersion()
        )
        val purpose = defaultHdPurpose()
        val index = if (purpose == Bip32.BIP84_PURPOSE) {
            secureKeyStore.getNextBip84ChangeIndex(coinId)
        } else {
            secureKeyStore.getNextChangeIndex(coinId)
        }
        val keyPair = hdWallet.deriveChangeKey(index, purpose)
        val address = addressForPurpose(keyPair, purpose)

        // Store key if not already stored
        if (secureKeyStore.getPrivateKey(coinId, address) == null) {
            storeOwnedKey(address, keyPair)
        }
        if (walletDao.getWalletByAddress(coinId, address) == null) {
            walletDao.insertWallet(
                WalletEntity(
                    address = address,
                    label = if (purpose == Bip32.BIP84_PURPOSE) {
                        "Native SegWit Change #${index + 1}"
                    } else {
                        "Change #${index + 1}"
                    },
                    createdAt = System.currentTimeMillis(),
                    isActive = true,
                    derivationPath = derivationPath(purpose, 1, index),
                    derivationIndex = index,
                    isChange = true,
                    addressType = addressTypeForPurpose(purpose),
                    derivationPurpose = purpose,
                    coinId = coinId
                )
            )
        }
        return PendingChangeAddress(address, index, purpose)
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
            for (address in walletDao.getActiveAddresses(coinId)) {
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
            for (address in walletDao.getActiveAddresses(coinId)) {
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
            val addresses = walletDao.getActiveAddresses(coinId)
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
        val scriptPubKey = MeowcoinAddress.toScriptPubKey(address, profile)
            .joinToString("") { "%02x".format(it) }
        val utxoEntities = unspent.map { utxo ->
            UtxoEntity(
                id = "${utxo.txHash}:${utxo.txPos}",
                txHash = utxo.txHash,
                outputIndex = utxo.txPos,
                walletAddress = address,
                value = utxo.value,
                scriptPubKey = scriptPubKey,
                confirmations = if (utxo.height > 0) 1 else 0,
                isSpent = false,
                coinId = coinId
            )
        }

        utxoDao.deleteAllForWallet(coinId, address)
        if (utxoEntities.isNotEmpty()) {
            utxoDao.insertUtxos(utxoEntities)
        }
        return utxoEntities.size
    }

    private suspend fun refreshHistory(address: String): Int = historySyncMutex.withLock {
        val scriptHash = electrumClient.addressToScriptHash(address)
        val myAddresses = (walletDao.getActiveAddresses(coinId) + address).toSet()
        val prevTxCache = mutableMapOf<String, JsonObject>()
        val history = electrumClient.getHistory(scriptHash)

        for (item in history) {
            val existing = transactionDao.getTransaction(coinId, item.txHash)
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
                repositoryScope.launch {
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
        val addresses = walletDao.getActiveAddresses(coinId)
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
                val estimate = (feePerKb * 100_000_000 / 1000).toLong()
                val minimum = profile.defaultFeeRate.coerceAtLeast(1L)
                val maximum = Math.multiplyExact(minimum, 10L)
                estimate.coerceIn(minimum, maximum)
            } else {
                profile.defaultFeeRate
            }
        } catch (e: Exception) {
            profile.defaultFeeRate
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
        secureKeyStore.removePrivateKey(coinId, address)
        walletDao.deleteWallet(WalletEntity(address = address, coinId = coinId))
        transactionDao.deleteAllForWallet(coinId, address)
        utxoDao.deleteAllForWallet(coinId, address)
        assetDao.deleteAllForWallet(coinId, address)
    }

    /**
     * Delete the entire wallet (all addresses, keys, seed).
     */
    suspend fun deleteAllWalletData() = withContext(Dispatchers.IO) {
        database.clearAllTables()
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
            status = if (confirmations > 0) "confirmed" else "pending",
            coinId = coinId
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
        return try {
            AmountCodec.toAtomic(valEl.asBigDecimal, profile)
        } catch (_: Exception) {
            0L
        }
    }
}
