package com.meowcoin.wallet.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meowcoin.wallet.R
import com.meowcoin.wallet.ui.theme.MeowOrange

@Composable
fun WelcomeScreen(
    onCreateHdWallet: () -> Unit,
    onImportMnemonic: (String) -> Unit,
    onImportWIF: (String) -> Unit,
    mnemonicWords: List<String>,
    onMnemonicBackedUp: () -> Unit,
    isLoading: Boolean = false,
    errorMessage: String? = null
) {
    var showImportDialog by remember { mutableStateOf(false) }
    var showMnemonicImportDialog by remember { mutableStateOf(false) }
    var wifInput by remember { mutableStateOf("") }
    var mnemonicInput by remember { mutableStateOf("") }
    var showWif by remember { mutableStateOf(false) }

    // If mnemonic words are being displayed, show the backup screen
    if (mnemonicWords.isNotEmpty()) {
        MnemonicBackupScreen(
            words = mnemonicWords,
            onConfirmBackup = onMnemonicBackedUp
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Meowcoin logo
        Image(
            painter = painterResource(id = R.drawable.meowcoin_logo),
            contentDescription = "Meowcoin Logo",
            modifier = Modifier.size(120.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Meowcoin Wallet",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = MeowOrange
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "HD Light Client",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "A secure, lightweight wallet for MEWC.\nPowered by Electrum servers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Error
        errorMessage?.let { error ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (isLoading) {
            CircularProgressIndicator(color = MeowOrange)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Creating wallet...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // Create HD wallet (primary action)
            Button(
                onClick = onCreateHdWallet,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MeowOrange),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Create New Wallet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Restore from seed phrase
            OutlinedButton(
                onClick = { showMnemonicImportDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MeowOrange),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Restore from Seed Phrase",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Import WIF (legacy)
            TextButton(
                onClick = { showImportDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Import WIF Private Key",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Your keys, your coins",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // Mnemonic import dialog
    if (showMnemonicImportDialog) {
        AlertDialog(
            onDismissRequest = { showMnemonicImportDialog = false },
            title = { Text("Restore Wallet") },
            text = {
                Column {
                    Text(
                        "Enter your 12 or 24 word seed phrase, separated by spaces:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = mnemonicInput,
                        onValueChange = { mnemonicInput = it },
                        label = { Text("Seed Phrase") },
                        placeholder = { Text("word1 word2 word3 ...") },
                        minLines = 3,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MeowOrange
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    val wordCount = mnemonicInput.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.size
                    Text(
                        "$wordCount words entered",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (wordCount == 12 || wordCount == 24)
                            MeowOrange else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showMnemonicImportDialog = false
                        onImportMnemonic(mnemonicInput.trim())
                        mnemonicInput = ""
                    },
                    enabled = mnemonicInput.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.size in listOf(12, 24),
                    colors = ButtonDefaults.buttonColors(containerColor = MeowOrange)
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showMnemonicImportDialog = false
                    mnemonicInput = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Import WIF dialog (legacy)
    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Import WIF Key") },
            text = {
                Column {
                    Text(
                        "Enter your WIF (Wallet Import Format) private key:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = wifInput,
                        onValueChange = { wifInput = it },
                        label = { Text("WIF Private Key") },
                        placeholder = { Text("5... or K... or L...") },
                        singleLine = true,
                        visualTransformation = if (showWif)
                            VisualTransformation.None
                        else
                            PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showWif = !showWif }) {
                                Icon(
                                    if (showWif) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    "Toggle visibility"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MeowOrange
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Never share your private key with anyone",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showImportDialog = false
                        onImportWIF(wifInput.trim())
                        wifInput = ""
                    },
                    enabled = wifInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MeowOrange)
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    wifInput = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Screen to show the mnemonic seed words for backup.
 */
@Composable
fun MnemonicBackupScreen(
    words: List<String>,
    onConfirmBackup: () -> Unit
) {
    var confirmed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Icon(
            Icons.Default.Security,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MeowOrange
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Back Up Your Seed Phrase",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Write down these ${words.size} words in order. This is the ONLY way to recover your wallet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Word grid
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Show words in 2 columns
                for (row in words.indices step 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        WordChip(
                            index = row + 1,
                            word = words[row],
                            modifier = Modifier.weight(1f)
                        )
                        if (row + 1 < words.size) {
                            WordChip(
                                index = row + 2,
                                word = words[row + 1],
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Never share your seed phrase. Anyone with it can access your funds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(
                checked = confirmed,
                onCheckedChange = { confirmed = it }
            )
            Text(
                "I have written down my seed phrase",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onConfirmBackup,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = confirmed,
            colors = ButtonDefaults.buttonColors(containerColor = MeowOrange),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                "I've Backed Up My Seed Phrase",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun WordChip(
    index: Int,
    word: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .background(
                MaterialTheme.colorScheme.surface,
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$index.",
            style = MaterialTheme.typography.labelSmall,
            color = MeowOrange,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = word,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
