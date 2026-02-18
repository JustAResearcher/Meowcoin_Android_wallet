package com.meowcoin.wallet.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    balance: String,
    fiatBalance: String,
    address: String,
    transactions: List<TransactionEntity>,
    assets: List<AssetEntity>,
    isLoading: Boolean,
    onSendClick: () -> Unit,
    onReceiveClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onTransactionClick: (String) -> Unit
) {
    // Tab state: 0 = Transactions, 1 = Assets
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.meowcoin_logo),
                            contentDescription = "Meowcoin",
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Meowcoin",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
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
                    BalanceCard(balance = balance, fiatBalance = fiatBalance)
                }

                // Action Buttons
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    ActionButtonsRow(
                        onSendClick = onSendClick,
                        onReceiveClick = onReceiveClick,
                        onRefreshClick = onRefreshClick
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Address display
                item {
                    AddressDisplay(address = address)
                }

                // Tab selector: Transactions / Assets
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
                                    subtitle = "Your Meowcoin transactions will appear here"
                                )
                            }
                        } else {
                            items(transactions, key = { it.txId }) { tx ->
                                val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                                val timestamp = dateFormat.format(Date(tx.timestamp))
                                val displayAddress = if (tx.amount > 0) tx.fromAddress else tx.toAddress

                                TransactionItem(
                                    txId = tx.txId,
                                    amount = tx.amount,
                                    address = displayAddress,
                                    timestamp = timestamp,
                                    status = tx.status,
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
