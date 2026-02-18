package com.meowcoin.wallet.ui.navigation

import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.meowcoin.wallet.crypto.BiometricHelper
import com.meowcoin.wallet.data.remote.ElectrumClient
import com.meowcoin.wallet.ui.screens.*
import com.meowcoin.wallet.viewmodel.WalletViewModel

/**
 * Navigation routes.
 */
object Routes {
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val SEND = "send"
    const val RECEIVE = "receive"
    const val SETTINGS = "settings"
}

@Composable
fun MeowcoinNavHost(
    viewModel: WalletViewModel = viewModel(),
    navController: NavHostController = rememberNavController()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sendState by viewModel.sendState.collectAsStateWithLifecycle()
    val mnemonicState by viewModel.mnemonicState.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val serverInfo by viewModel.serverInfo.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val biometricAvailable = remember { BiometricHelper.isBiometricAvailable(context) }
    val startDestination = if (uiState.hasWallet) Routes.HOME else Routes.WELCOME

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // ── Welcome / Setup ──
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onCreateHdWallet = {
                    viewModel.createHdWallet()
                },
                onImportMnemonic = { mnemonic ->
                    viewModel.importHdWallet(mnemonic)
                },
                onImportWIF = { wif ->
                    viewModel.importWalletFromWIF(wif)
                },
                mnemonicWords = mnemonicState.words,
                onMnemonicBackedUp = {
                    viewModel.confirmMnemonicBackup()
                },
                isLoading = uiState.isLoading,
                errorMessage = uiState.error
            )

            // Navigate to Home when wallet is created and mnemonic backed up (or non-HD)
            LaunchedEffect(uiState.hasWallet, mnemonicState.isBackedUp, uiState.isHdWallet) {
                if (uiState.hasWallet && (!uiState.isHdWallet || mnemonicState.isBackedUp)) {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                }
            }
        }

        // ── Home ──
        composable(Routes.HOME) {
            HomeScreen(
                balance = uiState.balance,
                fiatBalance = uiState.fiatBalance,
                address = uiState.address,
                transactions = uiState.transactions,
                assets = uiState.assets,
                isLoading = uiState.isLoading,
                onSendClick = {
                    viewModel.clearSendState()
                    navController.navigate(Routes.SEND)
                },
                onReceiveClick = {
                    navController.navigate(Routes.RECEIVE)
                },
                onRefreshClick = {
                    viewModel.refreshData()
                },
                onSettingsClick = {
                    navController.navigate(Routes.SETTINGS)
                },
                onTransactionClick = { /* TX detail - future enhancement */ }
            )
        }

        // ── Send ──
        composable(Routes.SEND) {
            SendScreen(
                balance = uiState.balance,
                onSend = { address, amount ->
                    viewModel.sendMEWC(address, amount)
                },
                onScanQR = { /* QR scanning - future enhancement */ },
                onBack = { navController.popBackStack() },
                isSending = sendState.isSending,
                errorMessage = sendState.error,
                successTxId = sendState.successTxId
            )
        }

        // ── Receive ──
        composable(Routes.RECEIVE) {
            ReceiveScreen(
                address = uiState.address,
                onBack = { navController.popBackStack() },
                onShare = { address ->
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, address)
                        putExtra(Intent.EXTRA_SUBJECT, "My Meowcoin Address")
                    }
                    context.startActivity(
                        Intent.createChooser(shareIntent, "Share Address")
                    )
                }
            )
        }

        // ── Settings ──
        composable(Routes.SETTINGS) {
            var wif by remember { mutableStateOf<String?>(null) }
            var seedPhrase by remember { mutableStateOf<String?>(null) }

            SettingsScreen(
                address = uiState.address,
                wif = wif,
                seedPhrase = seedPhrase,
                isHdWallet = uiState.isHdWallet,
                biometricEnabled = uiState.biometricEnabled,
                biometricAvailable = biometricAvailable,
                allAddresses = uiState.allAddresses,
                connectionState = connectionState,
                serverHost = serverInfo?.host ?: "",
                serverVersion = serverInfo?.serverVersion ?: "",
                blockHeight = serverInfo?.blockHeight ?: 0,
                onExportPrivateKey = {
                    wif = viewModel.getWIF()
                },
                onShowSeedPhrase = {
                    seedPhrase = viewModel.getSeedPhrase()
                },
                onToggleBiometric = { enabled ->
                    viewModel.setBiometricEnabled(enabled)
                },
                onDeriveNewAddress = {
                    viewModel.deriveNextAddress()
                },
                onConnectCustomServer = { host, port, useSSL ->
                    viewModel.connectToCustomServer(host, port, useSSL)
                },
                onReconnect = {
                    viewModel.reconnect()
                },
                onDeleteWallet = {
                    viewModel.deleteWallet()
                    navController.navigate(Routes.WELCOME) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
