package com.meowcoin.wallet.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meowcoin.wallet.R
import com.meowcoin.wallet.ui.components.AddressDisplay
import com.meowcoin.wallet.ui.theme.MeowOrange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    address: String,
    onBack: () -> Unit,
    onShare: (String) -> Unit,
    coinName: String = "Meowcoin",
    ticker: String = "MEWC",
    paymentUri: String = address,
    rawAddressRequiresNetworkTag: Boolean = false
) {
    val clipboardManager = LocalClipboardManager.current
    val primaryCopyContent = if (rawAddressRequiresNetworkTag) paymentUri else address
    var showRawAddressWarning by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receive $ticker", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onShare(paymentUri) }) {
                        Icon(Icons.Default.Share, "Share Address")
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (ticker == "MEWC") {
                Image(
                    painter = painterResource(id = R.drawable.meowcoin_logo),
                    contentDescription = coinName,
                    modifier = Modifier.size(80.dp)
                )
            } else {
                Surface(
                    modifier = Modifier.size(80.dp),
                    shape = CircleShape,
                    color = MeowOrange
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = ticker,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Your $coinName Address",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Share this address or scan the QR code to receive $ticker",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // QR Code and address
            AddressDisplay(
                address = address,
                showQR = true,
                qrContent = paymentUri,
                copyContent = primaryCopyContent
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Copy button
            Button(
                onClick = {
                    clipboardManager.setText(AnnotatedString(primaryCopyContent))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MeowOrange),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    if (rawAddressRequiresNetworkTag) {
                        "Copy Network-Tagged Request"
                    } else {
                        "Copy Address"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (rawAddressRequiresNetworkTag) {
                TextButton(onClick = { showRawAddressWarning = true }) {
                    Text("Copy raw address")
                }
            }
        }
    }

    if (showRawAddressWarning) {
        AlertDialog(
            onDismissRequest = { showRawAddressWarning = false },
            title = { Text("Ambiguous raw address") },
            text = {
                Text(
                    "This address can be interpreted differently by another coin. " +
                        "Only copy it raw when the sender has independently selected $coinName ($ticker)."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(address))
                        showRawAddressWarning = false
                    }
                ) {
                    Text("Copy $ticker address")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRawAddressWarning = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
