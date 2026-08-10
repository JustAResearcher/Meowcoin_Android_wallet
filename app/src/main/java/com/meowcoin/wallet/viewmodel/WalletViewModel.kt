package com.meowcoin.wallet.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meowcoin.wallet.crypto.AmountCodec
import com.meowcoin.wallet.crypto.Bip39
import com.meowcoin.wallet.crypto.CoinProfile
import com.meowcoin.wallet.crypto.CoinRegistry
import com.meowcoin.wallet.crypto.PaymentRequestSource
import com.meowcoin.wallet.crypto.SecureKeyStore
import com.meowcoin.wallet.data.local.AssetEntity
import com.meowcoin.wallet.data.local.TransactionEntity
import com.meowcoin.wallet.data.local.WalletDatabase
import com.meowcoin.wallet.data.local.WalletEntity
import com.meowcoin.wallet.data.remote.ElectrumClient
import com.meowcoin.wallet.data.remote.PriceService
import com.meowcoin.wallet.data.repository.WalletRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Locale

class WalletViewModel(application: Application) : AndroidViewModel(application) {

    private val database = WalletDatabase.getInstance(application)
    private val secureKeyStore = SecureKeyStore(application)
    private var activeProfile: CoinProfile = CoinRegistry.findById(
        secureKeyStore.getSelectedCoinId()
    )?.takeIf { it.enabled } ?: CoinRegistry.MEWC
    private var repository = WalletRepository(database, secureKeyStore, activeProfile)
    private var connectionObserverJob: Job? = null
    private var serverObserverJob: Job? = null
    private var activationJob: Job? = null
    private var syncJob: Job? = null
    private var sendJob: Job? = null
    private var consolidationJob: Job? = null
    private var deletionJob: Job? = null
    private var coinGeneration = 0L
    private var isDeleting = false
    private val walletOperationJobs = mutableListOf<Job>()
    private val dataObserverJobs = mutableListOf<Job>()
    private var preparedSend: WalletRepository.PreparedSend? = null
    private var preparedConsolidation: WalletRepository.PreparedConsolidation? = null

    // ── UI State ──
    private val _uiState = MutableStateFlow(WalletUiState())
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    private val _sendState = MutableStateFlow(SendUiState())
    val sendState: StateFlow<SendUiState> = _sendState.asStateFlow()

    private val _consolidationState = MutableStateFlow(ConsolidationUiState())
    val consolidationState: StateFlow<ConsolidationUiState> =
        _consolidationState.asStateFlow()

    private val _mnemonicState = MutableStateFlow(
        secureKeyStore.getSeedPhrase()
            ?.takeIf { secureKeyStore.isMnemonicBackupRequired() }
            ?.let { mnemonic ->
                MnemonicUiState(
                    mnemonic = mnemonic,
                    words = mnemonic.split(" "),
                    isBackedUp = false
                )
            }
            ?: MnemonicUiState(isBackedUp = true)
    )
    val mnemonicState: StateFlow<MnemonicUiState> = _mnemonicState.asStateFlow()

    private fun launchWalletOperation(
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        if (isDeleting) return Job().apply { cancel() }
        walletOperationJobs.removeAll { it.isCompleted }
        return viewModelScope.launch(block = block).also(walletOperationJobs::add)
    }

    private fun legacySeedDerivationWarning(): String? {
        val storedSeed = secureKeyStore.getSeedPhrase() ?: return null
        if (secureKeyStore.getSeedDerivationVersion() != Bip39.LEGACY_DERIVATION_VERSION) {
            return null
        }
        val legacyInput = storedSeed.trim().lowercase(Locale.ROOT)
        if (legacyInput == Bip39.canonicalizeMnemonic(storedSeed)) return null
        return "This wallet preserves legacy recovery-phrase spacing. Keep the exact phrase " +
            "format shown in Settings and move funds to a new wallet before recovering elsewhere."
    }

