package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import java.util.Locale
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ApiFeedConfig
import com.example.data.model.MarketAlert
import com.example.data.model.MarketAsset
import com.example.data.model.MarketOverview
import com.example.data.model.NavigationTab
import com.example.data.model.Signal
import com.example.ui.components.AlertItemCard
import com.example.ui.components.PriceTickerCard
import com.example.ui.components.SentimentGauge
import com.example.ui.components.SignalItemCard
import com.example.ui.theme.NexusTheme

@Composable
fun DashboardScreen(
    assets: List<MarketAsset>,
    overview: MarketOverview,
    signals: List<Signal>,
    alerts: List<MarketAlert>,
    apiConfigs: List<ApiFeedConfig>,
    onAssetClick: (MarketAsset) -> Unit,
    onConnectFeedClick: (MarketAsset) -> Unit,
    onSetAlertClick: (symbol: String) -> Unit,
    onToggleAlert: (alertId: String) -> Unit,
    onDeleteAlert: (alertId: String) -> Unit,
    onViewAllSignals: () -> Unit,
    onNavigateTab: (NavigationTab) -> Unit,
    onCopySignalLevels: (Signal) -> Unit,
    onToggleFavorite: (String) -> Unit = {},
    paperEquityUsdt: Double? = null,
    paperNetPnlUsdt: Double? = null,
    modifier: Modifier = Modifier
) {
    val colors = NexusTheme.colors
    // Primary assets for dashboard sections
    val btcAsset = assets.firstOrNull { it.id == "BTC" } ?: assets.firstOrNull()
    val goldAsset = assets.firstOrNull { it.id == "XAU" }
    val silverAsset = assets.firstOrNull { it.id == "XAG" }
    val favoriteAssets = assets.filter { it.isFavorite }

    // Energy assets: WTI, Brent, Natural Gas
    var selectedEnergyId by remember { mutableStateOf("WTI") }
    val energyAsset = assets.firstOrNull { it.id == selectedEnergyId }
        ?: assets.firstOrNull { it.id == "WTI" }
        ?: assets.firstOrNull { it.id == "OIL" }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            // Dashboard Title & Overview Banner
            MarketOverviewHeader(overview = overview, onConfigureFeeds = { onNavigateTab(NavigationTab.SETTINGS) })
        }

        // Paper Trading & 6M Backtest Quick Access Card
        item {
            val eqText = paperEquityUsdt?.let { String.format(Locale.US, "$%,.2f", it) } ?: "Open Terminal"
            val pnlValue = paperNetPnlUsdt ?: 0.0
            val pnlColor = if (pnlValue >= 0.0) colors.bullishGreen else colors.bearishRed
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateTab(NavigationTab.PAPER_TRADING) }
                    .testTag("dashboard_paper_trading_cta"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = colors.card),
                border = BorderStroke(1.dp, colors.gold.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.AccountBalanceWallet,
                            contentDescription = "Paper Trading Wallet",
                            tint = colors.gold,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "PAPER TRADING & 6M BACKTEST",
                                color = colors.gold,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "Equity: $eqText • Net PnL: ${if (pnlValue >= 0) "+" else ""}${String.format(Locale.US, "%.2f", pnlValue)} USDT",
                                color = colors.textSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = "Open Paper Trading",
                        tint = pnlColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Priority Watchlist Carousel (if any favorited)
        if (favoriteAssets.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "PRIORITY WATCHLIST",
                    subtitle = "Quick Access Starred Assets",
                    icon = Icons.Default.Star
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(favoriteAssets, key = { "fav_dash_${it.id}" }) { fav ->
                        Card(
                            modifier = Modifier
                                .width(160.dp)
                                .clickable { onAssetClick(fav) }
                                .testTag("dashboard_fav_${fav.id.lowercase()}"),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.card),
                            border = BorderStroke(1.dp, colors.border)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = fav.symbol,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            color = colors.textPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    )
                                    IconButton(
                                        onClick = { onToggleFavorite(fav.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = null,
                                            tint = colors.gold,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (fav.currentPrice != null) String.format(Locale.US, "$%,.2f", fav.currentPrice) else "Awaiting Quote",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = colors.gold,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                val changePct = fav.change24hPercent ?: 0.0
                                Text(
                                    text = "${if (changePct >= 0) "+" else ""}${String.format(Locale.US, "%.2f", changePct)}%",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (changePct >= 0) colors.bullishGreen else colors.bearishRed,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // 1. Bitcoin Price Section
        item {
            SectionHeader(
                title = "BITCOIN PRICE SECTION",
                subtitle = "Cryptocurrency Market Anchor • Real-Time Stream",
                icon = Icons.Default.Timeline
            )
            Spacer(modifier = Modifier.height(8.dp))
            btcAsset?.let { asset ->
                PriceTickerCard(
                    asset = asset,
                    onCardClick = { onAssetClick(asset) },
                    onConnectFeedClick = { onConnectFeedClick(asset) },
                    onSetAlertClick = { onSetAlertClick(asset.symbol) },
                    onToggleFavorite = { onToggleFavorite(asset.id) }
                )
            }
        }

        // 2. Gold Price Section
        item {
            SectionHeader(
                title = "GOLD PRICE SECTION (XAUUSD)",
                subtitle = "Precious Metal & Sovereign Reserve Benchmark",
                icon = Icons.Default.AutoAwesome
            )
            Spacer(modifier = Modifier.height(8.dp))
            goldAsset?.let { asset ->
                PriceTickerCard(
                    asset = asset,
                    onCardClick = { onAssetClick(asset) },
                    onConnectFeedClick = { onConnectFeedClick(asset) },
                    onSetAlertClick = { onSetAlertClick(asset.symbol) },
                    onToggleFavorite = { onToggleFavorite(asset.id) }
                )
            }
        }

        // 3. Silver Price Section
        item {
            SectionHeader(
                title = "SILVER PRICE SECTION (XAGUSD)",
                subtitle = "Industrial Demand & Monetary Hybrid",
                icon = Icons.Default.Speed
            )
            Spacer(modifier = Modifier.height(8.dp))
            silverAsset?.let { asset ->
                PriceTickerCard(
                    asset = asset,
                    onCardClick = { onAssetClick(asset) },
                    onConnectFeedClick = { onConnectFeedClick(asset) },
                    onSetAlertClick = { onSetAlertClick(asset.symbol) },
                    onToggleFavorite = { onToggleFavorite(asset.id) }
                )
            }
        }

        // 4. Crude Oil & Energy Price Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionHeader(
                    title = "CRUDE OIL & ENERGY SECTION",
                    subtitle = "WTI, Brent Crude & Natural Gas Benchmarks",
                    icon = Icons.Default.Speed
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Energy benchmark selector chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("WTI" to "WTI Crude", "BRENT" to "Brent Crude", "NATGAS" to "Nat Gas").forEach { (id, label) ->
                    val isSel = selectedEnergyId == id
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) colors.gold else colors.surfaceVariant)
                            .clickable { selectedEnergyId = id }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (isSel) colors.onGold else colors.textSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            energyAsset?.let { asset ->
                PriceTickerCard(
                    asset = asset,
                    onCardClick = { onAssetClick(asset) },
                    onConnectFeedClick = { onConnectFeedClick(asset) },
                    onSetAlertClick = { onSetAlertClick(asset.symbol) },
                    onToggleFavorite = { onToggleFavorite(asset.id) }
                )
            }
        }

        // 5. Market Sentiment
        item {
            SectionHeader(
                title = "MARKET SENTIMENT & VOLATILITY",
                subtitle = "Multi-Model Algorithmic Index",
                icon = Icons.Default.Speed
            )
            Spacer(modifier = Modifier.height(8.dp))
            SentimentGauge(
                fearAndGreedIndex = overview.fearAndGreedIndex,
                sentimentLabel = overview.sentimentLabel,
                bullishPercent = overview.bullishAssetsPercent,
                volatilityState = overview.volatilityState,
                hasLiveSentiment = overview.hasLiveSentiment,
                sourceLabel = overview.sentimentTimestamp
            )
        }

        // 6. Recent Signals
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionHeader(
                    title = "RECENT AI SIGNALS",
                    subtitle = "High-Conviction Quantitative Alerts",
                    icon = Icons.Default.AutoAwesome
                )

                OutlinedButton(
                    onClick = onViewAllSignals,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.gold),
                    modifier = Modifier.testTag("dashboard_view_all_signals_btn")
                ) {
                    Text("View All", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(imageVector = Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(12.dp))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (signals.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dashboard_empty_signals_card"),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    border = BorderStroke(1.dp, colors.border.copy(alpha = 0.7f))
                ) {
                    Text(
                        text = "No verified signals loaded yet. Open Signals or run the Live Scanner to evaluate real market data via Central Signal Authority.",
                        style = MaterialTheme.typography.bodySmall.copy(color = colors.textMuted),
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            items(signals.take(2)) { signal ->
                SignalItemCard(
                    signal = signal,
                    onSetAlertClick = { onSetAlertClick(signal.assetSymbol) },
                    onCopyLevelsClick = { onCopySignalLevels(signal) }
                )
            }
        }

        // 7. Recent Alerts
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionHeader(
                    title = "PRICE ALERTS",
                    subtitle = "Active Triggers & Thresholds",
                    icon = Icons.Default.NotificationsActive
                )

                Button(
                    onClick = { onSetAlertClick("BTC/USDT") },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.gold, contentColor = colors.onGold),
                    modifier = Modifier.testTag("dashboard_add_alert_btn")
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New Alert", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (alerts.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    border = BorderStroke(1.dp, colors.border)
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No active price alerts set. Tap 'New Alert' to add one.", color = colors.textMuted, fontSize = 12.sp)
                    }
                }
            }
        } else {
            items(alerts.take(3)) { alert ->
                AlertItemCard(
                    alert = alert,
                    onToggle = { onToggleAlert(alert.id) },
                    onDelete = { onDeleteAlert(alert.id) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MarketOverviewHeader(
    overview: MarketOverview,
    onConfigureFeeds: () -> Unit
) {
    val colors = NexusTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth().testTag("market_overview_header_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        border = BorderStroke(
            1.dp,
            Brush.horizontalGradient(listOf(colors.gold.copy(alpha = 0.4f), colors.border))
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "EXECUTIVE TRADING TERMINAL",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colors.gold,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Text(
                        text = "NEXUS Core Intelligence Engine",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = overview.marketStatus,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colors.bullishGreen,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Macro metrics row from CoinGecko & Binance
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceVariant.copy(alpha = 0.7f))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Crypto Market Cap", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 10.sp))
                    Text(
                        text = overview.totalCryptoMarketCap,
                        style = MaterialTheme.typography.bodyMedium.copy(color = colors.textPrimary, fontWeight = FontWeight.Bold)
                    )
                }
                Column {
                    Text("24h Volume", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 10.sp))
                    Text(
                        text = overview.cryptoVolume24h,
                        style = MaterialTheme.typography.bodyMedium.copy(color = colors.gold, fontWeight = FontWeight.Bold)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("BTC Dominance", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 10.sp))
                    Text(
                        text = overview.btcDominance,
                        style = MaterialTheme.typography.bodyMedium.copy(color = colors.bullishGreen, fontWeight = FontWeight.Bold)
                    )
                }
            }

            if (overview.defiTvlFormatted != "Unavailable" || overview.stablecoinSupplyFormatted != "Unavailable") {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceVariant.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("DeFi TVL (DefiLlama)", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 9.sp))
                        Text(
                            text = overview.defiTvlFormatted,
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textPrimary, fontWeight = FontWeight.Bold)
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Stablecoin Float", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 9.sp))
                        Text(
                            text = overview.stablecoinSupplyFormatted,
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.bullishGreen, fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${overview.connectedFeedsCount} Active Feeds • ${overview.dataProviderSummary}",
                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary, fontSize = 10.sp),
                    maxLines = 1
                )
                Text(
                    text = "Feeds Setup →",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = colors.gold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    ),
                    modifier = Modifier.clickable { onConfigureFeeds() }
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    val colors = NexusTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = colors.gold, modifier = Modifier.size(16.dp))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = colors.textMuted,
                    fontSize = 10.sp
                )
            )
        }
    }
}
