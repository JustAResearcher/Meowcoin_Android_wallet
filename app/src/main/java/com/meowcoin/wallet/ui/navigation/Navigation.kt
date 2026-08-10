package com.meowcoin.wallet.ui.navigation

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.meowcoin.wallet.BuildConfig
import com.meowcoin.wallet.ashcats.buildAshCatsUrl
import com.meowcoin.wallet.crypto.AmountCodec
import com.meowcoin.wallet.crypto.BiometricHelper
import com.meowcoin.wallet.crypto.CoinRegistry
import com.meowcoin.wallet.crypto.PaymentUriCodec
import com.meowcoin.wallet.ui.screens.*
import com.meowcoin.wallet.viewmodel.WalletViewModel

/**
 * Navigation routes.
 */
object Routes {
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val SEND = "send"
    const val QR_SCAN = "qr_scan"
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
    val consolidationState by viewModel.consolidationState.collectAsStateWithLifecycle()
    val mnemonicState by viewModel.mnemonicState.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val serverInfo by viewModel.serverInfo.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        val message = uiState.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = message,
            actionLabel = "Dismiss",
            withDismissAction = true,
            duration = SnackbarDuration.Long
        )
        viewModel.clearError()
    }

    val activeProfile = remember(uiState.coinId) {
        CoinRegistry.findById(uiState.coinId) ?: CoinRegistry.MEWC
    }
    val coinOptions = remember(uiState.coinId, uiState.isHdWallet) {
        CoinRegistry.all.map { profile ->
            CoinUiOption(
                id = profile.id,
                name = profile.name,
                ticker = profile.ticker,
                enabled = profile.enabled &&
                    (uiState.isHdWallet || profile.id == uiState.coinId)
            )
        }
    }
    val paymentUri = remember(activeProfile, uiState.receiveAddress, uiState.address) {
        val receiveAddress = uiState.receiveAddress.ifBlank { uiState.address }
        if (receiveAddress.isBlank()) {
            ""
        } else {
            runCatching {
                PaymentUriCodec.build(activeProfile, receiveAddress)
            }.getOrDefault(receiveAddress)
        }
    }

    val biometricAvailable = remember { BiometricHelper.isBiometricAvailable(context) }
    val startDestination = if (uiState.hasWallet && mnemonicState.isBackedUp) {
        Routes.HOME
    } else {
        Routes.WELCOME
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize()
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

            // All non-HD/imported wallets start backed up; newly generated seeds do not.
            LaunchedEffect(uiState.hasWallet, mnemonicState.isBackedUp) {
                if (uiState.hasWallet && mnemonicState.isBackedUp) {
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
                coinId = uiState.coinId,
                coinName = uiState.coinName,
                ticker = uiState.ticker,
                coinOptions = coinOptions,
                supportsAssets = uiState.coinId == CoinRegistry.MEWC.id,
                supportsAshCats = uiState.coinId == CoinRegistry.MEWC.id,
                actionsEnabled = !uiState.isSwitchingCoin,
                onCoinSelected = viewModel::selectCoin,
                onSendClick = {
                    if (!uiState.isSwitchingCoin) {
                        viewModel.clearSendState()
                        navController.navigate(Routes.SEND)
                    }
                },
                onReceiveClick = {
                    if (!uiState.isSwitchingCoin) {
                        navController.navigate(Routes.RECEIVE)
                    }
                },
                onRefreshClick = {
                    viewModel.refreshData()
                },
                onAshCatsClick = {
                    if (uiState.coinId != CoinRegistry.MEWC.id) return@HomeScreen
                    val ashCatsUri = Uri.parse(
                        buildAshCatsUrl(BuildConfig.ASH_CATS_URL, uiState.address)
                    )
                    val customTab = CustomTabsIntent.Builder()
                        .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
                        .setShowTitle(true)
                        .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                        .build()

                    try {
                        customTab.launchUrl(context, ashCatsUri)
                    } catch (_: ActivityNotFoundException) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, ashCatsUri))
                    }
                },
                onSettingsClick = {
                    if (!uiState.isSwitchingCoin) {
                        navController.navigate(Routes.SETTINGS)
                    }
                },
                onTransactionClick = { /* TX detail - future enhancement */ }
            )
        }

        // ── Send ──
        composable(Routes.SEND) { backStackEntry ->
            val scannedPayment by backStackEntry.savedStateHandle
                .getStateFlow<String?>(SCANNED_PAYMENT_KEY, null)
                .collectAsStateWithLifecycle()
            val scannedRequest = remember(scannedPayment, activeProfile) {
                scannedPayment?.let { value ->
                    runCatching {
                        PaymentUriCodec.parseSendTarget(value, activeProfile)
                    }.getOrNull()
                }
            }

            SendScreen(
                balance = uiState.balance,
                coinName = uiState.coinName,
                ticker = uiState.ticker,
                addressPlaceholder = "${uiState.coinName} address",
                scannedPayment = scannedPayment,
                scannedPaymentAmount = scannedRequest?.amountAtomic?.let { amount ->
                    AmountCodec.formatAtomic(amount, activeProfile)
                },
                previewAddress = sendState.preview?.toAddress,
                previewNetwork = sendState.preview?.network,
                previewDestinationType = sendState.preview?.destinationType,
                previewNetworkTagged = sendState.preview?.networkTagged == true,
                previewAmount = sendState.preview?.amount,
                previewFee = sendState.preview?.fee,
                onConfirmSend = viewModel::confirmPreparedSend,
                onCancelSend = viewModel::cancelPreparedSend,
                onSend = { address, amount, sendAll ->
                    viewModel.sendCoin(address, amount, sendAll)
                },
                onScanQR = {
                    backStackEntry.savedStateHandle[SCANNED_PAYMENT_KEY] = null
                    navController.navigate(Routes.QR_SCAN)
                },
                onBack = {
                    if (!sendState.isSending) {
                        viewModel.cancelPreparedSend()
                        navController.popBackStack()
                    }
                },
                isSending = sendState.isSending,
                errorMessage = sendState.error,
                successTxId = sendState.successTxId
            )
        }

        // ── Payment QR scanner ──
        composable(Routes.QR_SCAN) {
            QrScannerScreen(
                coinName = uiState.coinName,
                isResultAccepted = { value ->
                    runCatching {
                        PaymentUriCodec.parseSendTarget(value, activeProfile)
                    }.isSuccess
                },
                onResult = { scannedPayment ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set(SCANNED_PAYMENT_KEY, scannedPayment.trim())
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }

        // ── Receive ──
        composable(Routes.RECEIVE) {
            val receiveAddress = uiState.receiveAddress.ifBlank { uiState.address }
            val rawAddressRequiresNetworkTag = remember(receiveAddress, activeProfile) {
                receiveAddress.isNotBlank() && runCatching {
                    PaymentUriCodec.parseSendTarget(receiveAddress, activeProfile)
                }.isFailure
            }
            ReceiveScreen(
                address = receiveAddress,
                coinName = uiState.coinName,
                ticker = uiState.ticker,
                paymentUri = paymentUri,
                rawAddressRequiresNetworkTag = rawAddressRequiresNetworkTag,
                onBack = { navController.popBackStack() },
                onShare = { uri ->
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "My ${uiState.coinName} Address")
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
                coinName = uiState.coinName,
                ticker = uiState.ticker,
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
                consolidationState = consolidationState,
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
                onRescanAddresses = {
                    viewModel.rescanAddresses()
                },
                onPrepareConsolidation = {
                    viewModel.prepareConsolidation()
                },
                onConsolidate = {
                    viewModel.consolidateUtxos()
                },
                onDismissConsolidation = {
                    viewModel.clearConsolidationState()
                },
                onConnectCustomServer = { host, port, useSSL ->
                    viewModel.connectToCustomServer(host, port, useSSL)
                },
                onReconnect = {
                    viewModel.reconnect()
                },
                onDeleteWallet = {
                    viewModel.deleteWallet {
                        navController.navigate(Routes.WELCOME) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }
}

private const val SCANNED_PAYMENT_KEY = "scannedPayment"