    // ── Connection state from the active coin's Electrum client ──
    private val _connectionState = MutableStateFlow(ElectrumClient.ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ElectrumClient.ConnectionState> = _connectionState.asStateFlow()
    private val _serverInfo = MutableStateFlow<ElectrumClient.ServerInfo?>(null)
    val serverInfo: StateFlow<ElectrumClient.ServerInfo?> = _serverInfo.asStateFlow()

    init {
        bindNetworkState()
        val hasAnyWallet = secureKeyStore.hasSeedPhrase() || secureKeyStore.hasWallet("mewc")
        _uiState.update {
            it.copy(
                hasWallet = hasAnyWallet,
                biometricEnabled = secureKeyStore.isBiometricEnabled(),
                coinId = activeProfile.id,
                coinName = activeProfile.name,
                ticker = activeProfile.ticker,
                uriScheme = activeProfile.uriScheme
            )
        }
        if (hasAnyWallet) {
            val generation = ++coinGeneration
            _uiState.update { it.copy(isSwitchingCoin = true) }
            activationJob = viewModelScope.launch {
                try {
                    activateCoin(activeProfile, initializeFromSeed = true, generation = generation)
                } catch (e: Exception) {
                    if (generation == coinGeneration) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isSwitchingCoin = false,
                                error = "Coin activation failed: ${e.message}"
                            )
                        }
                    }
                }
            }
        }
    }

    private fun bindNetworkState() {
        connectionObserverJob?.cancel()
        serverObserverJob?.cancel()
        val activeRepository = repository
        connectionObserverJob = viewModelScope.launch {
            activeRepository.connectionState.collect { _connectionState.value = it }
        }
        serverObserverJob = viewModelScope.launch {
            activeRepository.serverInfo.collect { _serverInfo.value = it }
        }
    }

    fun selectCoin(coinId: String) {
        if (isDeleting) return
        if (activationJob?.isActive == true) {
            _uiState.update { it.copy(error = "A coin switch is already in progress") }
            return
        }
        if (_sendState.value.isSending || preparedSend != null) {
            _uiState.update { it.copy(error = "Finish or cancel the pending send first") }
            return
        }
        if (_consolidationState.value.isLoading ||
            _consolidationState.value.isConsolidating ||
            preparedConsolidation != null
        ) {
            _uiState.update { it.copy(error = "Finish or cancel consolidation first") }
            return
        }
        val profile = CoinRegistry.findById(coinId)
        if (profile == null || !profile.enabled) {
            _uiState.update {
                it.copy(error = profile?.disabledReason ?: "Unsupported coin: $coinId")
            }
            return
        }
        if (profile.id != activeProfile.id && !secureKeyStore.hasSeedPhrase()) {
            _uiState.update {
                it.copy(
                    error = "A recovery-phrase wallet is required to add ${profile.name}"
                )
            }
            return
        }

        val generation = ++coinGeneration
        _uiState.update { it.copy(isSwitchingCoin = true, isLoading = true) }
        activationJob = viewModelScope.launch {
            try {
                activateCoin(profile, initializeFromSeed = true, generation = generation)
            } catch (e: Exception) {
                if (generation == coinGeneration) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isSwitchingCoin = false,
                            error = "Coin activation failed: ${e.message}"
                        )
                    }
                }
            }
        }
    }

    private suspend fun activateCoin(
        profile: CoinProfile,
        initializeFromSeed: Boolean,
        generation: Long
    ) {
        if (generation != coinGeneration) return
        _uiState.update { it.copy(isLoading = true, isSwitchingCoin = true, error = null) }
        syncJob?.cancelAndJoin()
        val oldDataObservers = dataObserverJobs.toList()
        oldDataObservers.forEach(Job::cancel)
        oldDataObservers.forEach { it.cancelAndJoin() }
        dataObserverJobs.clear()
        repository.close()

        val nextRepository = WalletRepository(database, secureKeyStore, profile)
        val address = if (initializeFromSeed) {
            nextRepository.initializeFromStoredSeedIfNeeded()
        } else {
            secureKeyStore.getPrimaryAddress(profile.id)
        }
        val isHd = secureKeyStore.isHdWallet(profile.id)
        val receiveAddress = address?.let { nextRepository.getDefaultReceiveAddress() }.orEmpty()

        if (generation != coinGeneration) {
            nextRepository.close()
            return
        }

        activeProfile = profile
        repository = nextRepository
        secureKeyStore.setSelectedCoinId(profile.id)
        bindNetworkState()

        _uiState.update {
            it.copy(
                address = address.orEmpty(),
                receiveAddress = receiveAddress,
                balance = "0.00000000",
                fiatBalance = "",
                transactions = emptyList(),
                assets = emptyList(),
                allAddresses = emptyList(),
                hasWallet = address != null || secureKeyStore.hasSeedPhrase(),
                isHdWallet = isHd,
                isLoading = false,
                isSwitchingCoin = false,
                coinId = profile.id,
                coinName = profile.name,
                ticker = profile.ticker,
                uriScheme = profile.uriScheme,
                error = legacySeedDerivationWarning()
            )
        }

        if (address != null) {
            val discoveryPending = isHd && nextRepository.isHdDiscoveryPending()
            observeWalletData(address, nextRepository, profile, generation)
            connectAndSync(
                address = address,
                runDiscovery = discoveryPending,
                targetRepository = nextRepository,
                targetProfile = profile,
                generation = generation
            )
        }
    }

    // ═══════════════════════════════════════════
    //  HD Wallet Creation / Import
    // ═══════════════════════════════════════════

    /**
     * Create a new HD wallet. Generates mnemonic and stores in mnemonicState
     * for the user to back up before finalizing.
     */
    fun createHdWallet(wordCount: Int = 12) {
        launchWalletOperation {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val mnemonic = repository.createHdWallet(wordCount)
                val address = secureKeyStore.getPrimaryAddress(activeProfile.id) ?: ""
                val receiveAddress = repository.getDefaultReceiveAddress()
                _mnemonicState.update {
                    it.copy(
                        mnemonic = mnemonic,
                        words = mnemonic.split(" "),
                        isBackedUp = false
                    )
                }
                _uiState.update {
                    it.copy(
                        address = address,
                        receiveAddress = receiveAddress,
                        hasWallet = true,
                        isHdWallet = true,
                        isLoading = false
                    )
                }
                observeWalletData(address)
                connectAndSync(address)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = "Failed to create wallet: ${e.message}")
                }
            }
        }
    }

    /**
     * Import / restore an HD wallet from a mnemonic seed phrase.
     */
    fun importHdWallet(mnemonic: String) {
        launchWalletOperation {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val address = repository.importHdWallet(mnemonic)
                val receiveAddress = repository.getDefaultReceiveAddress()
                _uiState.update {
                    it.copy(
                        address = address,
                        receiveAddress = receiveAddress,
                        hasWallet = true,
                        isHdWallet = true,
                        isLoading = false
                    )
                }
                _mnemonicState.value = MnemonicUiState(isBackedUp = true)
                observeWalletData(address)
                connectAndSync(address, runDiscovery = true)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = "Import failed: ${e.message}")
                }
            }
        }
    }

    fun confirmMnemonicBackup() {
        secureKeyStore.setMnemonicBackupRequired(false)
        _mnemonicState.value = MnemonicUiState(isBackedUp = true)
    }

    /**
     * Derive the next HD address.
     */
    fun deriveNextAddress(label: String = "") {
        val targetRepository = repository
        val generation = coinGeneration
        launchWalletOperation {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val newAddress = targetRepository.deriveNextAddress(label)
                if (generation != coinGeneration) return@launchWalletOperation
                _uiState.update { it.copy(receiveAddress = newAddress) }
                targetRepository.refreshWalletData(newAddress)
                if (generation != coinGeneration) return@launchWalletOperation
                targetRepository.subscribeToAddress(newAddress)
            } catch (e: Exception) {
                if (generation == coinGeneration) {
                    _uiState.update {
                        it.copy(error = "Failed to derive address: ${e.message}")
                    }
                }
            } finally {
                if (generation == coinGeneration) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    // ═══════════════════════════════════════════
    //  Legacy Wallet Creation / Import
    // ═══════════════════════════════════════════

    fun createWallet() {
        launchWalletOperation {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val address = repository.createWallet()
                _uiState.update {
                    it.copy(
                        address = address,
                        receiveAddress = address,
                        hasWallet = true,
                        isHdWallet = false,
                        isLoading = false
                    )
                }
                observeWalletData(address)
                connectAndSync(address)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = "Failed to create wallet: ${e.message}")
                }
            }
        }
    }

    fun importWalletFromWIF(wif: String) {
        launchWalletOperation {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val address = repository.importWalletFromWIF(wif)
                _uiState.update {
                    it.copy(
                        address = address,
                        receiveAddress = address,
                        hasWallet = true,
                        isHdWallet = false,
                        isLoading = false
                    )
                }
                observeWalletData(address)
                connectAndSync(address)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = "Import failed: ${e.message}")
                }
            }
        }
    }

    // ═══════════════════════════════════════════
    //  Network Connection
    // ═══════════════════════════════════════════

    private fun connectAndSync(
        address: String,
        runDiscovery: Boolean = false,
        targetRepository: WalletRepository = repository,
        targetProfile: CoinProfile = activeProfile,
        generation: Long = coinGeneration
    ) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            if (generation != coinGeneration) return@launch
            _uiState.update { it.copy(isLoading = true) }
            try {
                val connected = targetRepository.connectToNetwork()
                if (connected) {
                    // BIP44 address discovery — only on freshly-restored HD wallets,
                    // before subscriptions so newly-found addresses are wired up too.
                    if (runDiscovery && targetRepository.isHdWallet()) {
                        try {
                            targetRepository.discoverHdAddresses()
                            if (generation == coinGeneration) {
                                _uiState.update {
                                    it.copy(
                                        receiveAddress = targetRepository.getDefaultReceiveAddress()
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            if (generation == coinGeneration) {
                                _uiState.update {
                                    it.copy(error = "Address scan failed: ${e.message}")
                                }
                            }
                        }
                    }
                    // Subscribe to all addresses for HD wallets
                    if (targetRepository.isHdWallet()) {
                        targetRepository.subscribeToAllAddresses()
                    } else {
                        targetRepository.subscribeToAddress(address)
                    }
                    targetRepository.subscribeToBlocks { _ -> }
                    // Make the wallet usable as soon as its spendable coins are
                    // current. Transaction history continues in the background.
                    refreshBalances(address, targetRepository)
                    // Fetch fiat price
                    if (targetProfile.id == CoinRegistry.MEWC.id) {
                        try { targetRepository.fetchFiatPrice() } catch (_: Exception) {}
                        updateFiatBalance(targetProfile, generation)
                    }
                    refreshHistoryInBackground(address, targetRepository, generation)
                } else if (generation == coinGeneration) {
                    _uiState.update {
                        it.copy(
                            error = if (runDiscovery) {
                                "Could not connect to Electrum. Address discovery will retry automatically."
                            } else {
                                "Could not connect to Electrum."
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                if (generation == coinGeneration) {
                    _uiState.update { it.copy(error = "Connection failed: ${e.message}") }
                }
            } finally {
                if (generation == coinGeneration) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun connectToCustomServer(host: String, port: Int, useSSL: Boolean) {
        launchWalletOperation {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                repository.disconnect()
                val connected = repository.connectToCustomServer(host, port, useSSL)
                if (connected) {
                    val address = _uiState.value.address
                    if (address.isNotEmpty()) {
                        if (repository.isHdWallet() && repository.isHdDiscoveryPending()) {
                            try {
                                repository.discoverHdAddresses()
                                _uiState.update {
                                    it.copy(receiveAddress = repository.getDefaultReceiveAddress())
                                }
                            } catch (e: Exception) {
                                _uiState.update {
                                    it.copy(error = "Address scan failed: ${e.message}")
                                }
                            }
                        }
                        if (repository.isHdWallet()) {
                            repository.subscribeToAllAddresses()
                        } else {
                            repository.subscribeToAddress(address)
                        }
                        refreshBalances(address)
                        refreshHistoryInBackground(address)
                    }
                } else {
                    _uiState.update { it.copy(error = "Failed to connect to $host:$port") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Connection error: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private suspend fun refreshBalances(
        address: String,
        targetRepository: WalletRepository = repository
    ) {
        val result = if (targetRepository.isHdWallet()) {
            targetRepository.refreshAllBalances()
        } else {
            targetRepository.refreshWalletBalance(address)
        }
        result.getOrThrow()
    }

    private fun refreshHistoryInBackground(
        address: String,
        targetRepository: WalletRepository = repository,
        generation: Long = coinGeneration
    ) {
        launchWalletOperation {
            if (generation != coinGeneration) return@launchWalletOperation
            if (targetRepository.isHdWallet()) {
                targetRepository.refreshAllHistories()
            } else {
                targetRepository.refreshWalletHistory(address)
            }
        }
    }

    fun reconnect() {
        val address = _uiState.value.address
        if (address.isNotEmpty()) {
            connectAndSync(
                address = address,
                runDiscovery = repository.isHdWallet() && repository.isHdDiscoveryPending()
            )
        }
    }

    /**
     * On-demand BIP44 address discovery for an existing HD wallet. Connects if
     * not already connected, walks both chains with gap_limit=20, then
     * re-subscribes and refreshes so newly-found addresses contribute to the
     * displayed balance and history.
     */
    fun rescanAddresses() {
        launchWalletOperation {
            if (!repository.isHdWallet()) {
                _uiState.update { it.copy(error = "Rescan is only available for HD wallets") }
                return@launchWalletOperation
            }
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                if (connectionState.value != ElectrumClient.ConnectionState.CONNECTED) {
                    val ok = repository.connectToNetwork()
                    if (!ok) {
                        _uiState.update { it.copy(error = "Could not connect to Electrum") }
                        return@launchWalletOperation
                    }
                }
                repository.discoverHdAddresses(force = true)
                _uiState.update {
                    it.copy(receiveAddress = repository.getDefaultReceiveAddress())
                }
                repository.subscribeToAllAddresses()
                refreshBalances(_uiState.value.address)
                updateFiatBalance()
                refreshHistoryInBackground(_uiState.value.address)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Rescan failed: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    // ═══════════════════════════════════════════
    //  Data Observation
    // ═══════════════════════════════════════════

    private fun observeWalletData(
        address: String,
        targetRepository: WalletRepository = repository,
        targetProfile: CoinProfile = activeProfile,
        generation: Long = coinGeneration
    ) {
        dataObserverJobs.forEach(Job::cancel)
        dataObserverJobs.clear()

        // Observe total balance (all addresses for HD, single for legacy)
        dataObserverJobs += viewModelScope.launch {
            if (targetRepository.isHdWallet()) {
                targetRepository.getTotalBalanceFormatted().collect { balance ->
                    if (generation != coinGeneration) return@collect
                    _uiState.update { it.copy(balance = balance) }
                    updateFiatBalance(targetProfile, generation)
                }
            } else {
                targetRepository.getBalanceFormatted(address).collect { balance ->
                    if (generation != coinGeneration) return@collect
                    _uiState.update { it.copy(balance = balance) }
                    updateFiatBalance(targetProfile, generation)
                }
            }
        }

        // Observe transactions (all addresses for HD)
        dataObserverJobs += viewModelScope.launch {
            if (targetRepository.isHdWallet()) {
                targetRepository.getAllTransactions().collect { txs ->
                    if (generation != coinGeneration) return@collect
                    _uiState.update { it.copy(transactions = txs) }
                }
            } else {
                targetRepository.getRecentTransactions(address).collect { txs ->
                    if (generation != coinGeneration) return@collect
                    _uiState.update { it.copy(transactions = txs) }
                }
            }
        }

        // Observe assets
        dataObserverJobs += viewModelScope.launch {
            targetRepository.getAssets().collect { assets ->
                if (generation != coinGeneration) return@collect
                _uiState.update { it.copy(assets = assets) }
            }
        }

        // Load all addresses for the address list
        loadAllAddresses(targetRepository, generation)
    }

    private fun loadAllAddresses(
        targetRepository: WalletRepository = repository,
        generation: Long = coinGeneration
    ) {
        dataObserverJobs += viewModelScope.launch {
            targetRepository.getAllWallets().collect { wallets ->
                if (generation != coinGeneration) return@collect
                _uiState.update { it.copy(allAddresses = wallets) }
            }
        }
    }

    private fun updateFiatBalance(
        targetProfile: CoinProfile = activeProfile,
        generation: Long = coinGeneration
    ) {
        if (generation != coinGeneration) return
        val balanceStr = _uiState.value.balance
        if (targetProfile.id != CoinRegistry.MEWC.id) {
            _uiState.update { it.copy(fiatBalance = "") }
            return
        }
        val satoshis = runCatching { AmountCodec.parseAtomic(balanceStr, targetProfile) }
            .getOrDefault(0L)
        val currency = secureKeyStore.getFiatCurrency()
        val fiat = PriceService.formatFiat(satoshis, currency)
        _uiState.update { it.copy(fiatBalance = fiat) }
    }

    // ═══════════════════════════════════════════
    //  Actions
    // ═══════════════════════════════════════════

    fun refreshData() {
        if (_uiState.value.isSwitchingCoin) return
        val address = _uiState.value.address
        if (address.isEmpty()) return
        val targetRepository = repository
        val targetProfile = activeProfile
        val generation = coinGeneration

        launchWalletOperation {
            _uiState.update { it.copy(isLoading = true) }
            try {
                refreshBalances(address, targetRepository)
                if (targetProfile.id == CoinRegistry.MEWC.id) {
                    try { targetRepository.fetchFiatPrice() } catch (_: Exception) {}
                    updateFiatBalance(targetProfile, generation)
                }
                refreshHistoryInBackground(address, targetRepository, generation)
            } catch (e: Exception) {
                if (generation == coinGeneration) {
                    _uiState.update { it.copy(error = "Refresh failed: ${e.message}") }
                }
            } finally {
                if (generation == coinGeneration) {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun sendCoin(toAddress: String, amountString: String, sendAll: Boolean = false) {
        if (_uiState.value.isSwitchingCoin || activationJob?.isActive == true) {
            _sendState.update { it.copy(error = "Wait for coin activation to finish") }
            return
        }
        val fromAddress = _uiState.value.address
        if (fromAddress.isEmpty() || _sendState.value.isSending || preparedSend != null) return
        if (_consolidationState.value.isLoading ||
            _consolidationState.value.isConsolidating ||
            preparedConsolidation != null
        ) {
            _sendState.update { it.copy(error = "Wait for consolidation to finish") }
            return
        }

        val sendRepository = repository
        val sendProfile = activeProfile
        val generation = coinGeneration
        _sendState.update { it.copy(isSending = true, error = null, successTxId = null) }
        sendJob = viewModelScope.launch {
            try {
                val atomicAmount = if (sendAll) {
                    0L
                } else {
                    AmountCodec.parseAtomic(amountString, sendProfile)
                }
                val feeRate = sendRepository.getEstimatedFeeRate()
                val result = sendRepository.prepareTransaction(
                    fromAddress = fromAddress,
                    toAddress = toAddress,
                    amountSatoshis = atomicAmount,
                    sendAll = sendAll,
                    feeRate = feeRate
                )

                result.fold(
                    onSuccess = { prepared ->
                        if (generation != coinGeneration ||
                            prepared.coinId != sendProfile.id
                        ) {
                            _sendState.value = SendUiState(
                                error = "Coin changed while preparing the transaction"
                            )
                            return@fold
                        }
                        preparedSend = prepared
                        _sendState.update {
                            it.copy(
                                isSending = false,
                                preview = SendPreview(
                                    toAddress = prepared.toAddress,
                                    network = "${sendProfile.name} Mainnet (${sendProfile.ticker})",
                                    destinationType = prepared.destinationType,
                                    networkTagged = prepared.paymentRequestSource ==
                                        PaymentRequestSource.URI,
                                    amount = AmountCodec.formatAtomic(
                                        prepared.amount,
                                        sendProfile,
                                        trimTrailingZeros = false
                                    ),
                                    fee = AmountCodec.formatAtomic(
                                        prepared.fee,
                                        sendProfile,
                                        trimTrailingZeros = false
                                    )
                                )
                            )
                        }
                    },
                    onFailure = { error ->
                        _sendState.update {
                            it.copy(isSending = false, error = error.message)
                        }
                    }
                )
            } catch (e: Exception) {
                _sendState.update {
                    it.copy(isSending = false, error = e.message)
                }
            }
        }
    }

    fun confirmPreparedSend() {
        val prepared = preparedSend ?: return
        if (_sendState.value.isSending) return
        val sendRepository = repository
        if (sendRepository.profile.id != prepared.coinId) {
            preparedSend = null
            _sendState.value = SendUiState(error = "Prepared transaction belongs to another coin")
            return
        }
        _sendState.update { it.copy(isSending = true, error = null) }

        sendJob = viewModelScope.launch {
            sendRepository.broadcastPreparedTransaction(prepared).fold(
                onSuccess = { txId ->
                    preparedSend = null
                    _sendState.update {
                        it.copy(isSending = false, preview = null, successTxId = txId)
                    }
                    refreshData()
                },
                onFailure = { error ->
                    _sendState.update { it.copy(isSending = false, error = error.message) }
                }
            )
        }
    }

    fun cancelPreparedSend() {
        if (_sendState.value.isSending) return
        preparedSend = null
        _sendState.update { it.copy(preview = null, error = null) }
    }

    fun sendMEWC(toAddress: String, amountString: String) {
        sendCoin(toAddress, amountString, sendAll = false)
    }

    fun prepareConsolidation() {
        if (_sendState.value.isSending || preparedSend != null ||
            _consolidationState.value.isLoading ||
            _consolidationState.value.isConsolidating ||
            preparedConsolidation != null
        ) {
            _consolidationState.value = ConsolidationUiState(
                error = "Another wallet operation is already in progress"
            )
            return
        }
        _consolidationState.value = ConsolidationUiState(isLoading = true)
        val activeRepository = repository
        consolidationJob = viewModelScope.launch {
            val previewResult = try {
                refreshBalances(_uiState.value.address)
                activeRepository.prepareConsolidation()
            } catch (e: Exception) {
                Result.failure(e)
            }
            previewResult.fold(
                onSuccess = { prepared ->
                    preparedConsolidation = prepared
                    _consolidationState.value = ConsolidationUiState(preview = prepared.preview)
                },
                onFailure = { error ->
                    _consolidationState.value = ConsolidationUiState(error = error.message)
                }
            )
        }
    }

    fun consolidateUtxos() {
        val prepared = preparedConsolidation ?: return
        if (_consolidationState.value.isConsolidating ||
            _consolidationState.value.isLoading ||
            _sendState.value.isSending || preparedSend != null
        ) return
        _consolidationState.update { it.copy(isConsolidating = true, error = null) }
        val activeRepository = repository
        consolidationJob = viewModelScope.launch {
            activeRepository.broadcastPreparedConsolidation(prepared).fold(
                onSuccess = { txId ->
                    preparedConsolidation = null
                    _consolidationState.update {
                        it.copy(
                            isConsolidating = false,
                            preview = null,
                            successTxId = txId
                        )
                    }
                    refreshData()
                },
                onFailure = { error ->
                    _consolidationState.update {
                        it.copy(isConsolidating = false, error = error.message)
                    }
                }
            )
        }
    }

    fun clearConsolidationState() {
        if (_consolidationState.value.isConsolidating) return
        consolidationJob?.cancel()
        consolidationJob = null
        preparedConsolidation = null
        _consolidationState.value = ConsolidationUiState()
    }

    // ═══════════════════════════════════════════
    //  Biometric
    // ═══════════════════════════════════════════

    fun setBiometricEnabled(enabled: Boolean) {
        repository.setBiometricEnabled(enabled)
        _uiState.update { it.copy(biometricEnabled = enabled) }
    }

    fun isBiometricEnabled(): Boolean = repository.isBiometricEnabled()

    // ═══════════════════════════════════════════
    //  Key Export / Seed Phrase
    // ═══════════════════════════════════════════

    fun getWIF(): String? {
        return repository.getWIF(_uiState.value.address)
    }

    fun getSeedPhrase(): String? {
        return repository.getSeedPhrase()
    }

    // ═══════════════════════════════════════════
    //  Wallet Deletion
    // ═══════════════════════════════════════════

    fun deleteWallet(onDeleted: () -> Unit = {}) {
        if (isDeleting || deletionJob?.isActive == true) return
        isDeleting = true

        val repositoryToDelete = repository
        coinGeneration++
        val jobsToStop = buildList {
            addAll(walletOperationJobs)
            addAll(dataObserverJobs)
            listOfNotNull(
                activationJob,
                syncJob,
                sendJob,
                consolidationJob,
                connectionObserverJob,
                serverObserverJob
            ).forEach(::add)
        }.distinct()
        jobsToStop.forEach(Job::cancel)
        repositoryToDelete.disconnect()
        preparedSend = null
        preparedConsolidation = null
        _sendState.value = SendUiState()
        _consolidationState.value = ConsolidationUiState()
        _uiState.update {
            it.copy(
                hasWallet = false,
                isLoading = true,
                isSwitchingCoin = true,
                error = null
            )
        }

        deletionJob = viewModelScope.launch {
            try {
                jobsToStop.forEach { it.cancelAndJoin() }
                repositoryToDelete.close()
                repositoryToDelete.deleteAllWalletData()
            } catch (e: Exception) {
                isDeleting = false
                _uiState.update {
                    it.copy(
                        hasWallet = true,
                        isLoading = false,
                        isSwitchingCoin = false,
                        error = "Delete failed: ${e.message}"
                    )
                }
                return@launch
            }
            walletOperationJobs.clear()
            dataObserverJobs.clear()
            activeProfile = CoinRegistry.MEWC
            repository = WalletRepository(database, secureKeyStore, activeProfile)
            bindNetworkState()
            _uiState.value = WalletUiState()
            _sendState.value = SendUiState()
            _consolidationState.value = ConsolidationUiState()
            _mnemonicState.value = MnemonicUiState(isBackedUp = true)
            isDeleting = false
            onDeleted()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearSendState() {
        if (_sendState.value.isSending || preparedSend != null) return
        _sendState.value = SendUiState()
    }

    override fun onCleared() {
        super.onCleared()
        activationJob?.cancel()
        syncJob?.cancel()
        sendJob?.cancel()
        consolidationJob?.cancel()
        deletionJob?.cancel()
        walletOperationJobs.forEach(Job::cancel)
        dataObserverJobs.forEach(Job::cancel)
        connectionObserverJob?.cancel()
        serverObserverJob?.cancel()
        repository.shutdown()
    }
}

data class WalletUiState(
    val hasWallet: Boolean = false,
    val coinId: String = CoinRegistry.MEWC.id,
    val coinName: String = CoinRegistry.MEWC.name,
    val ticker: String = CoinRegistry.MEWC.ticker,
    val uriScheme: String = CoinRegistry.MEWC.uriScheme,
    val address: String = "",
    val receiveAddress: String = "",
    val balance: String = "0.00000000",
    val fiatBalance: String = "",
    val transactions: List<TransactionEntity> = emptyList(),
    val assets: List<AssetEntity> = emptyList(),
    val allAddresses: List<WalletEntity> = emptyList(),
    val isHdWallet: Boolean = false,
    val biometricEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val isSwitchingCoin: Boolean = false,
    val error: String? = null
)

data class SendUiState(
    val isSending: Boolean = false,
    val error: String? = null,
    val successTxId: String? = null,
    val preview: SendPreview? = null
)

data class SendPreview(
    val toAddress: String,
    val network: String,
    val destinationType: String,
    val networkTagged: Boolean,
    val amount: String,
    val fee: String
)

data class ConsolidationUiState(
    val isLoading: Boolean = false,
    val isConsolidating: Boolean = false,
    val preview: WalletRepository.ConsolidationPreview? = null,
    val error: String? = null,
    val successTxId: String? = null
)

data class MnemonicUiState(
    val mnemonic: String = "",
    val words: List<String> = emptyList(),
    val isBackedUp: Boolean = false
)
