package com.meowcoin.wallet.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meowcoin.wallet.crypto.SecureKeyStore
import com.meowcoin.wallet.data.local.AssetEntity
import com.meowcoin.wallet.data.local.TransactionEntity
import com.meowcoin.wallet.data.local.WalletDatabase
import com.meowcoin.wallet.data.local.WalletEntity
import com.meowcoin.wallet.data.remote.ElectrumClient
import com.meowcoin.wallet.data.remote.PriceService
import com.meowcoin.wallet.data.repository.WalletRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class WalletViewModel(application: Application) : AndroidViewModel(application) {

    private val database = WalletDatabase.getInstance(application)
    private val secureKeyStore = SecureKeyStore(application)
    private val repository = WalletRepository(database, secureKeyStore)

    // ── UI State ──
    private val _uiState = MutableStateFlow(WalletUiState())
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    private val _sendState = MutableStateFlow(SendUiState())
    val sendState: StateFlow<SendUiState> = _sendState.asStateFlow()

    private val _mnemonicState = MutableStateFlow(MnemonicUiState())
    val mnemonicState: StateFlow<MnemonicUiState> = _mnemonicState.asStateFlow()

    // ── Connection state from Electrum ──
    val connectionState: StateFlow<ElectrumClient.ConnectionState> = repository.connectionState
    val serverInfo = repository.serverInfo

    init {
        if (secureKeyStore.hasWallet()) {
            val address = secureKeyStore.getPrimaryAddress()
            if (address != null) {
                _uiState.update {
                    it.copy(
                        address = address,
                        hasWallet = true,
                        isHdWallet = secureKeyStore.isHdWallet(),
                        biometricEnabled = secureKeyStore.isBiometricEnabled()
                    )
                }
                observeWalletData(address)
                connectAndSync(address)
            }
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val mnemonic = repository.createHdWallet(wordCount)
                val address = secureKeyStore.getPrimaryAddress() ?: ""
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val address = repository.importHdWallet(mnemonic.trim().lowercase())
                _uiState.update {
                    it.copy(
                        address = address,
                        hasWallet = true,
                        isHdWallet = true,
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

    fun confirmMnemonicBackup() {
        _mnemonicState.update { it.copy(isBackedUp = true) }
    }

    /**
     * Derive the next HD address.
     */
    fun deriveNextAddress(label: String = "") {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val newAddress = repository.deriveNextAddress(label)
                // Refresh to pick up the new address
                repository.refreshWalletData(newAddress)
                repository.subscribeToAddress(newAddress)
                // Update wallet list
                loadAllAddresses()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = "Failed to derive address: ${e.message}")
                }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    // ═══════════════════════════════════════════
    //  Legacy Wallet Creation / Import
    // ═══════════════════════════════════════════

    fun createWallet() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val address = repository.createWallet()
                _uiState.update {
                    it.copy(address = address, hasWallet = true, isHdWallet = false, isLoading = false)
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val address = repository.importWalletFromWIF(wif)
                _uiState.update {
                    it.copy(address = address, hasWallet = true, isHdWallet = false, isLoading = false)
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

    private fun connectAndSync(address: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val connected = repository.connectToNetwork()
                if (connected) {
                    // Subscribe to all addresses for HD wallets
                    if (secureKeyStore.isHdWallet()) {
                        repository.subscribeToAllAddresses()
                    } else {
                        repository.subscribeToAddress(address)
                    }
                    repository.subscribeToBlocks { _ ->
                        viewModelScope.launch { refreshData() }
                    }
                    // Initial sync
                    if (secureKeyStore.isHdWallet()) {
                        repository.refreshAllAddresses()
                    } else {
                        repository.refreshWalletData(address)
                    }
                    // Fetch fiat price
                    try { repository.fetchFiatPrice() } catch (_: Exception) {}
                    updateFiatBalance()
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Connection failed: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun connectToCustomServer(host: String, port: Int, useSSL: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                repository.disconnect()
                val connected = repository.connectToCustomServer(host, port, useSSL)
                if (connected) {
                    val address = _uiState.value.address
                    if (address.isNotEmpty()) {
                        if (secureKeyStore.isHdWallet()) {
                            repository.subscribeToAllAddresses()
                            repository.refreshAllAddresses()
                        } else {
                            repository.subscribeToAddress(address)
                            repository.refreshWalletData(address)
                        }
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

    fun reconnect() {
        val address = _uiState.value.address
        if (address.isNotEmpty()) {
            connectAndSync(address)
        }
    }

    // ═══════════════════════════════════════════
    //  Data Observation
    // ═══════════════════════════════════════════

    private fun observeWalletData(address: String) {
        // Observe total balance (all addresses for HD, single for legacy)
        viewModelScope.launch {
            if (secureKeyStore.isHdWallet()) {
                repository.getTotalBalanceMEWC().collect { balance ->
                    _uiState.update { it.copy(balance = balance) }
                    updateFiatBalance()
                }
            } else {
                repository.getBalanceMEWC(address).collect { balance ->
                    _uiState.update { it.copy(balance = balance) }
                    updateFiatBalance()
                }
            }
        }

        // Observe transactions (all addresses for HD)
        viewModelScope.launch {
            if (secureKeyStore.isHdWallet()) {
                repository.getAllTransactions().collect { txs ->
                    _uiState.update { it.copy(transactions = txs) }
                }
            } else {
                repository.getRecentTransactions(address).collect { txs ->
                    _uiState.update { it.copy(transactions = txs) }
                }
            }
        }

        // Observe assets
        viewModelScope.launch {
            repository.getAssets().collect { assets ->
                _uiState.update { it.copy(assets = assets) }
            }
        }

        // Load all addresses for the address list
        loadAllAddresses()
    }

    private fun loadAllAddresses() {
        viewModelScope.launch {
            repository.getAllWallets().collect { wallets ->
                _uiState.update { it.copy(allAddresses = wallets) }
            }
        }
    }

    private fun updateFiatBalance() {
        val balanceStr = _uiState.value.balance
        val balanceDouble = balanceStr.toDoubleOrNull() ?: 0.0
        val satoshis = (balanceDouble * 100_000_000).toLong()
        val currency = secureKeyStore.getFiatCurrency()
        val fiat = PriceService.formatFiat(satoshis, currency)
        _uiState.update { it.copy(fiatBalance = fiat) }
    }

    // ═══════════════════════════════════════════
    //  Actions
    // ═══════════════════════════════════════════

    fun refreshData() {
        val address = _uiState.value.address
        if (address.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                if (secureKeyStore.isHdWallet()) {
                    repository.refreshAllAddresses()
                } else {
                    repository.refreshWalletData(address)
                }
                try { repository.fetchFiatPrice() } catch (_: Exception) {}
                updateFiatBalance()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Refresh failed: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun sendMEWC(toAddress: String, amountString: String) {
        val fromAddress = _uiState.value.address
        if (fromAddress.isEmpty()) return

        viewModelScope.launch {
            _sendState.update { it.copy(isSending = true, error = null, successTxId = null) }
            try {
                val satoshis = WalletRepository.parseMEWCtoSatoshis(amountString)
                val result = repository.sendTransaction(fromAddress, toAddress, satoshis)

                result.fold(
                    onSuccess = { txId ->
                        _sendState.update {
                            it.copy(isSending = false, successTxId = txId)
                        }
                        refreshData()
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

    fun deleteWallet() {
        viewModelScope.launch {
            repository.disconnect()
            repository.deleteAllWalletData()
            _uiState.value = WalletUiState()
            _sendState.value = SendUiState()
            _mnemonicState.value = MnemonicUiState()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearSendState() {
        _sendState.value = SendUiState()
    }

    override fun onCleared() {
        super.onCleared()
        repository.disconnect()
    }
}

data class WalletUiState(
    val hasWallet: Boolean = false,
    val address: String = "",
    val balance: String = "0.00000000",
    val fiatBalance: String = "",
    val transactions: List<TransactionEntity> = emptyList(),
    val assets: List<AssetEntity> = emptyList(),
    val allAddresses: List<WalletEntity> = emptyList(),
    val isHdWallet: Boolean = false,
    val biometricEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)

data class SendUiState(
    val isSending: Boolean = false,
    val error: String? = null,
    val successTxId: String? = null
)

data class MnemonicUiState(
    val mnemonic: String = "",
    val words: List<String> = emptyList(),
    val isBackedUp: Boolean = false
)
