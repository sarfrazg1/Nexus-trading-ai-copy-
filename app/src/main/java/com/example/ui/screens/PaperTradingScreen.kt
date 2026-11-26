package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MarketAsset
import com.example.data.paper.PaperClosedTrade
import com.example.data.paper.PaperOrderSide
import com.example.data.paper.PaperOrderType
import com.example.data.paper.PaperPendingOrder
import com.example.data.paper.PaperPortfolioSnapshot
import com.example.data.paper.PaperPosition
import com.example.data.paper.PaperTradePrefill
import com.example.data.paper.PaperTradingEngine
import com.example.data.paper.formatUsdt
import com.example.data.scanner.ManagedSignalEntry
import com.example.data.scanner.ManagedSignalState
import com.example.data.scanner.SignalTransitionRecord
import com.example.data.timemachine.MultiCoinBacktestReport
import com.example.ui.theme.NexusTheme
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperTradingScreen(
    portfolio: PaperPortfolioSnapshot,
    prefill: PaperTradePrefill?,
    assets: List<MarketAsset>,
    feedbackMessage: String?,
    managedSignals: List<ManagedSignalEntry>,
    transitionLog: List<SignalTransitionRecord>,
    backtestReport: MultiCoinBacktestReport?,
    isRunningBacktest: Boolean,
    backtestProgress: String?,
    backtestError: String?,
    onResetWallet: (Double) -> Unit,
    onSubmitMarketOrder: (symbol: String, side: PaperOrderSide, qty: Double, leverage: Int, sl: Double?, tp: Double?) -> Unit,
    onSubmitLimitOrder: (symbol: String, side: PaperOrderSide, limit: Double, qty: Double, leverage: Int, sl: Double?, tp: Double?) -> Unit,
    onEditPendingOrder: (orderId: String, limit: Double, qty: Double, sl: Double?, tp: Double?) -> Unit,
    onCancelPendingOrder: (orderId: String) -> Unit,
    onUpdatePositionSlTp: (positionId: String, sl: Double?, tp: Double?) -> Unit,
    onClosePosition: (positionId: String) -> Unit,
    onClearPrefill: () -> Unit,
    onDismissFeedback: () -> Unit,
    onRunSixMonthBacktest: (symbols: List<String>, lookbackDays: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = NexusTheme.colors
    var subTab by remember { mutableIntStateOf(0) }
    var showResetDialog by remember { mutableStateOf(false) }
    var editingPendingOrder by remember { mutableStateOf<PaperPendingOrder?>(null) }
    var editingPosition by remember { mutableStateOf<PaperPosition?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // Simulation Disclosure Banner
        Surface(
            color = colors.gold.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, colors.gold.copy(alpha = 0.4f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountBalanceWallet,
                        contentDescription = "Paper Trading Simulator",
                        tint = colors.gold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "PAPER TRADING SIMULATOR • Live Market Prices, Virtual USDT Wallet",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colors.gold,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(
                    onClick = { showResetDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 36.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reset Wallet", tint = colors.gold, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Reset", color = colors.gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (!feedbackMessage.isNullOrBlank()) {
            Surface(
                color = colors.surfaceVariant,
                border = BorderStroke(1.dp, colors.border),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = feedbackMessage,
                        style = MaterialTheme.typography.bodySmall.copy(color = colors.textPrimary),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismissFeedback, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = colors.textMuted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        SecondaryTabRow(
            selectedTabIndex = subTab,
            containerColor = colors.surface,
            contentColor = colors.gold
        ) {
            Tab(
                selected = subTab == 0,
                onClick = { subTab = 0 },
                text = { Text("Terminal (${portfolio.openPositions.size}/${portfolio.pendingOrders.size})", fontSize = 11.sp, maxLines = 1) },
                modifier = Modifier.testTag("paper_tab_terminal")
            )
            Tab(
                selected = subTab == 1,
                onClick = { subTab = 1 },
                text = { Text("Stats (${portfolio.closedTrades.size})", fontSize = 11.sp, maxLines = 1) },
                modifier = Modifier.testTag("paper_tab_stats")
            )
            Tab(
                selected = subTab == 2,
                onClick = { subTab = 2 },
                text = { Text("6M Backtest & Compare", fontSize = 11.sp, maxLines = 1) },
                modifier = Modifier.testTag("paper_tab_backtest")
            )
        }

        when (subTab) {
            0 -> PaperTerminalTab(
                portfolio = portfolio,
                prefill = prefill,
                assets = assets,
                onSubmitMarketOrder = onSubmitMarketOrder,
                onSubmitLimitOrder = onSubmitLimitOrder,
                onClearPrefill = onClearPrefill,
                onEditPendingOrder = { editingPendingOrder = it },
                onCancelPendingOrder = onCancelPendingOrder,
                onEditPositionSlTp = { editingPosition = it },
                onClosePosition = onClosePosition
            )
            1 -> PaperHistoryAndStatsTab(portfolio = portfolio)
            else -> SixMonthBacktestAndComparisonTab(
                portfolio = portfolio,
                managedSignals = managedSignals,
                transitionLog = transitionLog,
                report = backtestReport,
                isRunning = isRunningBacktest,
                progressText = backtestProgress,
                errorText = backtestError,
                onRunBacktest = onRunSixMonthBacktest
            )
        }
    }

    if (showResetDialog) {
        ResetWalletDialog(
            currentInitial = portfolio.config.initialBalanceUsdt,
            onDismiss = { showResetDialog = false },
            onConfirm = { newBalance ->
                onResetWallet(newBalance)
                showResetDialog = false
            }
        )
    }

    editingPendingOrder?.let { order ->
        EditPendingLimitDialog(
            order = order,
            onDismiss = { editingPendingOrder = null },
            onConfirm = { lim, qty, sl, tp ->
                onEditPendingOrder(order.id, lim, qty, sl, tp)
                editingPendingOrder = null
            }
        )
    }

    editingPosition?.let { pos ->
        EditPositionSlTpDialog(
            position = pos,
            onDismiss = { editingPosition = null },
            onConfirm = { sl, tp ->
                onUpdatePositionSlTp(pos.id, sl, tp)
                editingPosition = null
            }
        )
    }
}

@Composable
private fun PaperTerminalTab(
    portfolio: PaperPortfolioSnapshot,
    prefill: PaperTradePrefill?,
    assets: List<MarketAsset>,
    onSubmitMarketOrder: (symbol: String, side: PaperOrderSide, qty: Double, leverage: Int, sl: Double?, tp: Double?) -> Unit,
    onSubmitLimitOrder: (symbol: String, side: PaperOrderSide, limit: Double, qty: Double, leverage: Int, sl: Double?, tp: Double?) -> Unit,
    onClearPrefill: () -> Unit,
    onEditPendingOrder: (PaperPendingOrder) -> Unit,
    onCancelPendingOrder: (String) -> Unit,
    onEditPositionSlTp: (PaperPosition) -> Unit,
    onClosePosition: (String) -> Unit
) {
    val colors = NexusTheme.colors
    val quickPairs = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "LINKUSDT", "BNBUSDT", "XRPUSDT")

    var selectedPair by remember { mutableStateOf("BTCUSDT") }
    var side by remember { mutableStateOf(PaperOrderSide.LONG) }
    var orderType by remember { mutableStateOf(PaperOrderType.MARKET) }
    var leverage by remember { mutableIntStateOf(3) }
    var limitPriceText by remember { mutableStateOf("") }
    var marginUsdtText by remember { mutableStateOf("250") }
    var stopLossText by remember { mutableStateOf("") }
    var takeProfitText by remember { mutableStateOf("") }

    LaunchedEffect(prefill) {
        if (prefill != null) {
            selectedPair = prefill.symbol
            side = prefill.side
            orderType = prefill.suggestedOrderType
            leverage = prefill.leverage.coerceIn(1, 20)
            limitPriceText = fmtPricePlain(prefill.entryPrice)
            stopLossText = prefill.stopLoss?.let { fmtPricePlain(it) } ?: ""
            takeProfitText = prefill.takeProfit?.let { fmtPricePlain(it) } ?: ""
        }
    }

    val baseAsset = PaperTradingEngine.extractBaseAsset(selectedPair)
    val livePrice = assets.firstOrNull {
        it.id.equals(baseAsset, ignoreCase = true) ||
            it.symbol.replace("/", "").equals(selectedPair, ignoreCase = true)
    }?.currentPrice?.takeIf { it.isFinite() && it > 0.0 }

    val refPrice = if (orderType == PaperOrderType.LIMIT) {
        limitPriceText.toDoubleOrNull()?.takeIf { it > 0.0 } ?: livePrice ?: 0.0
    } else {
        livePrice ?: 0.0
    }
    val marginUsdt = marginUsdtText.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 0.0
    val computedQty = if (refPrice > 0.0 && marginUsdt > 0.0) (marginUsdt * leverage) / refPrice else 0.0
    val estLiq = if (refPrice > 0.0) PaperTradingEngine.calculateLiquidationPrice(side, refPrice, leverage) else 0.0

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Wallet Balance Card
        item {
            WalletOverviewCard(portfolio = portfolio)
        }

        // Order Ticket Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.card),
                border = BorderStroke(1.dp, if (prefill != null) colors.gold else colors.border),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (prefill != null) "PREFILLED ORDER (${prefill.sourceLabel})" else "NEW PAPER ORDER",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    color = if (prefill != null) colors.gold else colors.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = if (livePrice != null) "$selectedPair Live: $${fmtPricePlain(livePrice)}" else "$selectedPair Live: Fetching...",
                                style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted)
                            )
                        }
                        if (prefill != null) {
                            TextButton(onClick = onClearPrefill) {
                                Text("Clear", color = colors.textMuted, fontSize = 11.sp)
                            }
                        }
                    }

                    // Pair selector
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        quickPairs.forEach { pair ->
                            val selected = selectedPair == pair
                            FilterChip(
                                selected = selected,
                                onClick = { selectedPair = pair },
                                label = { Text(pair.removeSuffix("USDT"), fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colors.gold.copy(alpha = 0.2f),
                                    selectedLabelColor = colors.gold
                                )
                            )
                        }
                    }

                    // Side + Order Type Toggle
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { side = PaperOrderSide.LONG },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("paper_side_long"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (side == PaperOrderSide.LONG) colors.bullishGreen else colors.surfaceVariant,
                                contentColor = if (side == PaperOrderSide.LONG) Color.Black else colors.textPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("LONG", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { side = PaperOrderSide.SHORT },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("paper_side_short"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (side == PaperOrderSide.SHORT) colors.bearishRed else colors.surfaceVariant,
                                contentColor = if (side == PaperOrderSide.SHORT) Color.White else colors.textPrimary
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("SHORT", fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = orderType == PaperOrderType.MARKET,
                            onClick = { orderType = PaperOrderType.MARKET },
                            label = { Text("Market (Instant)", fontSize = 12.sp) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("paper_type_market")
                        )
                        FilterChip(
                            selected = orderType == PaperOrderType.LIMIT,
                            onClick = {
                                orderType = PaperOrderType.LIMIT
                                if (limitPriceText.isBlank() && livePrice != null) {
                                    limitPriceText = fmtPricePlain(livePrice)
                                }
                            },
                            label = { Text("Limit (Pending)", fontSize = 12.sp) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("paper_type_limit")
                        )
                    }

                    // Leverage chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Lev:", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted))
                        listOf(1, 2, 3, 5, 10, 20).forEach { lev ->
                            FilterChip(
                                selected = leverage == lev,
                                onClick = { leverage = lev },
                                label = { Text("${lev}x", fontSize = 11.sp) }
                            )
                        }
                    }

                    if (orderType == PaperOrderType.LIMIT) {
                        OutlinedTextField(
                            value = limitPriceText,
                            onValueChange = { limitPriceText = it },
                            label = { Text("Limit Entry Price (USDT)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = if (side == PaperOrderSide.LONG) {
                                "Strict Fill Rule: LONG Limit fills only when verified live price <= Limit Price."
                            } else {
                                "Strict Fill Rule: SHORT Limit fills only when verified live price >= Limit Price."
                            },
                            style = MaterialTheme.typography.labelSmall.copy(color = colors.gold)
                        )
                    }

                    OutlinedTextField(
                        value = marginUsdtText,
                        onValueChange = { marginUsdtText = it },
                        label = { Text("Margin Size (USDT)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Quick % balance buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0.05 to "5%", 0.10 to "10%", 0.25 to "25%", 0.50 to "50%").forEach { (pct, label) ->
                            OutlinedButton(
                                onClick = {
                                    val amt = (portfolio.availableBalanceUsdt * pct).coerceAtLeast(10.0)
                                    marginUsdtText = String.format(Locale.US, "%.2f", amt)
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.defaultMinSize(minHeight = 40.dp)
                            ) {
                                Text(label, fontSize = 11.sp)
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = stopLossText,
                            onValueChange = { stopLossText = it },
                            label = { Text("Stop Loss") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = takeProfitText,
                            onValueChange = { takeProfitText = it },
                            label = { Text("Take Profit") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Position estimation strip
                    Surface(
                        color = colors.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Notional: ${formatUsdt(marginUsdt * leverage)} • Qty: ${String.format(Locale.US, "%.4f", computedQty)}",
                                style = MaterialTheme.typography.labelSmall.copy(color = colors.textSecondary)
                            )
                            Text(
                                text = if (estLiq > 0.0) "Est. Liq: $${fmtPricePlain(estLiq)}" else "",
                                style = MaterialTheme.typography.labelSmall.copy(color = colors.bearishRed)
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val sl = stopLossText.toDoubleOrNull()
                            val tp = takeProfitText.toDoubleOrNull()
                            if (orderType == PaperOrderType.MARKET) {
                                onSubmitMarketOrder(selectedPair, side, computedQty, leverage, sl, tp)
                            } else {
                                val lim = limitPriceText.toDoubleOrNull() ?: 0.0
                                onSubmitLimitOrder(selectedPair, side, lim, computedQty, leverage, sl, tp)
                            }
                        },
                        enabled = computedQty > 0.0,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (side == PaperOrderSide.LONG) colors.bullishGreen else colors.bearishRed,
                            contentColor = if (side == PaperOrderSide.LONG) Color.Black else Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("paper_submit_order_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (orderType == PaperOrderType.MARKET) {
                                "PLACE MARKET ${side.name} (SIMULATED)"
                            } else {
                                "PLACE LIMIT ${side.name} ORDER"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Open Positions Section
        item {
            Text(
                text = "OPEN PAPER POSITIONS (${portfolio.openPositions.size})",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = colors.gold,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        if (portfolio.openPositions.isEmpty()) {
            item {
                EmptyPlaceholderCard("No open positions. Tap PAPER TRADE on a qualifying signal or place a Market/Limit order above.")
            }
        } else {
            items(portfolio.openPositions, key = { it.id }) { pos ->
                OpenPositionCard(
                    position = pos,
                    onEditSlTp = { onEditPositionSlTp(pos) },
                    onClose = { onClosePosition(pos.id) }
                )
            }
        }

        // Pending Limit Orders Section
        item {
            Text(
                text = "PENDING LIMIT ORDERS (${portfolio.pendingOrders.size})",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = colors.gold,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        if (portfolio.pendingOrders.isEmpty()) {
            item {
                EmptyPlaceholderCard("No pending limit orders queued.")
            }
        } else {
            items(portfolio.pendingOrders, key = { it.id }) { order ->
                PendingOrderCard(
                    order = order,
                    onEdit = { onEditPendingOrder(order) },
                    onCancel = { onCancelPendingOrder(order.id) }
                )
            }
        }
    }
}

@Composable
private fun WalletOverviewCard(portfolio: PaperPortfolioSnapshot) {
    val colors = NexusTheme.colors
    val netColor = if (portfolio.totalNetPnlUsdt >= 0.0) colors.bullishGreen else colors.bearishRed
    val unrealColor = if (portfolio.unrealizedNetPnlUsdt >= 0.0) colors.bullishGreen else colors.bearishRed

    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        border = BorderStroke(1.dp, colors.border),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Total Virtual Equity", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted))
                    Text(
                        text = "${formatUsdt(portfolio.equityUsdt)} USDT",
                        style = MaterialTheme.typography.headlineSmall.copy(color = colors.textPrimary, fontWeight = FontWeight.Bold)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Total Net PnL", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted))
                    Text(
                        text = "${if (portfolio.totalNetPnlUsdt >= 0) "+" else ""}${formatUsdt(portfolio.totalNetPnlUsdt)} (${String.format(Locale.US, "%+.2f%%", portfolio.totalNetReturnPercent)})",
                        style = MaterialTheme.typography.titleSmall.copy(color = netColor, fontWeight = FontWeight.Bold)
                    )
                }
            }
            HorizontalDivider(color = colors.border.copy(alpha = 0.5f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricCol("Available", formatUsdt(portfolio.availableBalanceUsdt))
                MetricCol("Used Margin", formatUsdt(portfolio.usedPositionMarginUsdt))
                MetricCol("Reserved Limit", formatUsdt(portfolio.reservedLimitMarginUsdt))
                MetricCol("Unrealized Net", formatUsdt(portfolio.unrealizedNetPnlUsdt), unrealColor)
            }
            Text(
                text = "Last Price Tick (PKT): ${portfolio.lastPriceTickPkt}",
                style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted)
            )
        }
    }
}

@Composable
private fun OpenPositionCard(
    position: PaperPosition,
    onEditSlTp: () -> Unit,
    onClose: () -> Unit
) {
    val colors = NexusTheme.colors
    val sideColor = if (position.side == PaperOrderSide.LONG) colors.bullishGreen else colors.bearishRed
    val netColor = if (position.netUnrealizedPnlUsdt >= 0.0) colors.bullishGreen else colors.bearishRed

    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        border = BorderStroke(1.dp, sideColor.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(color = sideColor.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
                        Text(
                            text = "${position.side.name} ${position.leverage}x",
                            color = sideColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(position.symbol, fontWeight = FontWeight.Bold, color = colors.textPrimary)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Net: ${if (position.netUnrealizedPnlUsdt >= 0) "+" else ""}${formatUsdt(position.netUnrealizedPnlUsdt)} (${String.format(Locale.US, "%+.1f%%", position.netRoiPercent)})",
                        color = netColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Gross: ${formatUsdt(position.grossUnrealizedPnlUsdt)}",
                        color = colors.textMuted,
                        fontSize = 10.sp
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricCol("Entry", "$${fmtPricePlain(position.entryPrice)}")
                MetricCol("Mark", "$${fmtPricePlain(position.currentMarkPrice)}")
                MetricCol("Qty", String.format(Locale.US, "%.4f", position.quantity))
                MetricCol("Liq", "$${fmtPricePlain(position.liquidationPrice)}", colors.bearishRed)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "SL: ${position.stopLoss?.let { "$${fmtPricePlain(it)}" } ?: "None"} • TP: ${position.takeProfit?.let { "$${fmtPricePlain(it)}" } ?: "None"}",
                    style = MaterialTheme.typography.labelSmall.copy(color = colors.textSecondary)
                )
                Text(
                    text = "Opened: ${position.openedAtPkt}",
                    style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onEditSlTp,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 44.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit SL/TP", modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit SL / TP", fontSize = 12.sp)
                }
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.bearishRed, contentColor = Color.White),
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 44.dp)
                ) {
                    Text("Close @ Market", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PendingOrderCard(
    order: PaperPendingOrder,
    onEdit: () -> Unit,
    onCancel: () -> Unit
) {
    val colors = NexusTheme.colors
    val sideColor = if (order.side == PaperOrderSide.LONG) colors.bullishGreen else colors.bearishRed

    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        border = BorderStroke(1.dp, colors.border),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${order.symbol} • LIMIT ${order.side.name} ${order.leverage}x", fontWeight = FontWeight.Bold, color = sideColor)
                Text("Reserved: ${formatUsdt(order.reservedMarginUsdt)}", fontSize = 11.sp, color = colors.textSecondary)
            }
            Text(
                text = "Limit: $${fmtPricePlain(order.limitPrice)} • Qty: ${String.format(Locale.US, "%.4f", order.quantity)} • SL: ${order.stopLoss?.let { fmtPricePlain(it) } ?: "-"} • TP: ${order.takeProfit?.let { fmtPricePlain(it) } ?: "-"}",
                style = MaterialTheme.typography.bodySmall.copy(color = colors.textPrimary)
            )
            Text(order.statusNote, style = MaterialTheme.typography.labelSmall.copy(color = colors.gold))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 44.dp)
                ) {
                    Text("Edit Limit", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 44.dp)
                ) {
                    Text("Cancel Order", color = colors.bearishRed, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun PaperHistoryAndStatsTab(portfolio: PaperPortfolioSnapshot) {
    val colors = NexusTheme.colors
    val stats = portfolio.stats

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.card),
                border = BorderStroke(1.dp, colors.border),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.History, contentDescription = null, tint = colors.gold, modifier = Modifier.size(18.dp))
                        Text("PAPER TRADING PERFORMANCE", fontWeight = FontWeight.Bold, color = colors.textPrimary)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        MetricCol("Total Trades", "${stats.totalTrades}")
                        MetricCol("Win Rate", String.format(Locale.US, "%.1f%%", stats.winRatePercent))
                        MetricCol("Profit Factor", String.format(Locale.US, "%.2f", stats.profitFactor))
                        MetricCol("Max DD", String.format(Locale.US, "%.2f%%", stats.maxDrawdownPercent), colors.bearishRed)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        MetricCol("Gross PnL", formatUsdt(stats.totalGrossPnlUsdt))
                        MetricCol("Net PnL", formatUsdt(stats.totalNetPnlUsdt), if (stats.totalNetPnlUsdt >= 0) colors.bullishGreen else colors.bearishRed)
                        MetricCol("Today (PKT)", formatUsdt(stats.todayNetPnlUsdt))
                        MetricCol("Fees+Fund", formatUsdt(stats.totalFeesPaidUsdt + stats.totalFundingUsdt))
                    }
                    Text(
                        text = "LONG: ${stats.longTradesCount} (${String.format(Locale.US, "%.1f%%", stats.longWinRatePercent)} WR) • SHORT: ${stats.shortTradesCount} (${String.format(Locale.US, "%.1f%%", stats.shortWinRatePercent)} WR)",
                        style = MaterialTheme.typography.labelSmall.copy(color = colors.textSecondary)
                    )
                }
            }
        }

        if (portfolio.closedTrades.isEmpty()) {
            item {
                EmptyPlaceholderCard("No closed paper trades yet.")
            }
        } else {
            items(portfolio.closedTrades, key = { it.id }) { trade ->
                ClosedTradeRowCard(trade)
            }
        }
    }
}

@Composable
private fun ClosedTradeRowCard(trade: PaperClosedTrade) {
    val colors = NexusTheme.colors
    val netColor = if (trade.netPnlUsdt >= 0.0) colors.bullishGreen else colors.bearishRed
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        border = BorderStroke(1.dp, colors.border),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${trade.symbol} • ${trade.side.name} (${trade.exitReason.label})", fontWeight = FontWeight.Bold, color = colors.textPrimary, fontSize = 12.sp)
                Text(
                    text = "Net: ${if (trade.netPnlUsdt >= 0) "+" else ""}${formatUsdt(trade.netPnlUsdt)}",
                    fontWeight = FontWeight.Bold,
                    color = netColor,
                    fontSize = 12.sp
                )
            }
            Text(
                text = "Entry: $${fmtPricePlain(trade.entryPrice)} -> Exit: $${fmtPricePlain(trade.exitPrice)} | Gross: ${formatUsdt(trade.grossPnlUsdt)} | Costs: ${formatUsdt(trade.totalTradingCostsUsdt)}",
                style = MaterialTheme.typography.labelSmall.copy(color = colors.textSecondary)
            )
            Text(
                text = "Closed PKT: ${trade.closedAtPkt}" + (trade.netRMultiple?.let { " • R: ${String.format(Locale.US, "%+.2fR", it)}" } ?: ""),
                style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted)
            )
        }
    }
}

@Composable
private fun SixMonthBacktestAndComparisonTab(
    portfolio: PaperPortfolioSnapshot,
    managedSignals: List<ManagedSignalEntry>,
    transitionLog: List<SignalTransitionRecord>,
    report: MultiCoinBacktestReport?,
    isRunning: Boolean,
    progressText: String?,
    errorText: String?,
    onRunBacktest: (List<String>, Int) -> Unit
) {
    val colors = NexusTheme.colors
    var selectedLookbackDays by remember { mutableIntStateOf(180) }

    val resolvedLive = managedSignals.filter {
        it.state == ManagedSignalState.TARGET_HIT || it.state == ManagedSignalState.STOP_LOSS_HIT
    }
    val liveWins = resolvedLive.count { it.state == ManagedSignalState.TARGET_HIT }
    val liveWinRate = if (resolvedLive.isNotEmpty()) (liveWins.toDouble() / resolvedLive.size * 100.0) else null

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Explicit Separation Card: Backtest vs Paper Trading vs Live Signal Outcomes
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.card),
                border = BorderStroke(1.dp, colors.border),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "INDEPENDENT PERFORMANCE COMPARISON",
                        style = MaterialTheme.typography.titleSmall.copy(color = colors.gold, fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Historical Backtest, Live Paper Trading, and Tracked Live Signals are measured separately and NEVER mixed.",
                        style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted)
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ComparisonMiniCard(
                            title = "6M Backtest",
                            primary = if (report != null && report.hasSufficientOverallData) {
                                "${report.overall.totalTrades} trades • ${String.format(Locale.US, "%.1f%%", report.overall.winRatePercent)} WR"
                            } else "Not Run / Insufficient",
                            secondary = if (report != null && report.hasSufficientOverallData) {
                                "Net ${formatUsdt(report.overall.netPnlUsdt)} • PF ${String.format(Locale.US, "%.2f", report.overall.profitFactor)}"
                            } else "Past simulation only",
                            modifier = Modifier.weight(1f)
                        )
                        ComparisonMiniCard(
                            title = "Paper Trading",
                            primary = "${portfolio.stats.totalTrades} trades • ${String.format(Locale.US, "%.1f%%", portfolio.stats.winRatePercent)} WR",
                            secondary = "Net ${formatUsdt(portfolio.stats.totalNetPnlUsdt)} • PF ${String.format(Locale.US, "%.2f", portfolio.stats.profitFactor)}",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    ComparisonMiniCard(
                        title = "Live Signal Tracking (${managedSignals.size} signals / ${transitionLog.size} transitions)",
                        primary = if (liveWinRate != null) {
                            "${resolvedLive.size} resolved (${liveWins} TP / ${resolvedLive.size - liveWins} SL) • ${String.format(Locale.US, "%.1f%%", liveWinRate)} Win Rate"
                        } else {
                            "Awaiting resolved live outcomes (Active: ${managedSignals.count { it.state.isOpenOrLive }})"
                        },
                        secondary = "Note: Setup quality >=92/100 measures confluence score, NOT guaranteed win probability.",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 6-Month Multi-Coin Historical Backtest Runner
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.card),
                border = BorderStroke(1.dp, colors.border),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Analytics, contentDescription = null, tint = colors.gold, modifier = Modifier.size(18.dp))
                        Text("REAL 6-MONTH CAUSAL BACKTEST (BINANCE FUTURES)", fontWeight = FontWeight.Bold, color = colors.textPrimary, fontSize = 13.sp)
                    }
                    Text(
                        text = "Fetches real paginated 15M + 1H/4H/1D OHLCV candles & 8h historical funding for BTC, ETH, SOL, LINK with zero look-ahead and conservative same-bar SL/TP handling.",
                        style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(90 to "90 Days", 180 to "6 Months (180d)").forEach { (days, label) ->
                            FilterChip(
                                selected = selectedLookbackDays == days,
                                onClick = { selectedLookbackDays = days },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }
                    if (isRunning) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = colors.gold)
                        if (!progressText.isNullOrBlank()) {
                            Text(progressText, style = MaterialTheme.typography.labelSmall.copy(color = colors.gold))
                        }
                    }
                    if (!errorText.isNullOrBlank()) {
                        Surface(color = colors.bearishRed.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = colors.bearishRed, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(errorText, color = colors.bearishRed, fontSize = 11.sp)
                            }
                        }
                    }
                    Button(
                        onClick = {
                            onRunBacktest(listOf("BTCUSDT", "ETHUSDT", "SOLUSDT", "LINKUSDT"), selectedLookbackDays)
                        },
                        enabled = !isRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.gold, contentColor = Color.Black),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("run_6m_backtest_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isRunning) "RUNNING BACKTEST..." else "RUN ${selectedLookbackDays}D MULTI-COIN BACKTEST", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (report != null) {
            item { BacktestResultsCard(report = report) }
        }
    }
}

