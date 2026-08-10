package com.meowcoin.wallet.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meowcoin.wallet.ui.theme.MeowOrange
import com.meowcoin.wallet.ui.theme.MeowRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    balance: String,
    onSend: (address: String, amount: String, sendAll: Boolean) -> Unit,
    onScanQR: () -> Unit,
    onBack: () -> Unit,
    coinName: String = "Meowcoin",
    ticker: String = "MEWC",
    addressPlaceholder: String = "",
    estimatedFee: String = "",
    scannedPayment: String? = null,
    scannedPaymentAmount: String? = null,
    previewAddress: String? = null,
    previewNetwork: String? = null,
    previewDestinationType: String? = null,
    previewNetworkTagged: Boolean = false,
    previewAmount: String? = null,
    previewFee: String? = null,
    onConfirmSend: () -> Unit = {},
    onCancelSend: () -> Unit = {},
    isSending: Boolean = false,
    errorMessage: String? = null,
    successTxId: String? = null
) {
    var recipientAddress by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var sendAll by remember { mutableStateOf(false) }

    LaunchedEffect(scannedPayment) {
        val scanned = scannedPayment?.trim().orEmpty()
        if (scanned.isEmpty()) return@LaunchedEffect

        // Keep the complete, coin-tagged URI. The final repository boundary must retain this
        // provenance instead of reinterpreting an ambiguous Base58 payload as a raw address.
        recipientAddress = scanned
        if (!scannedPaymentAmount.isNullOrEmpty()) {
            amount = scannedPaymentAmount
            sendAll = false
        }
    }

    BackHandler(enabled = isSending || previewAddress != null) {
        if (!isSending) {
            onCancelSend()
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send $ticker", fontWeight = FontWeight.Bold) },
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
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Available balance
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Available Balance",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "$balance $ticker",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MeowOrange
                    )
                }
            }

            // Recipient Address
            OutlinedTextField(
                value = recipientAddress,
                onValueChange = { recipientAddress = it },
                label = { Text("Recipient Address") },
                placeholder = {
                    Text(addressPlaceholder.ifBlank { "$coinName address" })
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = onScanQR) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            "Scan QR Code",
                            tint = MeowOrange
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeowOrange,
                    cursorColor = MeowOrange
                )
            )

            // Amount
            OutlinedTextField(
                value = amount,
                onValueChange = { newValue ->
                    // Only allow valid numeric input
                    if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d{0,8}$"))) {
                        amount = newValue
                        sendAll = false
                    }
                },
                label = { Text("Amount ($ticker)") },
                placeholder = { Text("0.00000000") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                trailingIcon = {
                    TextButton(onClick = {
                        amount = balance
                        sendAll = true
                    }) {
                        Text("MAX", color = MeowOrange)
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MeowOrange,
                    cursorColor = MeowOrange
                )
            )

            // Fee estimate
            if (estimatedFee.isNotEmpty()) {
                Text(
                    text = "Estimated fee: $estimatedFee",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Error message
            errorMessage?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MeowRed.copy(alpha = 0.1f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MeowRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            error,
                            color = MeowRed,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Success message
            successTxId?.let { txId ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "🎉 Transaction Sent!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "TX: ${txId.take(16)}...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Send Button
            Button(
                onClick = { onSend(recipientAddress, amount, sendAll) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = recipientAddress.isNotEmpty() && amount.isNotEmpty() && !isSending,
                colors = ButtonDefaults.buttonColors(containerColor = MeowOrange),
                shape = MaterialTheme.shapes.medium
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.Send, "Send", modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Send $ticker",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // Confirmation dialog
    if (previewAddress != null && previewAmount != null && previewFee != null) {
        AlertDialog(
            onDismissRequest = { if (!isSending) onCancelSend() },
            title = { Text("Confirm Transaction") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    previewNetwork?.let { network ->
                        Text("Network: $network", fontWeight = FontWeight.Bold)
                    }
                    previewDestinationType?.let { addressType ->
                        Text("Address type: $addressType")
                    }
                    if (previewNetworkTagged) {
                        Text(
                            "Network-tagged payment request",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text("Send $previewAmount $ticker to:")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        previewAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Network fee: $previewFee $ticker", fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmSend,
                    enabled = !isSending,
                    colors = ButtonDefaults.buttonColors(containerColor = MeowOrange)
                ) {
                    Text(if (isSending) "Broadcasting..." else "Confirm and Broadcast")
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelSend, enabled = !isSending) {
                    Text("Cancel")
                }
            }
        )
    }
}
