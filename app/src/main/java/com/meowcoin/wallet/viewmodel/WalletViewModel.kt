package com.meowcoin.wallet.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meowcoin.wallet.crypto.SecureKeyStore
import com.meowcoin.wallet.data.local.TransactionEntity
import com.meowcoin.wallet.data.local.WalletDatabase
import com.meowcoin.wallet.data.remote.ElectrumClient
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

    // ── Connection state from Electrum ──
    val connectionState: StateFlow<ElectrumClient.ConnectionState> = repository.connectionState
    val serverInfo = repository.serverInfo

    init {
        // Check if wallet exists
        if (secureKeyStore.hasWallet()) {
            val address = secureKeyStore.getPrimaryAddress()
            if (address != null) {
                _uiState.update { it.copy(address = address, hasWallet = true) }
                observeWalletData(address)
                connectAndSync(address)
            }
        }
    }

    // ═══════════════════════════════════════════
    //  Wallet Creation / Import
    // ═══════════════════════════════════════════

    fun createWallet() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val address = repository.createWallet()
                _uiState.update {
                    it.copy(address = address, hasWallet = true, isLoading = false)
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
                    it.copy(address = address, hasWallet = true, isLoading = false)
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
                    // Subscribe to real-time address updates
                    repository.subscribeToAddress(address)
                    // Subscribe to new blocks
                    repository.subscribeToBlocks { _ ->
                        viewModelScope.launch { refreshData() }
                    }
                    // Initial sync
                    repository.refreshWalletData(address)
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
                        repository.subscribeToAddress(address)
                        repository.refreshWalletData(address)
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
        // Observe balance
        viewModelScope.launch {
            repository.getBalanceMEWC(address).collect { balance ->
                _uiState.update { it.copy(balance = balance) }
            }
        }

        // Observe transactions
        viewModelScope.launch {
            repository.getRecentTransactions(address).collect { txs ->
                _uiState.update { it.copy(transactions = txs) }
            }
        }
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
                repository.refreshWalletData(address)
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
                        // Refresh data after sending
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

    fun getWIF(): String? {
        return repository.getWIF(_uiState.value.address)
    }

    fun deleteWallet() {
        val address = _uiState.value.address
        viewModelScope.launch {
            repository.disconnect()
            repository.deleteWallet(address)
            secureKeyStore.clearAll()
            _uiState.value = WalletUiState()
            _sendState.value = SendUiState()
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
    val transactions: List<TransactionEntity> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

data class SendUiState(
    val isSending: Boolean = false,
    val error: String? = null,
    val successTxId: String? = null
)
