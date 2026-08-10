package com.meowcoin.wallet.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meowcoin.wallet.R
import com.meowcoin.wallet.data.local.AssetEntity
import com.meowcoin.wallet.data.local.TransactionEntity
import com.meowcoin.wallet.ui.components.*
import com.meowcoin.wallet.ui.theme.MeowOrange
import java.text.SimpleDateFormat
import java.util.*

data class CoinUiOption(
    val id: String,
    val name: String,
    val ticker: String,
    val enabled: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    balance: String,
    fiatBalance: String,
    address: String,
    transactions: List<TransactionEntity>,
    assets: List<AssetEntity>,
    isLoading: Boolean,
    coinId: String = "mewc",
    coinName: String = "Meowcoin",
    ticker: String = "MEWC",
    coinOptions: List<CoinUiOption> = emptyList(),
    supportsAssets: Boolean = true,
    supportsAshCats: Boolean = true,
    actionsEnabled: Boolean = true,
    onCoinSelected: (String) -> Unit = {},
    onSendClick: () -> Unit,
    onReceiveClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onAshCatsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onTransactionClick: (String) -> Unit
) {
    // Tab state: 0 = Transactions, 1 = Assets
    var selectedTab by remember { mutableIntStateOf(0) }
    var coinMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(supportsAssets) {
        if (!supportsAssets) selectedTab = 0
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        Row(
                            modifier = Modifier.clickable(enabled = actionsEnabled) {
                                coinMenuExpanded = true
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (coinId == "mewc") {
                                Image(
                                    painter = painterResource(id = R.drawable.meowcoin_logo),
                                    contentDescription = coinName,
                                    modifier = Modifier.size(32.dp)
                                )
                            } else {
                                Surface(
                                    modifier = Modifier.size(32.dp),
                                    shape = CircleShape,
                                    color = MeowOrange
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            ticker.take(4),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                coinName,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(Icons.Default.ArrowDropDown, "Select coin")
                        }
                        DropdownMenu(
                            expanded = coinMenuExpanded,
                            onDismissRequest = { coinMenuExpanded = false }
                        ) {
                            coinOptions.forEach { coin ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (coin.enabled) "${coin.name} (${coin.ticker})"
                                            else "${coin.name} (${coin.ticker}) - unavailable"
                                        )
                                    },
                                    enabled = coin.enabled,
                                    onClick = {
                                        coinMenuExpanded = false
                                        onCoinSelected(coin.id)
                                    }
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick, enabled = actionsEnabled) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                MeowLoading("Fetching your coins...")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Balance Card (with fiat)
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    BalanceCard(balance = balance, fiatBalance = fiatBalance, ticker = ticker)
                }

                // Action Buttons
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    ActionButtonsRow(
                        onSendClick = { if (actionsEnabled) onSendClick() },
                        onReceiveClick = { if (actionsEnabled) onReceiveClick() },
                        onRefreshClick = { if (actionsEnabled) onRefreshClick() }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (supportsAshCats) {
                    item {
                        AshCatsCard(onClick = onAshCatsClick)
                    }
                }

                // Address display
                item {
                    AddressDisplay(address = address)
                }

                // Tab selector: Transactions / Assets
                if (supportsAssets) {
                    item {
                        TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = MaterialTheme.colorScheme.background,
                        contentColor = MeowOrange
                        ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Transactions") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Assets (${assets.size})") }
                        )
                        }
                    }
                }

                when (selectedTab) {
                    0 -> {
                        // Transaction header
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Recent Transactions",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${transactions.size} total",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (transactions.isEmpty()) {
                            item {
                                EmptyState(
                                    icon = Icons.Default.Receipt,
                                    title = "No transactions yet",
                                    subtitle = "Your $coinName transactions will appear here"
                                )
                            }
                        } else {
                            items(
                                transactions,
                                key = { "$coinId:${it.txId}:${it.walletAddress}" }
                            ) { tx ->
                                val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                                val timestamp = dateFormat.format(Date(tx.timestamp))
                                val displayAddress = if (tx.amount > 0) tx.fromAddress else tx.toAddress

                                TransactionItem(
                                    txId = tx.txId,
                                    amount = tx.amount,
                                    address = displayAddress,
                                    timestamp = timestamp,
                                    status = tx.status,
                                    ticker = ticker,
                                    onClick = { onTransactionClick(tx.txId) }
                                )
                            }
                        }
                    }
                    1 -> {
                        if (assets.isEmpty()) {
                            item {
                                EmptyState(
                                    icon = Icons.Default.Token,
                                    title = "No assets yet",
                                    subtitle = "Meowcoin assets you own will appear here"
                                )
                            }
                        } else {
                            items(assets, key = { it.id }) { asset ->
                                AssetItem(asset = asset)
                            }
                        }
                    }
                }

                // Bottom spacer
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}
