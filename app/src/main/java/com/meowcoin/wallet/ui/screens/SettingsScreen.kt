package com.meowcoin.wallet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meowcoin.wallet.BuildConfig
import com.meowcoin.wallet.data.local.WalletEntity
import com.meowcoin.wallet.data.remote.ElectrumClient
import com.meowcoin.wallet.data.repository.WalletRepository
import com.meowcoin.wallet.ui.theme.MeowGreen
import com.meowcoin.wallet.ui.theme.MeowOrange
import com.meowcoin.wallet.ui.theme.MeowRed
import com.meowcoin.wallet.viewmodel.ConsolidationUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    address: String,
    coinName: String = "Meowcoin",
    ticker: String = "MEWC",
    wif: String?,
    seedPhrase: String?,
    isHdWallet: Boolean,
    biometricEnabled: Boolean,
    biometricAvailable: Boolean,
    allAddresses: List<WalletEntity>,
    connectionState: ElectrumClient.ConnectionState,
    serverHost: String,
    serverVersion: String,
    blockHeight: Int,
    consolidationState: ConsolidationUiState,
    onExportPrivateKey: () -> Unit,
    onShowSeedPhrase: () -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
    onDeriveNewAddress: () -> Unit,
    onRescanAddresses: () -> Unit,
    onPrepareConsolidation: () -> Unit,
    onConsolidate: () -> Unit,
    onDismissConsolidation: () -> Unit,
    onConnectCustomServer: (host: String, port: Int, useSSL: Boolean) -> Unit,
    onReconnect: () -> Unit,
    onDeleteWallet: () -> Unit,
    onBack: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showPrivateKey by remember { mutableStateOf(false) }
    var showSeedPhrase by remember { mutableStateOf(false) }
    var showCustomServer by remember { mutableStateOf(false) }
    var showAddresses by remember { mutableStateOf(false) }
    var showConsolidation by remember { mutableStateOf(false) }
    var customHost by remember { mutableStateOf("") }
    var customPort by remember { mutableStateOf("50002") }
    var customSSL by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Network / Electrum Status ──
            Text(
                "$coinName Network",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val statusColor = when (connectionState) {
                            ElectrumClient.ConnectionState.CONNECTED -> MeowGreen
                            ElectrumClient.ConnectionState.CONNECTING,
                            ElectrumClient.ConnectionState.RECONNECTING -> MeowOrange
                            else -> MeowRed
                        }
                        Icon(
                            Icons.Default.Circle,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = when (connectionState) {
                                ElectrumClient.ConnectionState.CONNECTED -> "Connected"
                                ElectrumClient.ConnectionState.CONNECTING -> "Connecting..."
                                ElectrumClient.ConnectionState.RECONNECTING -> "Reconnecting..."
                                ElectrumClient.ConnectionState.ERROR -> "Connection Error"
                                ElectrumClient.ConnectionState.DISCONNECTED -> "Disconnected"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor
                        )
                    }

                    if (serverHost.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        SettingRow("Server", serverHost)
                        SettingRow("Version", serverVersion)
                        SettingRow("Block Height", if (blockHeight > 0) blockHeight.toString() else "—")
                        SettingRow("Protocol", "Electrum (Stratum)")
                        SettingRow("Client Mode", "Electrum light client")
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onReconnect,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Reconnect")
                        }
                        OutlinedButton(
                            onClick = { showCustomServer = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Dns, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Custom Server")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Security ──
            Text(
                "Security",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // Biometric toggle
            if (biometricAvailable) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Biometric Authentication",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Require fingerprint/face to open wallet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = biometricEnabled,
                            onCheckedChange = onToggleBiometric,
                            colors = SwitchDefaults.colors(checkedThumbColor = MeowOrange)
                        )
                    }
                }
            }

            // ── Wallet Info ──
            Text(
                "Wallet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingRow("Address", "${address.take(12)}...${address.takeLast(8)}")
                    SettingRow("Network", "$coinName Mainnet")
                    SettingRow("Address Type", "P2PKH (Legacy)")
                    SettingRow("Wallet Type", if (isHdWallet) "HD (BIP44)" else "Single Key")
                    if (isHdWallet) {
                        SettingRow("Addresses", "${allAddresses.size}")
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "UTXO Management",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Combine many confirmed outputs into one to make future sends smaller.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            showConsolidation = true
                            onPrepareConsolidation()
                        },
                        enabled = connectionState == ElectrumClient.ConnectionState.CONNECTED,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AccountBalanceWallet, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Consolidate UTXOs")
                    }
                    if (connectionState != ElectrumClient.ConnectionState.CONNECTED) {
                        Text(
                            "Connect to an Electrum server before consolidating.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // Address management (HD wallets)
            if (isHdWallet) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Address Management",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))

                        if (showAddresses) {
                            allAddresses.filter { !it.isChange }.forEach { wallet ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        "${wallet.label}: ${wallet.address.take(10)}...${wallet.address.takeLast(6)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showAddresses = !showAddresses },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    if (showAddresses) Icons.Default.VisibilityOff else Icons.Default.List,
                                    null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(if (showAddresses) "Hide" else "Show Addresses")
                            }
                            OutlinedButton(
                                onClick = onDeriveNewAddress,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("New Address")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onRescanAddresses,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Rescan Addresses")
                        }
                        Text(
                            "Re-scans the chain for any used addresses derived from your seed.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // Seed phrase backup (HD wallets)
            if (isHdWallet) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Seed Phrase",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))

                        if (showSeedPhrase && seedPhrase != null) {
                            Text(
                                text = seedPhrase,
                                style = MaterialTheme.typography.bodySmall,
                                color = MeowRed
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Never share this with anyone! Write it down and store safely.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MeowRed
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                if (showSeedPhrase) {
                                    showSeedPhrase = false
                                } else {
                                    onShowSeedPhrase()
                                    showSeedPhrase = true
                                }
                            }
                        ) {
                            Icon(
                                if (showSeedPhrase) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (showSeedPhrase) "Hide Phrase" else "Show Seed Phrase")
                        }
                    }
                }
            }

            // Export private key (legacy or per-address)
            if (!isHdWallet) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Private Key (WIF)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))

                        if (showPrivateKey && wif != null) {
                            Text(
                                text = wif,
                                style = MaterialTheme.typography.bodySmall,
                                color = MeowRed
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Never share this with anyone!",
                                style = MaterialTheme.typography.labelSmall,
                                color = MeowRed
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                if (showPrivateKey) {
                                    showPrivateKey = false
                                } else {
                                    onExportPrivateKey()
                                    showPrivateKey = true
                                }
                            }
                        ) {
                            Icon(
                                if (showPrivateKey) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (showPrivateKey) "Hide Key" else "Show Private Key")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Danger Zone ──
            Text(
                "Danger Zone",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MeowRed
            )

            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MeowRed)
            ) {
                Icon(Icons.Default.Delete, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Delete Wallet", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                "Multi-Coin Wallet v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }

    if (showConsolidation) {
        val preview = consolidationState.preview
        val closeDialog = {
            showConsolidation = false
            onDismissConsolidation()
        }
        AlertDialog(
            onDismissRequest = {
                if (!consolidationState.isConsolidating) closeDialog()
            },
            title = {
                Text(
                    if (consolidationState.successTxId != null) {
                        "Consolidation Sent"
                    } else {
                        "Consolidate UTXOs?"
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        consolidationState.isLoading -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Checking confirmed UTXOs...")
                            }
                        }

                        consolidationState.successTxId != null -> {
                            Text("The self-transfer was broadcast successfully.")
                            SettingRow(
                                "Transaction",
                                "${consolidationState.successTxId.take(12)}..."
                            )
                            Text(
                                "Wait for it to confirm before running another consolidation.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        consolidationState.error != null -> {
                            Text(consolidationState.error, color = MeowRed)
                        }

                        preview != null -> {
                            SettingRow("Inputs", preview.inputCount.toString())
                            SettingRow(
                                "Total",
                                "${WalletRepository.formatMEWC(preview.totalInput)} $ticker"
                            )
                            SettingRow(
                                "Estimated fee",
                                "${WalletRepository.formatMEWC(preview.estimatedFee)} $ticker"
                            )
                            SettingRow(
                                "New output",
                                "${WalletRepository.formatMEWC(preview.outputAmount)} $ticker"
                            )
                            SettingRow(
                                "UTXOs after confirmation",
                                "~${preview.remainingUtxoCount}"
                            )
                            Text(
                                "This broadcasts one self-transfer from " +
                                    "${preview.sourceAddress.take(10)}... to your main wallet. " +
                                    "Only confirmed UTXOs owned by the same key are included.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                when {
                    consolidationState.successTxId != null ||
                        consolidationState.error != null -> {
                        TextButton(onClick = closeDialog) { Text("Close") }
                    }

                    preview != null -> {
                        Button(
                            onClick = onConsolidate,
                            enabled = !consolidationState.isConsolidating,
                            colors = ButtonDefaults.buttonColors(containerColor = MeowOrange)
                        ) {
                            if (consolidationState.isConsolidating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Broadcasting...")
                            } else {
                                Text("Consolidate")
                            }
                        }
                    }
                }
            },
            dismissButton = {
                if (preview != null &&
                    consolidationState.successTxId == null &&
                    !consolidationState.isConsolidating
                ) {
                    TextButton(onClick = closeDialog) { Text("Cancel") }
                }
            }
        )
    }

    // Delete confirmation
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Wallet?", color = MeowRed) },
            text = {
                Text(
                    if (isHdWallet)
                        "This will permanently remove your wallet from this device. " +
                        "Make sure you have backed up your seed phrase!\n\n" +
                        "This action cannot be undone."
                    else
                        "This will permanently remove your wallet from this device. " +
                        "Make sure you have backed up your private key!\n\n" +
                        "This action cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteWallet()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MeowRed)
                ) {
                    Text("Delete Forever")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Custom server dialog
    if (showCustomServer) {
        AlertDialog(
            onDismissRequest = { showCustomServer = false },
            title = { Text("Custom Electrum Server") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Connect to your own Electrum server:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = customHost,
                        onValueChange = { customHost = it },
                        label = { Text("Host") },
                        placeholder = { Text("electrum.example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customPort,
                        onValueChange = { customPort = it },
                        label = { Text("Port") },
                        placeholder = { Text("50002") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = customSSL,
                            onCheckedChange = { customSSL = it }
                        )
                        Text("Use SSL/TLS", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!customSSL) {
                        Text(
                            "Warning: plaintext servers can read and alter wallet traffic.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MeowRed
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCustomServer = false
                        val port = customPort.toIntOrNull() ?: 50002
                        onConnectCustomServer(customHost.trim(), port, customSSL)
                    },
                    enabled = customHost.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MeowOrange)
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomServer = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
