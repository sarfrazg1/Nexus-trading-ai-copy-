package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CandleTimeframe
import com.example.data.model.ScannerItem
import com.example.data.risk.BudgetConfig
import com.example.data.risk.DailyRiskLedger
import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.FuturesScanSignal
import com.example.data.scanner.FuturesSignalLifecycle
import com.example.data.scanner.FuturesWatchlistPair
import com.example.data.scanner.HighRewardOpportunity
import com.example.data.scanner.HighRewardScanState
import com.example.data.scanner.ManagedSignalEntry
import com.example.data.scanner.OpportunityPriorityTier
import com.example.data.scanner.RankedMultiMarketBoard
import com.example.data.scanner.ScannerEngineState
import com.example.data.scanner.SetupModelType
import com.example.data.scanner.SignalTransitionRecord
import com.example.data.scanner.SkillAdaptationRegistry
import com.example.data.scanner.SkillAdaptationStatus
import com.example.data.scanner.StrictMultiMarketEvaluation
import com.example.data.timemachine.DirectionalSetupBacktestReport
import com.example.ui.theme.NexusTheme
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScannerScreen(
    scannerItems: List<ScannerItem>,
    scannerFilter: String,
    onFilterSelected: (String) -> Unit,
    onSetAlertClick: (symbol: String) -> Unit,
    selectedTimeframe: CandleTimeframe = CandleTimeframe.H1,
    onTimeframeSelected: (CandleTimeframe) -> Unit = {},
    onInspectAsset: (assetId: String, symbol: String) -> Unit = { _, _ -> },
    onRefreshScanner: () -> Unit = {},
    // Binance USDT-M Futures Auto-Scanner parameters
    futuresScannerState: ScannerEngineState = ScannerEngineState(),
    futuresWatchlist: List<FuturesWatchlistPair> = emptyList(),
    scannerScreenMode: Int = 0, // 0 = Futures Auto-Scanner, 1 = Market Anomaly Screener
    onModeSelected: (Int) -> Unit = {},
    onStartFuturesScanner: () -> Unit = {},
    onStopFuturesScanner: () -> Unit = {},
    onToggleFuturesWatchlist: (symbol: String, enabled: Boolean) -> Unit = { _, _ -> },
    onAddCustomFuturesPair: (rawInput: String) -> Unit = {},
    onRemoveFuturesPair: (symbol: String) -> Unit = {},
    onIntervalSelected: (Int) -> Unit = {},
    onTogglePaperTrading: (Boolean) -> Unit = {},
    onExplainSignalWithAi: (FuturesScanSignal) -> Unit = {},
    onSimulatePaperTrade: (FuturesScanSignal) -> Unit = {},
    strict92ConfidenceMode: Boolean = false,
    onToggleStrict92Mode: (Boolean) -> Unit = {},
    onOpenWorkflow: (FuturesScanSignal) -> Unit = {},
    paperOrdersCount: Int = 0,
    activeAlertRulesCount: Int = 0,
    highRewardScanState: HighRewardScanState = HighRewardScanState(),
    onScanHighRewardNow: () -> Unit = {},
    onInspectHighRewardOpportunity: (HighRewardOpportunity) -> Unit = {},
    multiMarketStrictEvaluations: List<StrictMultiMarketEvaluation> = emptyList(),
    isScanningMultiMarketStrict: Boolean = false,
    multiMarketSetupBacktestSummary: String? = null,
    onScanMultiMarketStrict: () -> Unit = {},
    onRunStrictSetupBacktest: (symbol: String, setupType: SetupModelType, direction: FuturesDirection) -> Unit = { _, _, _ -> },
    // Part 3 State Parameters (defaulted for backwards caller compatibility)
    rankedMultiMarketBoard: RankedMultiMarketBoard? = null,
    multiMarketSetupDirectionalReport: DirectionalSetupBacktestReport? = null,
    managedSignals: List<ManagedSignalEntry> = emptyList(),
    signalTransitionLog: List<SignalTransitionRecord> = emptyList(),
    budgetConfig: BudgetConfig = BudgetConfig(),
    dailyRiskLedger: DailyRiskLedger = DailyRiskLedger(),
    onUpdateBudgetConfig: (BudgetConfig) -> Unit = {},
    centralVerdicts: List<com.example.data.signal.CentralSignalVerdict> = emptyList(),
    modifier: Modifier = Modifier
) {
    val colors = NexusTheme.colors
    val context = LocalContext.current
    var futuresSubTab by remember { mutableIntStateOf(0) }

    val filterOptions = listOf(
        "ALL",
        "STRONG BULLISH TREND",
        "STRONG BEARISH TREND",
        "RSI OVERBOUGHT (>70)",
        "RSI OVERSOLD (<30)",
        "EMA CROSSOVERS",
        "MACD CROSSOVERS",
        "HIGH VOLUME (RVOL > 1.5X)",
        "HIGH VOLATILITY (ATR)",
        "BOS & CHOCH",
        "ACTIVE FVG ZONES",
        "LIQUIDITY SWEEPS",
        "NEAR SUPPORT (≤0.8%)",
        "NEAR RESISTANCE (≤0.8%)"
    )

    val supportedTimeframes = listOf(
        CandleTimeframe.M15,
        CandleTimeframe.H1,
        CandleTimeframe.H4,
        CandleTimeframe.D1
    )

    val filteredAnomalyItems = scannerItems.filter { item ->
        when (scannerFilter.uppercase()) {
            "ALL" -> true
            "STRONG BULLISH TREND" -> item.matchedCriteria.any { it.contains("Strong Bullish Trend", ignoreCase = true) }
            "STRONG BEARISH TREND" -> item.matchedCriteria.any { it.contains("Strong Bearish Trend", ignoreCase = true) }
            "RSI OVERBOUGHT (>70)" -> item.rsi14 >= 70.0 || item.matchedCriteria.any { it.contains("Overbought", ignoreCase = true) }
            "RSI OVERSOLD (<30)" -> item.rsi14 <= 30.0 || item.matchedCriteria.any { it.contains("Oversold", ignoreCase = true) }
            "EMA CROSSOVERS" -> item.matchedCriteria.any { it.contains("EMA", ignoreCase = true) }
            "MACD CROSSOVERS" -> item.macdStatus.contains("Cross", ignoreCase = true) || item.matchedCriteria.any { it.contains("MACD", ignoreCase = true) }
            "HIGH VOLUME (RVOL > 1.5X)" -> item.volumeMultiplier >= 1.5 || item.matchedCriteria.any { it.contains("Volume", ignoreCase = true) }
            "HIGH VOLATILITY (ATR)" -> item.matchedCriteria.any { it.contains("Volatility", ignoreCase = true) }
            "BOS & CHOCH" -> item.matchedCriteria.any { it.contains("BOS", ignoreCase = true) || it.contains("CHOCH", ignoreCase = true) }
            "ACTIVE FVG ZONES" -> item.matchedCriteria.any { it.contains("FVG", ignoreCase = true) }
            "LIQUIDITY SWEEPS" -> item.matchedCriteria.any { it.contains("Liquidity", ignoreCase = true) || it.contains("Sweep", ignoreCase = true) }
            "NEAR SUPPORT (≤0.8%)" -> item.matchedCriteria.any { it.contains("Support", ignoreCase = true) }
            "NEAR RESISTANCE (≤0.8%)" -> item.matchedCriteria.any { it.contains("Resistance", ignoreCase = true) }
            else -> true
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 16.dp)
            .testTag("scanner_main_list"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "REAL-TIME MARKET INTELLIGENCE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colors.gold,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Text(
                        text = if (scannerScreenMode == 0) "Binance Futures Auto-Scanner" else "Market Anomaly Screener",
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }

                IconButton(
                    onClick = onRefreshScanner,
                    modifier = Modifier.size(48.dp).testTag("refresh_scanner_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Scanner",
                        tint = colors.gold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(colors.card, colors.gold.copy(alpha = 0.14f), colors.card)
                        )
                    )
                    .border(1.dp, colors.gold.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.gold.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlashOn,
                            contentDescription = "Binance Futures Multi-Timeframe Scanner",
                            tint = colors.gold,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "BINANCE USDT-M MULTI-TIMEFRAME ENGINE",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = colors.gold,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = "Completed-candle 15m/1h/4h patterns, bar-by-bar Entry Timer (PKT) & Risk Planner",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.card)
                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (scannerScreenMode == 0) colors.gold else colors.card)
                        .clickable { onModeSelected(0) }
                        .padding(vertical = 8.dp)
                        .testTag("mode_tab_futures_scanner"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.FlashOn,
                            contentDescription = null,
                            tint = if (scannerScreenMode == 0) colors.onGold else colors.gold,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Futures Auto-Scanner",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (scannerScreenMode == 0) colors.onGold else colors.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (scannerScreenMode == 1) colors.gold else colors.card)
                        .clickable { onModeSelected(1) }
                        .padding(vertical = 8.dp)
                        .testTag("mode_tab_anomaly_screener"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Timeline,
                            contentDescription = null,
                            tint = if (scannerScreenMode == 1) colors.onGold else colors.textSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Anomaly Screener",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (scannerScreenMode == 1) colors.onGold else colors.textSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }
        }

        if (scannerScreenMode == 0) {
            item {
                FuturesScannerDashboardHeader(
                    state = futuresScannerState,
                    onStartScanner = onStartFuturesScanner,
                    onStopScanner = onStopFuturesScanner,
                    onIntervalSelected = onIntervalSelected,
                    onTogglePaperTrading = onTogglePaperTrading
                )
            }

            item {
                BatteryOptimizationNoticeCard()
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.card)
                        .border(
                            1.dp,
                            if (strict92ConfidenceMode) colors.bullishGreen.copy(alpha = 0.55f) else colors.border,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onToggleStrict92Mode(!strict92ConfidenceMode) }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag("strict_92_toggle"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (strict92ConfidenceMode) "STRICT >92% ALERT & SIGNAL GATE: ON" else "92% Strict Qualifying Filter: OFF",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (strict92ConfidenceMode) colors.bullishGreen else colors.gold,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = "Push alerts require Score >92 (93+) & all filters • Paper Orders: $paperOrdersCount | Rules: $activeAlertRulesCount",
                            style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 10.sp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (strict92ConfidenceMode) colors.bullishGreen else colors.surfaceVariant)
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = if (strict92ConfidenceMode) ">92%" else "All >=60%",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (strict92ConfidenceMode) androidx.compose.ui.graphics.Color.White else colors.textSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        )
                    }
                }
            }

            item {
                com.example.ui.components.CentralSignalAuthoritySection(
                    verdicts = centralVerdicts
                )
            }

            item {
                HighRewardOpportunityRadarSection(
                    scanState = highRewardScanState,
                    onScanNow = onScanHighRewardNow,
                    onInspectOpportunity = onInspectHighRewardOpportunity
                )
            }

            item {
                MultiMarketStrictScannerSection(
                    evaluations = multiMarketStrictEvaluations,
                    isScanning = isScanningMultiMarketStrict,
                    backtestSummary = multiMarketSetupBacktestSummary,
                    onScanMultiMarket = onScanMultiMarketStrict,
                    onRunSetupBacktest = onRunStrictSetupBacktest,
                    onInspectAsset = onInspectAsset,
                    rankedBoard = rankedMultiMarketBoard,
                    directionalReport = multiMarketSetupDirectionalReport,
                    managedSignals = managedSignals,
                    transitionLog = signalTransitionLog,
                    centralVerdicts = centralVerdicts
                )
            }

            item {
                InlineRiskCalculatorCard(
                    budgetConfig = budgetConfig,
                    dailyLedger = dailyRiskLedger,
                    onUpdateBudgetConfig = onUpdateBudgetConfig
                )
            }

            item {
                val activeCount = futuresScannerState.activeSignals.count {
                    it.lifecycle == FuturesSignalLifecycle.ENTRY_CONFIRMED ||
                        it.lifecycle == FuturesSignalLifecycle.ACTIVE
                }
                val setupsCount = futuresScannerState.watchlistSetups.size
                val watchlistActiveCount = futuresWatchlist.count { it.isSelectedForScanning }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.card)
                        .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val subTabs = listOf(
                        "Confirmed ($activeCount)" to 0,
                        "Setups ($setupsCount)" to 1,
                        "Watchlist ($watchlistActiveCount)" to 2,
                        "History & Stats" to 3
                    )

                    subTabs.forEach { (title, index) ->
                        val isSel = futuresSubTab == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) colors.gold else colors.card)
                                .clickable { futuresSubTab = index }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (isSel) colors.onGold else colors.textSecondary,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 10.sp
                                )
                            )
                        }
                    }
                }
            }

            when (futuresSubTab) {
                0 -> {
                    val rawConfirmed = futuresScannerState.activeSignals.filter {
                        it.lifecycle == FuturesSignalLifecycle.ENTRY_CONFIRMED ||
                            it.lifecycle == FuturesSignalLifecycle.ACTIVE
                    }
                    val confirmedSignals = if (strict92ConfidenceMode) {
                        rawConfirmed.filter { it.qualityScore >= 92 }
                    } else rawConfirmed

                    if (confirmedSignals.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                                    .testTag(if (strict92ConfidenceMode) "no_qualifying_setup_card" else "empty_confirmed_card"),
                                colors = CardDefaults.cardColors(containerColor = colors.card)
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = colors.gold.copy(alpha = 0.6f),
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = if (strict92ConfidenceMode) "No qualifying setup available." else "No Confirmed Entries Right Now",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            color = colors.textPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    val highestRaw = rawConfirmed.maxOfOrNull { it.qualityScore }
                                    val detailText = if (strict92ConfidenceMode) {
                                        val obs = highestRaw?.let { "Highest unadjusted Model Confidence observed: $it% (< 92% threshold)." }
                                            ?: "No scanned pairs currently meet the >=92% Model Confidence rubric."
                                        "$obs Scores are never inflated. Model Confidence != Historical Empirical Win Rate."
                                    } else {
                                        "NEXUS scans completed candles only (15M primary + 1H/4H confluence). Wicks never trigger confirmation. Analysis & alerts only; no auto-trading."
                                    }
                                    Text(
                                        text = detailText,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = colors.textMuted,
                                            lineHeight = 16.sp
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        items(confirmedSignals, key = { it.id }) { signal ->
                            FuturesSignalCard(
                                signal = signal,
                                onExplainClick = { onExplainSignalWithAi(signal) },
                                onCopyLevels = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText(
                                        "NEXUS Futures Signal",
                                        "${signal.symbol} ${signal.direction.name}\nEntry: ${signal.entryZoneFormatted}\nSL: ${signal.stopLossFormatted}\nTP1: ${signal.tp1Formatted}\nTP2: ${signal.tp2Formatted}\nTP3: ${signal.tp3Formatted}\nR:R: 1:${String.format(Locale.US, "%.2f", signal.riskRewardRatio)}"
                                    )
                                    clipboard.setPrimaryClip(clip)
                                },
                                onSimulatePaperTrade = { onSimulatePaperTrade(signal) },
                                onOpenWorkflow = { onOpenWorkflow(signal) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                }

                1 -> {
                    val rawSetups = futuresScannerState.watchlistSetups
                    val setups = if (strict92ConfidenceMode) {
                        rawSetups.filter { it.qualityScore >= 92 }
                    } else rawSetups
                    if (setups.isEmpty()) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                                    .testTag(if (strict92ConfidenceMode) "no_qualifying_setup_card_setups" else "empty_setups_card"),
                                colors = CardDefaults.cardColors(containerColor = colors.card)
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.HourglassTop,
                                        contentDescription = null,
                                        tint = colors.gold.copy(alpha = 0.6f),
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = if (strict92ConfidenceMode) "No qualifying setup available." else "No Developing Setups Detected",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            color = colors.textPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = if (strict92ConfidenceMode) {
                                            "No developing setups meet the unadjusted >=92% Model Confidence rubric."
                                        } else {
                                            "Monitoring 4H and 1H structure on selected watchlist coins. Potential setups approaching Order Blocks or liquidity pools will display here as WATCHLIST_SETUP prior to entry confirmation."
                                        },
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = colors.textMuted,
                                            lineHeight = 16.sp
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        items(setups, key = { it.id }) { signal ->
                            FuturesSignalCard(
                                signal = signal,
                                onExplainClick = { onExplainSignalWithAi(signal) },
                                onCopyLevels = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText(
                                        "NEXUS Futures Setup",
                                        "${signal.symbol} ${signal.direction.name} (Developing)\nZone: ${signal.entryZoneFormatted}\nSL: ${signal.stopLossFormatted}"
                                    )
                                    clipboard.setPrimaryClip(clip)
                                },
                                onSimulatePaperTrade = { onSimulatePaperTrade(signal) },
                                onOpenWorkflow = { onOpenWorkflow(signal) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                }

                2 -> {
                    item {
                        FuturesWatchlistManagerSection(
                            watchlist = futuresWatchlist,
                            onToggleScanning = { symbol ->
                                val pair = futuresWatchlist.firstOrNull { it.symbol == symbol }
                                if (pair != null) {
                                    onToggleFuturesWatchlist(symbol, !pair.isSelectedForScanning)
                                }
                            },
                            onAddCustomPair = onAddCustomFuturesPair,
                            onRemovePair = onRemoveFuturesPair
                        )
                    }
                }

                3 -> {
                    item {
                        FuturesSignalHistoryTab(
                            history = futuresScannerState.signalHistory,
                            statistics = futuresScannerState.statistics
                        )
                    }
                }
            }

            item {
                SkillAdaptationMatrixCard()
            }
        }

        if (scannerScreenMode == 1) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.card)
                        .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    supportedTimeframes.forEach { tf ->
                        val isSel = selectedTimeframe == tf
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) colors.gold else colors.card)
                                .clickable { onTimeframeSelected(tf) }
                                .padding(vertical = 7.dp)
                                .testTag("scanner_tf_${tf.binanceInterval}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tf.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (isSel) colors.onGold else colors.textSecondary,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(filterOptions) { filter ->
                        val isSel = scannerFilter.equals(filter, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (isSel) colors.gold else colors.card)
                                .border(1.dp, if (isSel) colors.gold else colors.border, RoundedCornerShape(20.dp))
                                .clickable { onFilterSelected(filter) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag("scanner_filter_${filter.lowercase().replace(" ", "_").replace("(", "").replace(")", "").replace(">", "gt_").replace("<", "lt_").replace("≤", "lte_").replace(".", "_").replace("%", "")}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = filter,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = if (isSel) colors.onGold else colors.textSecondary,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SCAN RESULTS: ${filteredAnomalyItems.size} ASSETS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colors.textMuted,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    )

                    Text(
                        text = "Timeframe: ${selectedTimeframe.label}",
                        style = MaterialTheme.typography.labelSmall.copy(color = colors.gold)
                    )
                }
            }

            items(filteredAnomalyItems, key = { it.id }) { item ->
                val isBullish = item.detectedCondition.contains("Bullish", ignoreCase = true)
                val trendColor = if (isBullish) colors.bullishGreen else colors.bearishRed

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .testTag("scanner_card_${item.symbol}"),
                    colors = CardDefaults.cardColors(containerColor = colors.card)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = item.symbol,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = colors.textPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textMuted)
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "$${String.format(Locale.US, "%.2f", item.verifiedPrice)}",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        color = colors.textPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(trendColor.copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = item.detectedCondition,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = trendColor,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp
                                        )
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surface)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("RSI (14)", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 9.sp))
                                val rsiColor = when {
                                    item.rsi14 >= 70.0 -> colors.bearishRed
                                    item.rsi14 <= 30.0 -> colors.bullishGreen
                                    else -> colors.textPrimary
                                }
                                Text(
                                    text = String.format(Locale.US, "%.1f", item.rsi14),
                                    style = MaterialTheme.typography.bodySmall.copy(color = rsiColor, fontWeight = FontWeight.Bold)
                                )
                            }
                            Column {
                                Text("MACD", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 9.sp))
                                Text(item.macdStatus, style = MaterialTheme.typography.bodySmall.copy(color = colors.textPrimary, fontWeight = FontWeight.Medium))
                            }
                            Column {
                                Text("200 EMA", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 9.sp))
                                Text(item.emaCondition, style = MaterialTheme.typography.bodySmall.copy(color = colors.textPrimary, fontWeight = FontWeight.Medium))
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("VOL DELTA", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 9.sp))
                                Text("${item.volumeMultiplier}x", style = MaterialTheme.typography.bodySmall.copy(color = colors.gold, fontWeight = FontWeight.Bold))
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            item.matchedCriteria.forEach { crit ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(colors.surfaceVariant)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = crit,
                                        style = MaterialTheme.typography.bodySmall.copy(color = colors.gold, fontSize = 10.sp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onInspectAsset(item.assetId, item.symbol) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("scanner_inspect_btn_${item.id}"),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
                                border = androidx.compose.foundation.BorderStroke(1.dp, colors.border)
                            ) {
                                Icon(imageVector = Icons.Default.QueryStats, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.gold)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Analysis", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = { onSetAlertClick(item.symbol) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("scanner_alert_btn_${item.id}"),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.gold),
                                border = androidx.compose.foundation.BorderStroke(1.dp, colors.gold.copy(alpha = 0.5f))
                            ) {
                                Icon(imageVector = Icons.Default.AddAlert, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Set Alert", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InlineRiskCalculatorCard(
    budgetConfig: BudgetConfig,
    dailyLedger: DailyRiskLedger,
    onUpdateBudgetConfig: (BudgetConfig) -> Unit
) {
    val colors = NexusTheme.colors
    var expanded by remember { mutableStateOf(false) }
    var capitalText by remember(budgetConfig.accountBalanceUsdt) { mutableStateOf(budgetConfig.accountBalanceUsdt.toString()) }
    var riskPctText by remember(budgetConfig.maxRiskPercent) { mutableStateOf(budgetConfig.maxRiskPercent.toString()) }
    var maxTradesText by remember(budgetConfig.maxTradesPerDay) { mutableStateOf(budgetConfig.maxTradesPerDay.toString()) }
    var sampleEntryText by remember { mutableStateOf("100.0") }
    var sampleSlText by remember { mutableStateOf("98.0") }

    val cap = capitalText.toDoubleOrNull()?.coerceAtLeast(1.0) ?: budgetConfig.accountBalanceUsdt
    val pct = riskPctText.toDoubleOrNull()?.coerceIn(0.1, 25.0) ?: budgetConfig.maxRiskPercent
    val riskBudgetUsdt = cap * (pct / 100.0)
    val entryVal = sampleEntryText.toDoubleOrNull() ?: 100.0
    val slVal = sampleSlText.toDoubleOrNull() ?: 98.0
    val slDist = abs(entryVal - slVal)
    val roundTripCostRatio = ((budgetConfig.feeRatePercentPerSide * 2.0) + (budgetConfig.slippagePercentPerSide * 2.0)) / 100.0
    val unitCostLoss = slDist + (entryVal * roundTripCostRatio)
    val estPositionQty = if (unitCostLoss > 0.0 && entryVal > 0.0) riskBudgetUsdt / unitCostLoss else 0.0
    val realizedLossPct = if (cap > 0.0 && dailyLedger.realizedPnlUsdtToday < 0.0) (-dailyLedger.realizedPnlUsdtToday / cap) * 100.0 else 0.0
    val dailyWarnHit = realizedLossPct >= budgetConfig.dailyLossWarningPercent

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (dailyWarnHit) colors.warningOrange else colors.border, RoundedCornerShape(12.dp))
            .testTag("risk_calculator_card"),
        colors = CardDefaults.cardColors(containerColor = colors.card)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = colors.gold, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "RISK & POSITION CALCULATOR (DEFAULT 1% • MAX ${budgetConfig.maxTradesPerDay}/DAY)",
                            style = MaterialTheme.typography.labelSmall.copy(color = colors.gold, fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Capital $${String.format(Locale.US, "%.0f", cap)} • Risk $${String.format(Locale.US, "%.2f", riskBudgetUsdt)} (${String.format(Locale.US, "%.1f%%", pct)}) • Trades Today ${dailyLedger.tradesTakenToday}/${budgetConfig.maxTradesPerDay}",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary, fontSize = 10.sp)
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse risk calculator" else "Expand risk calculator",
                    tint = colors.gold
                )
            }

            if (dailyWarnHit) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "⚠️ 3% Daily Loss Warning Triggered (${String.format(Locale.US, "%.2f%%", realizedLossPct)}). Analysis & Alerting Only.",
                    color = colors.warningOrange,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = capitalText,
                        onValueChange = { capitalText = it },
                        label = { Text("Capital (USDT)", fontSize = 9.sp, color = colors.textMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.textPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.gold,
                            unfocusedBorderColor = colors.border,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        )
                    )
                    OutlinedTextField(
                        value = riskPctText,
                        onValueChange = { riskPctText = it },
                        label = { Text("Risk %", fontSize = 9.sp, color = colors.textMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(0.7f),
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.textPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.gold,
                            unfocusedBorderColor = colors.border,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        )
                    )
                    OutlinedTextField(
                        value = maxTradesText,
                        onValueChange = { maxTradesText = it },
                        label = { Text("Max/Day", fontSize = 9.sp, color = colors.textMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(0.7f),
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.textPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.gold,
                            unfocusedBorderColor = colors.border,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        )
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = sampleEntryText,
                        onValueChange = { sampleEntryText = it },
                        label = { Text("Entry Price", fontSize = 9.sp, color = colors.textMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.textPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.gold,
                            unfocusedBorderColor = colors.border,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        )
                    )
                    OutlinedTextField(
                        value = sampleSlText,
                        onValueChange = { sampleSlText = it },
                        label = { Text("Stop Loss", fontSize = 9.sp, color = colors.textMuted) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.textPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.gold,
                            unfocusedBorderColor = colors.border,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        )
                    )
                    Button(
                        onClick = {
                            onUpdateBudgetConfig(
                                budgetConfig.copy(
                                    accountBalanceUsdt = cap,
                                    maxRiskPercent = pct,
                                    maxRiskPerTradeAmount = riskBudgetUsdt,
                                    maxTradesPerDay = maxTradesText.toIntOrNull()?.coerceIn(1, 20) ?: budgetConfig.maxTradesPerDay
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.gold, contentColor = colors.onGold),
                        modifier = Modifier.weight(0.9f).height(48.dp)
                    ) {
                        Text("Save", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Position Size (incl. fees & slippage): ${String.format(Locale.US, "%.4f", estPositionQty)} units ($${String.format(Locale.US, "%.2f", estPositionQty * entryVal)} notional) • 3% Daily Loss Warning • Analysis & Alerts Only (Never Auto-Executes).",
                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary, fontSize = 10.sp)
                )
            }
        }
    }
}

@Composable
private fun SkillAdaptationMatrixCard() {
    val colors = NexusTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val skills = remember { SkillAdaptationRegistry.allAdaptedSkills }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .testTag("skill_adaptation_matrix_card"),
        colors = CardDefaults.cardColors(containerColor = colors.card)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp)
                    .testTag("skill_adaptation_toggle_btn"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "11-SKILL CRYPTO & FUTURES ADAPTATION MATRIX",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colors.gold,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    )
                    Text(
                        text = "9 Active Crypto Modules • Earnings Analyst (External API) • Pelosi Tracker (N/A)",
                        style = MaterialTheme.typography.bodySmall.copy(color = colors.textMuted, fontSize = 11.sp)
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse 11-skill matrix" else "Expand 11-skill matrix",
                    tint = colors.gold
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(10.dp))
                skills.forEach { skill ->
                    val statusColor = when (skill.status) {
                        SkillAdaptationStatus.ACTIVE_ADAPTED -> colors.bullishGreen
                        SkillAdaptationStatus.REQUIRES_EXTERNAL_CONFIG -> colors.gold
                        SkillAdaptationStatus.NOT_APPLICABLE -> colors.textMuted
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                            .testTag("adapted_skill_${skill.id.name.lowercase()}")
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = skill.originalName,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(statusColor.copy(alpha = 0.14f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = skill.status.badgeLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = statusColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = skill.nexusModuleName,
                            style = MaterialTheme.typography.labelSmall.copy(color = colors.gold, fontSize = 10.sp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = skill.adaptationSummary,
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary, fontSize = 11.sp, lineHeight = 15.sp)
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Policy: ${skill.honestDisclosure}",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textMuted, fontSize = 10.sp, lineHeight = 14.sp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HighRewardOpportunityRadarSection(
    scanState: HighRewardScanState,
    onScanNow: () -> Unit,
    onInspectOpportunity: (HighRewardOpportunity) -> Unit
) {
    val colors = NexusTheme.colors
    val active = scanState.activeOpportunities
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(
                1.dp,
                if (active.any { it.tier == OpportunityPriorityTier.HIGHEST_1_TO_5_PLUS }) {
                    colors.bullishGreen.copy(alpha = 0.6f)
                } else {
                    colors.gold.copy(alpha = 0.4f)
                },
                RoundedCornerShape(14.dp)
            )
            .testTag("high_reward_radar_section"),
        colors = CardDefaults.cardColors(containerColor = colors.card)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "NEXUS 1:5 HIGH-REWARD RADAR",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colors.gold,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                    )
                    Text(
                        text = "Strict >=1:4 Min Net R:R | Top Priority >=1:5 (PKT)",
                        style = MaterialTheme.typography.bodySmall.copy(color = colors.textMuted, fontSize = 10.sp)
                    )
                }
                OutlinedButton(
                    onClick = onScanNow,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.gold.copy(alpha = 0.6f)),
                    modifier = Modifier.testTag("scan_1_5_now_btn")
                ) {
                    Text("Scan 1:5", fontSize = 11.sp, color = colors.gold, fontWeight = FontWeight.Bold)
                }
            }

            if (active.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface)
                        .padding(12.dp)
                        .testTag("high_reward_wait_card"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "WAIT — No Confirmed >=1:4 Opportunity",
                        style = MaterialTheme.typography.labelMedium.copy(color = colors.gold, fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = scanState.waitBannerMessage,
                        style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary, fontSize = 11.sp, lineHeight = 15.sp)
                    )
                    Text(
                        text = "Last Checked (PKT): ${scanState.lastScannedPkt} • WorkManager scans ~every 15m when closed (Doze/battery limits apply); Foreground Scanner runs continuously. Instant push when closed uses free FCM topics (nexus_high_reward_1_5 / nexus_qualified_1_4) paired with the optional GitHub Actions scheduled workflow. Alerts require internet and cannot fire while force-stopped.",
                        style = MaterialTheme.typography.bodySmall.copy(color = colors.textMuted, fontSize = 10.sp, lineHeight = 14.sp)
                    )
                }
            } else {
                active.forEach { opp ->
                    val isTop = opp.tier == OpportunityPriorityTier.HIGHEST_1_TO_5_PLUS
                    val accent = if (isTop) colors.bullishGreen else colors.gold
                    val dirColor = if (opp.direction == FuturesDirection.LONG) colors.bullishGreen else colors.bearishRed

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.surface)
                            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                            .clickable { onInspectOpportunity(opp) }
                            .padding(12.dp)
                            .testTag("high_reward_card_${opp.symbol}"),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(accent.copy(alpha = 0.16f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isTop) "NEXUS 1:5" else "QUALIFIED 1:4",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = accent,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 9.sp
                                        )
                                    )
                                }
                                Text(
                                    text = "${opp.symbol} ${opp.direction.name}",
                                    style = MaterialTheme.typography.titleSmall.copy(color = dirColor, fontWeight = FontWeight.Bold)
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Net 1:${String.format(Locale.US, "%.2f", opp.netRiskRewardRatio)}",
                                    style = MaterialTheme.typography.labelMedium.copy(color = accent, fontWeight = FontWeight.ExtraBold)
                                )
                                Text(
                                    text = "Gross 1:${String.format(Locale.US, "%.2f", opp.grossRiskRewardRatio)}",
                                    style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 9.sp)
                                )
                            }
                        }

                        Text(
                            text = "Trigger (PKT): ${opp.entryTriggerTimestampPkt}",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.gold, fontSize = 10.sp)
                        )
                        Text(
                            text = "Entry ${opp.entryFormatted} | SL ${opp.stopLossFormatted} | TP ${opp.takeProfitFormatted}",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "$1 Risk -> $${String.format(Locale.US, "%.2f", opp.normalizedNetRewardUsd)} Net Reward | Est. Net +$${String.format(Locale.US, "%.2f", opp.estimatedNetProfitUsdt)} / -$${String.format(Locale.US, "%.2f", opp.estimatedNetLossUsdt)} (+${String.format(Locale.US, "%.1f", opp.estimatedNetRoiPercent)}% ROI @ ${opp.leverageAssumed}x)",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary, fontSize = 10.sp)
                        )
                        Text(
                            text = opp.romanUrduExplanation.whySelected,
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textMuted, fontSize = 10.sp, lineHeight = 14.sp)
                        )
                        Text(
                            text = "Tap for full Urdu / Roman Urdu explanation, risks & invalidations ->",
                            style = MaterialTheme.typography.labelSmall.copy(color = colors.gold, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        )
                    }
                }
            }
        }
    }
}