@Composable
private fun BacktestResultsCard(report: MultiCoinBacktestReport) {
    val colors = NexusTheme.colors
    val agg = report.overall
    val split = report.inOutSampleSplit

    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        border = BorderStroke(1.dp, colors.border),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(report.statusSummary, style = MaterialTheme.typography.bodySmall.copy(color = colors.textPrimary, fontWeight = FontWeight.SemiBold))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricCol("Trades", "${agg.totalTrades}")
                MetricCol("Win Rate", String.format(Locale.US, "%.1f%%", agg.winRatePercent))
                MetricCol("Profit Factor", String.format(Locale.US, "%.2f", agg.profitFactor))
                MetricCol("Max DD", String.format(Locale.US, "%.2f%%", agg.maxDrawdownPercent), colors.bearishRed)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricCol("Net PnL", formatUsdt(agg.netPnlUsdt), if (agg.netPnlUsdt >= 0) colors.bullishGreen else colors.bearishRed)
                MetricCol("Expectancy", String.format(Locale.US, "%+.2fR", agg.expectancyR))
                MetricCol("Costs", formatUsdt(agg.totalCostsUsdt))
                MetricCol("Same-Bar Loss", "${agg.ambiguousSameCandle}")
            }
            Text(
                text = "LONG: ${agg.longStats.trades} (${String.format(Locale.US, "%.1f%%", agg.longStats.winRatePercent)} WR, ${formatUsdt(agg.longStats.netPnlUsdt)}) | SHORT: ${agg.shortStats.trades} (${String.format(Locale.US, "%.1f%%", agg.shortStats.winRatePercent)} WR, ${formatUsdt(agg.shortStats.netPnlUsdt)})",
                style = MaterialTheme.typography.labelSmall.copy(color = colors.textSecondary)
            )
            HorizontalDivider(color = colors.border.copy(alpha = 0.5f))
            Text("70% IN-SAMPLE vs 30% OUT-OF-SAMPLE", fontWeight = FontWeight.Bold, color = colors.gold, fontSize = 11.sp)
            Text(
                text = "In-Sample (70%): ${split.inSampleCount} trades • ${String.format(Locale.US, "%.1f%%", split.inSampleWinRatePercent)} WR • PF ${String.format(Locale.US, "%.2f", split.inSampleProfitFactor)} • Exp ${String.format(Locale.US, "%+.2fR", split.inSampleExpectancyR)}\n" +
                    "Out-of-Sample (30%): ${split.outOfSampleCount} trades • ${String.format(Locale.US, "%.1f%%", split.outOfSampleWinRatePercent)} WR • PF ${String.format(Locale.US, "%.2f", split.outOfSampleProfitFactor)} • Exp ${String.format(Locale.US, "%+.2fR", split.outOfSampleExpectancyR)}",
                style = MaterialTheme.typography.bodySmall.copy(color = colors.textPrimary)
            )

            if (report.walkForwardWindows.isNotEmpty()) {
                HorizontalDivider(color = colors.border.copy(alpha = 0.5f))
                Text("WALK-FORWARD WINDOWS", fontWeight = FontWeight.Bold, color = colors.gold, fontSize = 11.sp)
                report.walkForwardWindows.forEach { wf ->
                    Text(
                        text = "W${wf.windowIndex} (${wf.periodLabelPkt}): IS ${wf.inSampleTrades} (${String.format(Locale.US, "%.0f%%", wf.inSampleWinRatePercent)}) -> OOS ${wf.outOfSampleTrades} (${String.format(Locale.US, "%.0f%%", wf.outOfSampleWinRatePercent)}, Net ${formatUsdt(wf.outOfSampleNetPnlUsdt)})",
                        style = MaterialTheme.typography.labelSmall.copy(color = colors.textSecondary)
                    )
                }
            }

            HorizontalDivider(color = colors.border.copy(alpha = 0.5f))
            Text("COIN-WISE BREAKDOWN", fontWeight = FontWeight.Bold, color = colors.gold, fontSize = 11.sp)
            report.coinBreakdowns.forEach { c ->
                Text(
                    text = "${c.symbol}: ${c.statusNote} • Net ${formatUsdt(c.metrics.netPnlUsdt)} (Funding events: ${c.fundingEventsCount})",
                    style = MaterialTheme.typography.labelSmall.copy(color = if (c.hasSufficientData) colors.textPrimary else colors.textMuted)
                )
            }

            if (report.monthlyBreakdown.isNotEmpty()) {
                HorizontalDivider(color = colors.border.copy(alpha = 0.5f))
                Text("MONTHLY BREAKDOWN (PKT)", fontWeight = FontWeight.Bold, color = colors.gold, fontSize = 11.sp)
                report.monthlyBreakdown.forEach { m ->
                    Text(
                        text = "${m.monthPkt}: ${m.trades} trades • ${String.format(Locale.US, "%.1f%%", m.winRatePercent)} WR • Net ${formatUsdt(m.netPnlUsdt)} • PF ${String.format(Locale.US, "%.2f", m.profitFactor)}",
                        style = MaterialTheme.typography.labelSmall.copy(color = colors.textSecondary)
                    )
                }
            }

            Text(
                text = report.methodologyDisclosure,
                style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted)
            )
        }
    }
}

