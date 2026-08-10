package com.meowcoin.wallet

import android.os.Bundle
import android.view.WindowManager
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meowcoin.wallet.crypto.BiometricHelper
import com.meowcoin.wallet.ui.navigation.MeowcoinNavHost
import com.meowcoin.wallet.ui.theme.MeowcoinWalletTheme
import com.meowcoin.wallet.viewmodel.WalletViewModel

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
                val lifecycleOwner = LocalLifecycleOwner.current
                var isForeground by remember {
                    mutableStateOf(
                        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    )
                }

                DisposableEffect(lifecycleOwner, needsBiometric) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> isForeground = true
                            Lifecycle.Event.ON_STOP -> {
                                isForeground = false
                                if (needsBiometric) unlocked = false
                            }
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                LaunchedEffect(needsBiometric) {
                    if (needsBiometric) unlocked = false else unlocked = true
                }

                // Prompt when first opened and whenever the app returns from the background.
                LaunchedEffect(needsBiometric, unlocked, isForeground) {
                    if (needsBiometric && !unlocked && isForeground) {
                        biometricHelper.authenticate(
                            activity = this@MainActivity,
                            title = "Unlock Wallet",
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
                                "Multi-Coin Wallet",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("Authenticate to unlock")
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = {
                                biometricHelper.authenticate(
                                    activity = this@MainActivity,
                                    title = "Unlock Wallet",
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
