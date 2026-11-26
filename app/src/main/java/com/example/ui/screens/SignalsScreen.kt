package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CandleTimeframe
import com.example.data.model.Signal
import com.example.data.model.SignalStatus
import com.example.data.model.SignalType
import com.example.data.signal.AiTradingSignal
import com.example.data.signal.SignalDecision
import com.example.ui.components.SignalItemCard
import com.example.ui.theme.NexusTheme
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignalsScreen(
    currentAiSignal: AiTradingSignal?,
    isAnalyzing: Boolean,
    selectedSymbol: String,
    selectedTimeframe: CandleTimeframe,
    isExpanded: Boolean,
    onAssetSelected: (String) -> Unit,
    onTimeframeSelected: (CandleTimeframe) -> Unit,
    onAnalyzeClick: () -> Unit,
    onToggleExpanded: () -> Unit,
    onInfoClick: (String) -> Unit,
    aiSignalsList: List<AiTradingSignal>,
    signals: List<Signal>,
    signalFilter: String,
    onFilterSelected: (String) -> Unit,
    onSetAlertClick: (symbol: String) -> Unit,
    onCopyLevelsClick: (Signal) -> Unit,
    errorMessage: String? = null,
    onRetryClick: () -> Unit = {},
    onPaperTradeClick: (AiTradingSignal) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = NexusTheme.colors
    val context = LocalContext.current
    val supportedAssets = listOf(
        Pair("BTC", "Bitcoin"),
        Pair("ETH", "Ethereum"),
        Pair("SOL", "Solana"),
        Pair("BNB", "BNB"),
        Pair("XRP", "XRP"),
        Pair("DOGE", "Dogecoin"),
        Pair("ADA", "Cardano"),
        Pair("LINK", "Chainlink"),
        Pair("XAUUSD", "Gold"),
        Pair("XAGUSD", "Silver"),
        Pair("OIL_WTI", "Crude Oil")
    )

    val timeframes = listOf(
        CandleTimeframe.M1,
        CandleTimeframe.M5,
        CandleTimeframe.M15,
        CandleTimeframe.M30,
        CandleTimeframe.H1,
        CandleTimeframe.H4,
        CandleTimeframe.D1,
        CandleTimeframe.W1
    )

    val filterTabs = listOf("ALL", "LONG", "SHORT", "NO TRADE")

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ----------------------------------------------------
        // 1. HEADER & CONTROL PANEL
        // ----------------------------------------------------
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "MULTI-FACTOR INTELLIGENCE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colors.gold,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Text(
                        text = "AI Trading Signal Engine",
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }

                Button(
                    onClick = onAnalyzeClick,
                    enabled = !isAnalyzing,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.gold),
                    modifier = Modifier.testTag("signal_analyze_button")
                ) {
                    if (isAnalyzing) {
                        CircularProgressIndicator(
                            color = colors.onGold,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = colors.onGold,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Analyze",
                            color = colors.onGold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Asset selector chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(supportedAssets) { (sym, name) ->
                    val isSel = selectedSymbol.equals(sym, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSel) colors.gold else colors.card)
                            .border(1.dp, if (isSel) colors.gold else colors.border, RoundedCornerShape(12.dp))
                            .clickable { onAssetSelected(sym) }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                            .testTag("signal_asset_chip_$sym"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = sym,
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = if (isSel) colors.onGold else colors.textPrimary,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 12.sp
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Timeframe selector row
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(timeframes) { tf ->
                    val isSel = selectedTimeframe == tf
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) colors.surfaceVariant else colors.card)
                            .border(1.dp, if (isSel) colors.gold.copy(alpha = 0.6f) else colors.border, RoundedCornerShape(8.dp))
                            .clickable { onTimeframeSelected(tf) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("signal_tf_chip_${tf.label}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tf.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (isSel) colors.gold else colors.textSecondary,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }

            if (isAnalyzing) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(modifier = Modifier.fillMaxWidth().testTag("signal_updating_indicator")) {
                    LinearProgressIndicator(
                        color = colors.gold,
                        trackColor = colors.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Updating $selectedSymbol (${selectedTimeframe.label}) strictly on closed candles...",
                        color = colors.textMuted,
                        fontSize = 11.sp
                    )
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("signal_error_card"),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.bearishRedBg),
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.bearishRed.copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "Signal Update Issue",
                                color = colors.bearishRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = errorMessage,
                                color = colors.textPrimary,
                                fontSize = 11.sp
                            )
                        }
                        OutlinedButton(
                            onClick = onRetryClick,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.bearishRed),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("signal_retry_button")
                        ) {
                            Text("Retry", color = colors.bearishRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // ----------------------------------------------------
        // 2. HERO SIGNAL CARD (LONG / SHORT / NO TRADE)
        // ----------------------------------------------------
        item {
            if (isAnalyzing && currentAiSignal == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.card)
                        .border(1.dp, colors.border, RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = colors.gold, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Analyzing $selectedSymbol OHLCV, Indicators & SMC...",
                            color = colors.textSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            } else if (currentAiSignal != null) {
                HeroSignalCard(
                    signal = currentAiSignal,
                    isExpanded = isExpanded,
                    onToggleExpanded = onToggleExpanded,
                    onInfoClick = onInfoClick,
                    onCopyLevelsClick = {
                        val clip = currentAiSignal.riskPlan?.let { plan ->
                            "${currentAiSignal.symbol} ${currentAiSignal.decision.label} on ${currentAiSignal.primaryTimeframe.label}\n" +
                                    "Entry: ${plan.entryZoneFormatted}\n" +
                                    "Stop Loss: ${plan.stopLossFormatted} (-${String.format(Locale.US, "%.1f", plan.stopLossDistancePercent)}%)\n" +
                                    "TP1: ${plan.takeProfit1Formatted} (+${String.format(Locale.US, "%.1f", plan.takeProfit1DistancePercent)}%)\n" +
                                    "TP2: ${plan.takeProfit2Formatted} (+${String.format(Locale.US, "%.1f", plan.takeProfit2DistancePercent)}%)\n" +
                                    "TP3: ${plan.takeProfit3Formatted} (+${String.format(Locale.US, "%.1f", plan.takeProfit3DistancePercent)}%)\n" +
                                    "R:R: ${plan.riskRewardFormatted} | Max Leverage: ${plan.suggestedMaxLeverage}x\n" +
                                    "Setup Quality: ${currentAiSignal.strictQualityReport?.totalScore ?: currentAiSignal.confidence.totalScore}/100 (Not Win %)\n" +
                                    "Invalidation: ${plan.invalidationCondition}"
                        } ?: "${currentAiSignal.symbol} NO TRADE: ${currentAiSignal.noTradeReason?.headline}"
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("NEXUS Signal", clip))
                    },
                    onSetAlertClick = { onSetAlertClick(currentAiSignal.symbol) },
                    onPaperTradeClick = { onPaperTradeClick(currentAiSignal) }
                )
            }
        }

        // ----------------------------------------------------
        // 3. TRANSPARENT CONFIDENCE SCORE BREAKDOWN
        // ----------------------------------------------------
        if (currentAiSignal != null) {
            item {
                ConfidenceBreakdownCard(
                    confidence = currentAiSignal.confidence,
                    onInfoClick = onInfoClick
                )
            }
        }

        // ----------------------------------------------------
        // 3B. PART 2: STRICT 8-GROUP QUALITY & MANDATORY FILTERS
        // ----------------------------------------------------
        currentAiSignal?.strictQualityReport?.let { strictReport ->
            item {
                StrictQualityAuditCard(report = strictReport)
            }
        }

        // ----------------------------------------------------
        // 4. MULTI-TIMEFRAME CONFIRMATION MATRIX
        // ----------------------------------------------------
        if (currentAiSignal != null && currentAiSignal.timeframeConditions.isNotEmpty()) {
            item {
                MultiTimeframeMatrixCard(
                    conditions = currentAiSignal.timeframeConditions,
                    agreementCount = currentAiSignal.mtfAgreementCount,
                    totalCount = currentAiSignal.mtfTotalCount,
                    agreementPercent = currentAiSignal.mtfAgreementPercent,
                    decision = currentAiSignal.decision
                )
            }
        }

        // ----------------------------------------------------
        // 5. SAFETY & REGULATORY COMPLIANCE BANNER
        // ----------------------------------------------------
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Manual Execution",
                        tint = colors.gold,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Manual Execution Only: NEXUS AI provides decision analytics and never executes trades automatically. Risk is user-managed.",
                        color = colors.textMuted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // ----------------------------------------------------
        // 6. MARKET-WIDE SIGNALS SCANNER FEED
        // ----------------------------------------------------
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Market Opportunities Feed",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                )

                Text(
                    text = "${aiSignalsList.size} Scanned",
                    color = colors.gold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Filter row
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(filterTabs) { filter ->
                    val isSel = signalFilter.equals(filter, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSel) colors.gold else colors.card)
                            .clickable { onFilterSelected(filter) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("signal_feed_filter_${filter.lowercase().replace(" ", "_")}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = filter,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (isSel) colors.onGold else colors.textSecondary,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }
        }

        // Filter and display signals
        val filteredList = aiSignalsList.filter { sig ->
            when (signalFilter.uppercase(Locale.US)) {
                "ALL" -> true
                "LONG" -> sig.decision == SignalDecision.LONG
                "SHORT" -> sig.decision == SignalDecision.SHORT
                "NO TRADE" -> sig.decision == SignalDecision.NO_TRADE
                else -> true
            }
        }

        if (filteredList.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No signals matching filter '$signalFilter'",
                        color = colors.textMuted,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            items(filteredList, key = { it.id }) { sig ->
                CompactAiSignalRow(
                    signal = sig,
                    isSelected = sig.symbol.equals(selectedSymbol, ignoreCase = true),
                    onSelect = { onAssetSelected(sig.symbol) },
                    onInfoClick = onInfoClick,
                    onPaperTradeClick = { onPaperTradeClick(sig) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// HERO SIGNAL CARD
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HeroSignalCard(
    signal: AiTradingSignal,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onInfoClick: (String) -> Unit,
    onCopyLevelsClick: () -> Unit,
    onSetAlertClick: () -> Unit,
    onPaperTradeClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = NexusTheme.colors
    val isLong = signal.decision == SignalDecision.LONG
    val isShort = signal.decision == SignalDecision.SHORT
    val isNoTrade = signal.decision == SignalDecision.NO_TRADE

    val accentColor = when {
        isLong -> colors.bullishGreen
        isShort -> colors.bearishRed
        else -> colors.gold
    }

    val badgeBg = when {
        isLong -> colors.bullishGreenBg
        isShort -> colors.bearishRedBg
        else -> colors.surfaceVariant
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("hero_signal_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            Brush.verticalGradient(
                listOf(accentColor.copy(alpha = 0.6f), colors.border)
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            // Header: Symbol, Timeframe, Signal Decision Badge + Roman Urdu Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = signal.symbol,
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(colors.surface, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = signal.primaryTimeframe.label,
                            color = colors.gold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Decision Badge + Info
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(badgeBg)
                            .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("signal_decision_badge")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = when {
                                    isLong -> Icons.Default.TrendingUp
                                    isShort -> Icons.Default.TrendingDown
                                    else -> Icons.Default.HourglassTop
                                },
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = signal.decision.label,
                                color = accentColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Roman Urdu Info Icon
                    IconButton(
                        onClick = {
                            val termKey = when {
                                isLong -> "LONG"
                                isShort -> "SHORT"
                                else -> "NO_TRADE"
                            }
                            onInfoClick(termKey)
                        },
                        modifier = Modifier.size(28.dp).testTag("signal_decision_info_icon")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Roman Urdu Info",
                            tint = colors.gold,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Sub-header: Freshness and Confluence Rating
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${signal.dataSource} • ${signal.dataFreshness} • ${signal.formattedTimestamp}",
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val qualityScore = signal.strictQualityReport?.totalScore ?: signal.confidence.totalScore
                    Text(
                        text = "Quality: ",
                        color = colors.textSecondary,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "$qualityScore/100 (Not Win%)",
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Confidence Info",
                        tint = colors.textMuted,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onInfoClick("CONFIDENCE_SCORE") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ------------------------------------------------
            // BRANCH A: NO TRADE VIEW
            // ------------------------------------------------
            if (isNoTrade && signal.noTradeReason != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Advisory",
                                tint = colors.gold,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = signal.noTradeReason.headline,
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = signal.noTradeReason.detailedExplanation,
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )

                        if (signal.noTradeReason.conflictingFactors.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Conflicting Factors:",
                                color = colors.bearishRed,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            )
                            signal.noTradeReason.conflictingFactors.forEach { factor ->
                                Text(
                                    text = "• $factor",
                                    color = colors.textMuted,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Recommended: ${signal.noTradeReason.recommendedAction}",
                            color = colors.gold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // ------------------------------------------------
            // BRANCH B: LONG / SHORT KEY LEVELS & RISK PLAN
            // ------------------------------------------------
            if (!isNoTrade && signal.riskPlan != null) {
                val plan = signal.riskPlan

                // Visual Risk-Reward Bar
                RiskRewardVisualBar(
                    riskPercent = plan.estimatedRiskPercent,
                    rewardPercent = plan.potentialRewardPercent,
                    rrFormatted = plan.riskRewardFormatted,
                    onInfoClick = onInfoClick
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Key Levels Grid
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Entry Row
                    LevelRow(
                        label = "ENTRY ZONE",
                        value = plan.entryZoneFormatted,
                        subText = "Discount Structural Level",
                        color = colors.textPrimary,
                        termKey = "ENTRY",
                        onInfoClick = onInfoClick
                    )

                    // Stop Loss Row
                    LevelRow(
                        label = "STOP LOSS",
                        value = plan.stopLossFormatted,
                        subText = "-${String.format(Locale.US, "%.2f", plan.stopLossDistancePercent)}%",
                        color = colors.bearishRed,
                        termKey = "STOP_LOSS",
                        onInfoClick = onInfoClick
                    )

                    // Take Profit Targets
                    LevelRow(
                        label = "TAKE PROFIT 1",
                        value = plan.takeProfit1Formatted,
                        subText = "+${String.format(Locale.US, "%.2f", plan.takeProfit1DistancePercent)}% (1.6x R:R)",
                        color = colors.bullishGreen,
                        termKey = "TAKE_PROFIT",
                        onInfoClick = onInfoClick
                    )

                    LevelRow(
                        label = "TAKE PROFIT 2 (Main)",
                        value = plan.takeProfit2Formatted,
                        subText = "+${String.format(Locale.US, "%.2f", plan.takeProfit2DistancePercent)}% (${plan.riskRewardFormatted})",
                        color = colors.bullishGreen,
                        termKey = "TAKE_PROFIT",
                        onInfoClick = onInfoClick
                    )

                    LevelRow(
                        label = "TAKE PROFIT 3 (Runner)",
                        value = plan.takeProfit3Formatted,
                        subText = "+${String.format(Locale.US, "%.2f", plan.takeProfit3DistancePercent)}% (Fib 1.618)",
                        color = colors.gold,
                        termKey = "TAKE_PROFIT",
                        onInfoClick = onInfoClick
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Leverage & Invalidation Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Suggested Max Leverage
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Max Leverage: ${plan.suggestedMaxLeverage}x",
                            color = colors.gold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Leverage Info",
                            tint = colors.gold,
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { onInfoClick("LEVERAGE") }
                        )
                    }

                    // Risk/Reward ratio badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "R:R: ${plan.riskRewardFormatted}",
                            color = colors.bullishGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "R:R Info",
                            tint = colors.bullishGreen,
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { onInfoClick("RISK_REWARD") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Invalidation Condition
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.bearishRedBg)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Block,
                            contentDescription = "Invalidation",
                            tint = colors.bearishRed,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Invalidation Condition:",
                                color = colors.bearishRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            Text(
                                text = plan.invalidationCondition,
                                color = colors.textPrimary,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                        }
                        IconButton(
                            onClick = { onInfoClick("INVALIDATION") },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Invalidation Info",
                                tint = colors.bearishRed,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // ------------------------------------------------
            // SUPPORTING CHIPS (INDICATORS & SMC)
            // ------------------------------------------------
            if (signal.supportingIndicators.isNotEmpty() || signal.smcConfirmations.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    signal.smcConfirmations.forEach { smc ->
                        Box(
                            modifier = Modifier
                                .background(colors.surfaceVariant, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(text = smc, color = colors.gold, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    signal.supportingIndicators.forEach { ind ->
                        Box(
                            modifier = Modifier
                                .background(colors.surface, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(text = ind, color = colors.textSecondary, fontSize = 10.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons: Copy Levels + Set Alert
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onCopyLevelsClick,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).testTag("copy_signal_levels_btn")
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy", tint = colors.textPrimary, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Copy Levels", color = colors.textPrimary, fontSize = 11.sp)
                }

                Button(
                    onClick = onSetAlertClick,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceVariant),
                    modifier = Modifier.weight(1f).testTag("set_signal_alert_btn")
                ) {
                    Icon(imageVector = Icons.Default.AddAlert, contentDescription = "Alert", tint = colors.gold, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Set Price Alert", color = colors.gold, fontSize = 11.sp)
                }
            }

            if (!isNoTrade && signal.riskPlan != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onPaperTradeClick,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .testTag("hero_paper_trade_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountBalanceWallet,
                        contentDescription = "Paper Trade",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "PAPER TRADE THIS ${signal.decision.label} SETUP",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// LEVEL ROW HELPER
// ---------------------------------------------------------------------------------------------

@Composable
fun LevelRow(
    label: String,
    value: String,
    subText: String,
    color: Color,
    termKey: String,
    onInfoClick: (String) -> Unit
) {
    val colors = NexusTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = colors.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "$label Info",
                tint = colors.textMuted,
                modifier = Modifier
                    .size(13.dp)
                    .clickable { onInfoClick(termKey) }
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = value,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subText,
                color = colors.textMuted,
                fontSize = 10.sp
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// RISK-REWARD VISUAL BAR
// ---------------------------------------------------------------------------------------------

@Composable
fun RiskRewardVisualBar(
    riskPercent: Double,
    rewardPercent: Double,
    rrFormatted: String,
    onInfoClick: (String) -> Unit
) {
    val colors = NexusTheme.colors
    val total = (riskPercent + rewardPercent).coerceAtLeast(1.0)
    val riskFraction = (riskPercent / total).toFloat().coerceIn(0.1f, 0.5f)
    val rewardFraction = 1f - riskFraction

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Risk: ${String.format(Locale.US, "%.1f", riskPercent)}%",
                color = colors.bearishRed,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Ratio $rrFormatted",
                    color = colors.gold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Risk Reward Info",
                    tint = colors.textMuted,
                    modifier = Modifier
                        .size(13.dp)
                        .clickable { onInfoClick("RISK_REWARD") }
                )
            }

            Text(
                text = "Target: +${String.format(Locale.US, "%.1f", rewardPercent)}%",
                color = colors.bullishGreen,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
        ) {
            Box(
                modifier = Modifier
                    .weight(riskFraction)
                    .fillMaxSize()
                    .background(colors.bearishRed)
            )
            Spacer(modifier = Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .weight(rewardFraction)
                    .fillMaxSize()
                    .background(colors.bullishGreen)
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// STRICT 8-GROUP SIGNAL QUALITY & MANDATORY FILTERS CARD
// ---------------------------------------------------------------------------------------------

@Composable
fun StrictQualityAuditCard(
    report: com.example.data.signal.StrictSignalQualityReport
) {
    val colors = NexusTheme.colors
    val tierColor = when (report.qualificationTier) {
        com.example.data.signal.StrictQualificationTier.HIGH_QUALITY_SETUP -> colors.bullishGreen
        com.example.data.signal.StrictQualificationTier.QUALIFIED_SETUP -> colors.gold
        com.example.data.signal.StrictQualificationTier.NO_TRADE -> colors.bearishRed
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("strict_quality_audit_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        border = androidx.compose.foundation.BorderStroke(1.dp, tierColor.copy(alpha = 0.55f))
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "STRICT 8-GROUP SIGNAL QUALITY",
                        color = colors.gold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = report.qualificationTier.label,
                        color = tierColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${report.totalScore} / 100",
                        color = tierColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = report.qualityScoreDisclaimer,
                color = colors.textMuted,
                fontSize = 10.sp,
                lineHeight = 13.sp
            )

            report.netRiskRewardSummary?.let { rr ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Net R:R (after fees/slippage): ${rr.netRiskRewardFormatted} • ${if (rr.meetsMinOneToFour) "Meets >= 1:4.0" else "Below 1:4.0"}",
                    color = if (rr.meetsMinOneToFour) colors.bullishGreen else colors.bearishRed,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "8 Independent Evidence Groups",
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            report.evidenceGroups.forEach { grp ->
                val frac = (grp.awardedPoints.toFloat() / grp.maxPoints).coerceIn(0f, 1f)
                val barColor = when {
                    grp.status == com.example.data.indicators.IndicatorValidationStatus.UNAVAILABLE ||
                        grp.status == com.example.data.indicators.IndicatorValidationStatus.STALE -> colors.textMuted
                    frac >= 0.75f -> colors.bullishGreen
                    frac >= 0.5f -> colors.gold
                    else -> colors.bearishRed
                }
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${grp.groupId.code}. ${grp.groupId.title}",
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${grp.awardedPoints}/${grp.maxPoints} (${grp.status.label})",
                            color = barColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                        color = barColor,
                        trackColor = colors.surfaceVariant
                    )
                    Text(
                        text = grp.summary,
                        color = colors.textMuted,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Mandatory Gate Filters (${report.passedFilterCount}/${report.mandatoryFilters.size} Passed)",
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))

            report.mandatoryFilters.forEach { f ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (f.passed) Icons.Default.CheckCircle else Icons.Default.Block,
                        contentDescription = if (f.passed) "Passed" else "Failed",
                        tint = if (f.passed) colors.bullishGreen else colors.bearishRed,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "#${f.filterNumber} ${f.name}: ${f.reason}",
                        color = if (f.passed) colors.textSecondary else colors.bearishRed,
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// TRANSPARENT CONFIDENCE SCORE BREAKDOWN CARD
// ---------------------------------------------------------------------------------------------

@Composable
fun ConfidenceBreakdownCard(
    confidence: com.example.data.signal.ConfidenceScoreBreakdown,
    onInfoClick: (String) -> Unit
) {
    val colors = NexusTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Confidence Breakdown",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Confidence Info",
                        tint = colors.gold,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onInfoClick("CONFIDENCE_SCORE") }
                    )
                }

                Text(
                    text = "${confidence.totalScore} / 100",
                    color = colors.gold,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Transparent multi-factor scoring based on verified technical indicators and market structure.",
                color = colors.textMuted,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Factors progress rows
            confidence.factors.forEach { factor ->
                FactorProgressRow(factor = factor)
                Spacer(modifier = Modifier.height(10.dp))
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Notice: Confidence score reflects data agreement from historical patterns, not a guaranteed probability of profit.",
                color = colors.textMuted,
                fontSize = 10.sp,
                lineHeight = 13.sp
            )
        }
    }
}

@Composable
fun FactorProgressRow(factor: com.example.data.signal.SignalFactorReason) {
    val colors = NexusTheme.colors
    val fraction = (factor.awardedPoints.toFloat() / factor.maxPoints).coerceIn(0f, 1f)
    val color = when (factor.stance) {
        "BULLISH" -> colors.bullishGreen
        "BEARISH" -> colors.bearishRed
        "CONFLICTING" -> colors.gold
        else -> colors.textSecondary
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = factor.factorName,
                color = colors.textPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${factor.awardedPoints} / ${factor.maxPoints} pts",
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(3.dp))

        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = colors.surfaceVariant
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = factor.summary,
            color = colors.textMuted,
            fontSize = 10.sp
        )
    }
}

// ---------------------------------------------------------------------------------------------
// MULTI-TIMEFRAME CONFIRMATION MATRIX
// ---------------------------------------------------------------------------------------------

@Composable
fun MultiTimeframeMatrixCard(
    conditions: List<com.example.data.signal.TimeframeCondition>,
    agreementCount: Int,
    totalCount: Int,
    agreementPercent: Int,
    decision: SignalDecision
) {
    val colors = NexusTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border)
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
                Text(
                    text = "Multi-Timeframe Confirmation",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                )

                Box(
                    modifier = Modifier
                        .background(colors.surfaceVariant, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "$agreementCount / $totalCount Aligned ($agreementPercent%)",
                        color = if (agreementPercent >= 65) colors.bullishGreen else colors.gold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 8-Timeframe Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                conditions.forEach { cond ->
                    val color = when (cond.stance) {
                        "BULLISH" -> colors.bullishGreen
                        "BEARISH" -> colors.bearishRed
                        else -> colors.textMuted
                    }
                    val bg = when (cond.stance) {
                        "BULLISH" -> colors.bullishGreenBg
                        "BEARISH" -> colors.bearishRedBg
                        else -> colors.surface
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(bg)
                            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = cond.timeframe.label,
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(color)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = cond.stance.take(4),
                            color = color,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// COMPACT SCANNED SIGNAL ROW
// ---------------------------------------------------------------------------------------------

@Composable
fun CompactAiSignalRow(
    signal: AiTradingSignal,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onInfoClick: (String) -> Unit,
    onPaperTradeClick: () -> Unit = {}
) {
    val colors = NexusTheme.colors
    val isLong = signal.decision == SignalDecision.LONG
    val isShort = signal.decision == SignalDecision.SHORT
    val accentColor = when {
        isLong -> colors.bullishGreen
        isShort -> colors.bearishRed
        else -> colors.gold
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .testTag("signal_feed_row_${signal.symbol}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) colors.surfaceVariant else colors.card
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) colors.gold else colors.border
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = signal.symbol,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = signal.primaryTimeframe.label,
                        color = colors.gold,
                        fontSize = 10.sp
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (signal.decision == SignalDecision.NO_TRADE) {
                        signal.noTradeReason?.headline ?: "No Setup"
                    } else {
                        "Entry: ${signal.riskPlan?.entryZoneFormatted ?: "N/A"}"
                    },
                    color = colors.textMuted,
                    fontSize = 11.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (signal.decision != SignalDecision.NO_TRADE && signal.riskPlan != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.gold.copy(alpha = 0.16f))
                            .border(1.dp, colors.gold.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .clickable { onPaperTradeClick() }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                            .testTag("compact_paper_trade_btn_${signal.symbol}")
                    ) {
                        Text(
                            text = "PAPER",
                            color = colors.gold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .background(
                            when {
                                isLong -> colors.bullishGreenBg
                                isShort -> colors.bearishRedBg
                                else -> colors.surface
                            },
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = signal.decision.label,
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }

                Text(
                    text = "${signal.strictQualityReport?.totalScore ?: signal.confidence.totalScore}/100",
                    color = accentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }
    }
}