@Composable
private fun ComparisonMiniCard(
    title: String,
    primary: String,
    secondary: String,
    modifier: Modifier = Modifier
) {
    val colors = NexusTheme.colors
    Surface(
        color = colors.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, colors.border),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall.copy(color = colors.gold, fontWeight = FontWeight.Bold))
            Text(primary, style = MaterialTheme.typography.bodySmall.copy(color = colors.textPrimary, fontWeight = FontWeight.SemiBold))
            Text(secondary, style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted))
        }
    }
}

@Composable
private fun MetricCol(label: String, value: String, valueColor: Color = NexusTheme.colors.textPrimary) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall.copy(color = NexusTheme.colors.textMuted, fontSize = 10.sp))
        Text(value, style = MaterialTheme.typography.bodySmall.copy(color = valueColor, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun EmptyPlaceholderCard(message: String) {
    val colors = NexusTheme.colors
    Surface(
        color = colors.card,
        border = BorderStroke(1.dp, colors.border.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall.copy(color = colors.textMuted),
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
private fun ResetWalletDialog(
    currentInitial: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var balanceText by remember { mutableStateOf(String.format(Locale.US, "%.0f", currentInitial)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset Paper Wallet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Clears open/pending paper trades and resets virtual balance to:")
                OutlinedTextField(
                    value = balanceText,
                    onValueChange = { balanceText = it },
                    label = { Text("Initial Virtual Balance (USDT)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(balanceText.toDoubleOrNull() ?: 10_000.0) }) {
                Text("Reset Balance")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun EditPendingLimitDialog(
    order: PaperPendingOrder,
    onDismiss: () -> Unit,
    onConfirm: (Double, Double, Double?, Double?) -> Unit
) {
    var limitText by remember { mutableStateOf(fmtPricePlain(order.limitPrice)) }
    var qtyText by remember { mutableStateOf(String.format(Locale.US, "%.5f", order.quantity)) }
    var slText by remember { mutableStateOf(order.stopLoss?.let { fmtPricePlain(it) } ?: "") }
    var tpText by remember { mutableStateOf(order.takeProfit?.let { fmtPricePlain(it) } ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${order.symbol} Limit Order") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = limitText, onValueChange = { limitText = it }, label = { Text("Limit Price") }, singleLine = true)
                OutlinedTextField(value = qtyText, onValueChange = { qtyText = it }, label = { Text("Quantity") }, singleLine = true)
                OutlinedTextField(value = slText, onValueChange = { slText = it }, label = { Text("Stop Loss (optional)") }, singleLine = true)
                OutlinedTextField(value = tpText, onValueChange = { tpText = it }, label = { Text("Take Profit (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(
                    limitText.toDoubleOrNull() ?: order.limitPrice,
                    qtyText.toDoubleOrNull() ?: order.quantity,
                    slText.toDoubleOrNull(),
                    tpText.toDoubleOrNull()
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EditPositionSlTpDialog(
    position: PaperPosition,
    onDismiss: () -> Unit,
    onConfirm: (Double?, Double?) -> Unit
) {
    var slText by remember { mutableStateOf(position.stopLoss?.let { fmtPricePlain(it) } ?: "") }
    var tpText by remember { mutableStateOf(position.takeProfit?.let { fmtPricePlain(it) } ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${position.symbol} SL / TP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = slText, onValueChange = { slText = it }, label = { Text("Stop Loss") }, singleLine = true)
                OutlinedTextField(value = tpText, onValueChange = { tpText = it }, label = { Text("Take Profit") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(slText.toDoubleOrNull(), tpText.toDoubleOrNull()) }) { Text("Update") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun fmtPricePlain(p: Double): String = when {
    p >= 1000.0 -> String.format(Locale.US, "%.2f", p)
    p >= 1.0 -> String.format(Locale.US, "%.2f", p)
    else -> String.format(Locale.US, "%.4f", p)
}
