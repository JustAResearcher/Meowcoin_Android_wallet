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
import com.meowcoin.wallet.data.local.WalletEntity
import com.meowcoin.wallet.data.remote.ElectrumClient
import com.meowcoin.wallet.ui.theme.MeowGreen
import com.meowcoin.wallet.ui.theme.MeowOrange
import com.meowcoin.wallet.ui.theme.MeowRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    address: String,
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
    onExportPrivateKey: () -> Unit,
    onShowSeedPhrase: () -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
    onDeriveNewAddress: () -> Unit,
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
                "Network (Light Client)",
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
                        SettingRow("Client Mode", "SPV Light Client")
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
                    SettingRow("Network", "Meowcoin Mainnet")
                    SettingRow("Address Type", "P2PKH (Legacy)")
                    SettingRow("Wallet Type", if (isHdWallet) "HD (BIP44)" else "Single Key")
                    if (isHdWallet) {
                        SettingRow("Addresses", "${allAddresses.size}")
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
                "Meowcoin Wallet v1.0.1",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
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
