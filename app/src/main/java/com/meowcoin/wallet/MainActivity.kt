package com.meowcoin.wallet

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meowcoin.wallet.crypto.BiometricHelper
import com.meowcoin.wallet.ui.navigation.MeowcoinNavHost
import com.meowcoin.wallet.ui.theme.MeowcoinWalletTheme
import com.meowcoin.wallet.viewmodel.WalletViewModel

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MeowcoinWalletTheme {
                val vm: WalletViewModel = viewModel()
                val uiState by vm.uiState.collectAsState()
                val biometricHelper = remember { BiometricHelper(this@MainActivity) }
                val needsBiometric = uiState.hasWallet &&
                        uiState.biometricEnabled &&
                        biometricHelper.isBiometricAvailable()
                var unlocked by remember { mutableStateOf(!needsBiometric) }

                // Prompt biometric on first composition when needed
                LaunchedEffect(needsBiometric) {
                    if (needsBiometric && !unlocked) {
                        biometricHelper.authenticate(
                            activity = this@MainActivity,
                            title = "Unlock Meowcoin Wallet",
                            subtitle = "Authenticate to access your wallet",
                            negativeButtonText = "Cancel",
                            onSuccess = { unlocked = true },
                            onError = { _, _ -> /* stay locked */ }
                        )
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!needsBiometric || unlocked) {
                        MeowcoinNavHost(viewModel = vm)
                    } else {
                        // Lock screen
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Meowcoin Wallet",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("Authenticate to unlock")
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = {
                                biometricHelper.authenticate(
                                    activity = this@MainActivity,
                                    title = "Unlock Meowcoin Wallet",
                                    subtitle = "Authenticate to access your wallet",
                                    negativeButtonText = "Cancel",
                                    onSuccess = { unlocked = true },
                                    onError = { _, _ -> /* stay locked */ }
                                )
                            }) {
                                Text("Unlock")
                            }
                        }
                    }
                }
            }
        }
    }
}
