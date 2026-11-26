package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AssetCategory
import com.example.data.model.MarketDataStatus
import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.ManagedSignalEntry
import com.example.data.scanner.RankedMultiMarketBoard
import com.example.data.scanner.RankedMultiMarketOpportunity
import com.example.data.scanner.SetupCandidateEvaluation
import com.example.data.scanner.SetupModelType
import com.example.data.scanner.SetupValidationStatus
import com.example.data.scanner.SignalTransitionRecord
import com.example.data.scanner.StrictMultiMarketEvaluation
import com.example.data.timemachine.DirectionalSetupBacktestReport
import com.example.data.timemachine.HistoricalBacktestResult
import com.example.ui.theme.NexusTheme
import java.util.Locale
import kotlin.math.abs

@Composable
fun MultiMarketStrictScannerSection(
    evaluations: List<StrictMultiMarketEvaluation>,
    isScanning: Boolean,
    backtestSummary: String?,
    onScanMultiMarket: () -> Unit,
    onRunSetupBacktest: (symbol: String, setupType: SetupModelType, direction: FuturesDirection) -> Unit,
    onInspectAsset: (assetId: String, symbol: String) -> Unit,
    rankedBoard: RankedMultiMarketBoard? = null,
    directionalReport: DirectionalSetupBacktestReport? = null,
    managedSignals: List<ManagedSignalEntry> = emptyList(),
    transitionLog: List<SignalTransitionRecord> = emptyList()
) {
    val colors = NexusTheme.colors
    var selectedCategory by remember { mutableStateOf(AssetCategory.ALL) }

    val managedBySymbol = remember(managedSignals) {
        managedSignals.associateBy { it.symbol.uppercase(Locale.US) }
    }

    val rankedItems: List<RankedMultiMarketOpportunity> = if (rankedBoard != null) {
        rankedBoard.opportunities.filter { item ->
            selectedCategory == AssetCategory.ALL || item.evaluation.category == selectedCategory
        }
    } else {
        evaluations.filter { eval ->
            selectedCategory == AssetCategory.ALL || eval.category == selectedCategory
        }.mapIndexed { idx, eval ->
            RankedMultiMarketOpportunity(
                rank = idx + 1,
                evaluation = eval,
                setup = eval.bestSetup,
                qualityReport = null,
                dataStatus = if (eval.status == SetupValidationStatus.DATA_UNAVAILABLE) MarketDataStatus.UNAVAILABLE else MarketDataStatus.LIVE,
                rankingScore = (eval.bestSetup?.score ?: 0).toDouble(),
                isQualified = eval.status == SetupValidationStatus.CONFIRMED &&
                    (eval.bestSetup?.estimatedNetRiskReward ?: 0.0) >= 4.0,
                isPreferredFiveToOne = (eval.bestSetup?.estimatedNetRiskReward ?: 0.0) >= 5.0,
                sessionNote = if (eval.category == AssetCategory.CRYPTO) "Crypto 24/7 Market — Active all hours" else eval.venueName,
                rankingSummary = ""
            )
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("multi_market_strict_section"),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, colors.gold.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "MULTI-MARKET FIB, RANKING & LIFECYCLE ENGINE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colors.gold,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.9.sp
                        )
                    )
                    Text(
                        text = "Crypto 24/7 (Binance/Bybit) • Gold/Silver/Oil • NVDA/GOOG/GOOGL",
                        style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary, fontSize = 11.sp)
                    )
                }

                Button(
                    onClick = onScanMultiMarket,
                    enabled = !isScanning,
                    modifier = Modifier.testTag("multi_market_strict_scan_btn"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.gold,
                        contentColor = colors.onGold
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = colors.onGold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Scanning", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Scan Multi-Market", modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Scan Markets", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Category Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(AssetCategory.ALL, AssetCategory.CRYPTO, AssetCategory.COMMODITIES, AssetCategory.STOCKS).forEach { cat ->
                    val isSel = selectedCategory == cat
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSel) colors.gold else colors.surface)
                            .border(1.dp, if (isSel) colors.gold else colors.border, RoundedCornerShape(16.dp))
                            .clickable { selectedCategory = cat }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("strict_cat_${cat.name.lowercase()}")
                    ) {
                        Text(
                            text = cat.label,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSel) colors.onGold else colors.textSecondary
                        )
                    }
                }
            }

            // Empty / No Strong Opportunity Banner when board has 0 qualified setups
            if (rankedBoard != null && rankedBoard.qualifiedCount == 0 && !rankedBoard.emptyHeadline.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.warningOrange.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                        .testTag("no_strong_opportunity_banner")
                ) {
                    Text(
                        text = rankedBoard.emptyHeadline,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.warningOrange
                    )
                    rankedBoard.emptyReason?.let { reason ->
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = reason,
                            fontSize = 10.sp,
                            color = colors.textSecondary,
                            lineHeight = 14.sp
                        )
                    }
                    Text(
                        text = "Checked (PKT): ${rankedBoard.generatedAtPkt} • Strict Net R:R >= 1:4.0 required (1:5+ top rank).",
                        fontSize = 9.sp,
                        color = colors.textMuted
                    )
                }
            }

            // Directional Backtest Summary + IS/OOS Split Card
            if (directionalReport != null) {
                Spacer(modifier = Modifier.height(8.dp))
                DirectionalBacktestComparisonCard(report = directionalReport)
            } else if (!backtestSummary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.gold.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = backtestSummary,
                        fontSize = 11.sp,
                        color = colors.textPrimary
                    )
                }
            }

            // Signal Lifecycle Transition Audit Trail preview
            if (transitionLog.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceVariant)
                        .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .testTag("signal_transition_audit_card")
                ) {
                    Text(
                        text = "SIGNAL LIFECYCLE AUDIT (${transitionLog.size} transitions • Anti-Reversal Guard Active)",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.gold
                    )
                    transitionLog.takeLast(3).reversed().forEach { rec ->
                        val fromTxt = rec.fromState?.label ?: "NEW"
                        Text(
                            text = "• ${rec.symbol} ${rec.direction.name}: $fromTxt -> ${rec.toState.label} (${rec.timestampPkt}) — ${rec.reason}",
                            fontSize = 9.sp,
                            color = colors.textSecondary,
                            lineHeight = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (rankedItems.isEmpty()) {
                Text(
                    text = "Tap 'Scan Markets' to run strict 1D/4H/1H/15M MTF confirmation, 7-gate Golden Zone (0.500–0.618) checks, Type A/B setup validation, Net >=1:4.0/1:5.0 filtering, and cross-market ranking.",
                    fontSize = 11.sp,
                    color = colors.textMuted,
                    lineHeight = 15.sp
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rankedItems.forEach { ranked ->
                        StrictMarketEvalCard(
                            ranked = ranked,
                            managedSignal = managedBySymbol[ranked.evaluation.symbol.uppercase(Locale.US)],
                            onRunBacktest = { setup ->
                                onRunSetupBacktest(ranked.evaluation.symbol, setup.setupType, setup.direction)
                            },
                            onInspect = { onInspectAsset(ranked.evaluation.symbol, ranked.evaluation.displaySymbol) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectionalBacktestComparisonCard(report: DirectionalSetupBacktestReport) {
    val colors = NexusTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .border(1.dp, colors.gold.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(10.dp)
            .testTag("directional_backtest_card"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "BACKTEST COMPARISON (LONG vs SHORT • 70% IS / 30% OOS)",
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            color = colors.gold
        )
        Text(
            text = report.comparisonSummary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary
        )
        DirectionalSideRow(label = "LONG", bt = report.longResult, accent = colors.bullishGreen)
        DirectionalSideRow(label = "SHORT", bt = report.shortResult, accent = colors.bearishRed)
        Text(
            text = report.disclaimer,
            fontSize = 9.sp,
            color = colors.textMuted,
            lineHeight = 13.sp
        )
    }
}

@Composable
private fun DirectionalSideRow(
    label: String,
    bt: HistoricalBacktestResult,
    accent: androidx.compose.ui.graphics.Color
) {
    val colors = NexusTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceVariant)
            .padding(7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "$label (n=${bt.sampleSize}) • Win ${String.format(Locale.US, "%.1f%%", bt.empiricalWinRateConservativePercent)}",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Text(
                text = "Exp ${String.format(Locale.US, "%+.2fR (%+.2f%%)", bt.expectancyR, bt.expectancyPercent)} | MaxDD ${String.format(Locale.US, "%.1f%%", bt.maxDrawdownPercent)}",
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary
            )
        }
        bt.inOutSampleSplit?.let { split ->
            Text(
                text = "70% In-Sample (n=${split.inSampleCount}): ${String.format(Locale.US, "%.1f%%", split.inSampleWinRatePercent)} (${String.format(Locale.US, "%+.2fR", split.inSampleExpectancyR)}) | 30% Out-of-Sample (n=${split.outOfSampleCount}): ${String.format(Locale.US, "%.1f%%", split.outOfSampleWinRatePercent)} (${String.format(Locale.US, "%+.2fR", split.outOfSampleExpectancyR)})",
                fontSize = 9.sp,
                color = colors.textSecondary
            )
        }
    }
}

@Composable
private fun StrictMarketEvalCard(
    ranked: RankedMultiMarketOpportunity,
    managedSignal: ManagedSignalEntry?,
    onRunBacktest: (SetupCandidateEvaluation) -> Unit,
    onInspect: () -> Unit
) {
    val colors = NexusTheme.colors
    val eval = ranked.evaluation
    val statusColor = when {
        ranked.isPreferredFiveToOne -> colors.bullishGreen
        eval.status == SetupValidationStatus.CONFIRMED -> colors.bullishGreen
        eval.status == SetupValidationStatus.WATCHING -> colors.gold
        eval.status == SetupValidationStatus.DATA_UNAVAILABLE -> colors.bearishRed
        else -> colors.textMuted
    }
    val statusIcon = when (eval.status) {
        SetupValidationStatus.CONFIRMED -> Icons.Default.CheckCircle
        SetupValidationStatus.WATCHING -> Icons.Default.HourglassTop
        SetupValidationStatus.DATA_UNAVAILABLE -> Icons.Default.CloudOff
        SetupValidationStatus.INVALIDATED -> Icons.Default.HourglassTop
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(10.dp)
            .testTag("strict_eval_card_${eval.symbol.lowercase()}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "#${ranked.rank} • ${eval.displaySymbol} • ${eval.assetName}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
                Text(
                    text = "Source: ${eval.venueName} (${ranked.dataStatus.label}) • ${ranked.sessionNote}",
                    fontSize = 10.sp,
                    color = colors.textSecondary
                )
            }

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(statusColor.copy(alpha = 0.16f))
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                ) {
                    Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (ranked.isPreferredFiveToOne) "TOP >=1:5" else eval.status.label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
                managedSignal?.let { ms ->
                    Text(
                        text = "State: ${ms.state.label}",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.neutralCyan
                    )
                }
            }
        }

        if (eval.status == SetupValidationStatus.DATA_UNAVAILABLE) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = eval.unavailableReason ?: "DATA UNAVAILABLE — No confirmed signal generated.",
                fontSize = 11.sp,
                color = colors.warningOrange
            )
            return@Column
        }

        Spacer(modifier = Modifier.height(6.dp))
        eval.mtfHierarchy?.let { mtf ->
            Text(
                text = mtf.summary,
                fontSize = 10.sp,
                color = colors.gold,
                fontWeight = FontWeight.SemiBold
            )
        }

        val setups = listOfNotNull(eval.liquiditySweepReversal, eval.trendContinuation)
        if (setups.isEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "WATCHING: No Type A Liquidity Sweep or Type B Trend Continuation trigger confirmed yet.",
                fontSize = 11.sp,
                color = colors.textMuted
            )
        } else {
            setups.forEach { setup ->
                Spacer(modifier = Modifier.height(8.dp))
                SetupModelRow(
                    setup = setup,
                    onBacktestClick = { onRunBacktest(setup) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Price: ${fmtPrice(eval.currentPrice)} | ${eval.timestampPkt}",
                fontSize = 10.sp,
                color = colors.textMuted
            )
            OutlinedButton(
                onClick = onInspect,
                border = BorderStroke(1.dp, colors.border),
                shape = RoundedCornerShape(6.dp)
            ) {
                Icon(Icons.Default.Analytics, contentDescription = "Open Chart & SMC", tint = colors.gold, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Inspect SMC", fontSize = 10.sp, color = colors.textPrimary)
            }
        }
    }
}

@Composable
private fun SetupModelRow(
    setup: SetupCandidateEvaluation,
    onBacktestClick: () -> Unit
) {
    val colors = NexusTheme.colors
    val dirColor = if (setup.direction == FuturesDirection.LONG) colors.bullishGreen else colors.bearishRed
    val stColor = when (setup.status) {
        SetupValidationStatus.CONFIRMED -> colors.bullishGreen
        SetupValidationStatus.WATCHING -> colors.gold
        else -> colors.textMuted
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceVariant)
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${setup.setupType.label} (${setup.direction.name})",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = dirColor
            )
            Text(
                text = "${setup.status.label} • ${setup.score}/100",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = stColor
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Entry: ${setup.entryZoneRange.ifBlank { fmtPrice(setup.entryPrice) }} | SL: ${fmtPrice(setup.stopLoss)} | TP1: ${fmtPrice(setup.adjustedTarget1)} | Final TP: ${fmtPrice(setup.adjustedTarget2)}",
            fontSize = 10.sp,
            color = colors.textPrimary
        )
        Text(
            text = "Gross R:R 1:${String.format(Locale.US, "%.2f", setup.grossRiskReward)} | Net R:R 1:${String.format(Locale.US, "%.2f", setup.estimatedNetRiskReward)} (Fee+Slip: ${String.format(Locale.US, "%.2f%%", setup.estimatedFeeSlippagePercent)})",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (setup.isPreferredFiveToOne) colors.bullishGreen else colors.gold
        )
        Text(
            text = "${setup.entryExecutionType} | 15M Close (PKT): ${setup.confirmationCandleClosePkt}",
            fontSize = 9.sp,
            color = colors.textSecondary
        )
        Text(
            text = "Fib Ext Ref — 1.272: ${setup.target1272Ref?.let(::fmtPrice) ?: "N/A"} • 1.618: ${setup.target1618Ref?.let(::fmtPrice) ?: "N/A"}",
            fontSize = 10.sp,
            color = colors.textSecondary
        )
        Text(
            text = setup.goldenZoneNote,
            fontSize = 10.sp,
            color = colors.gold
        )
        if (setup.supportingConfirmations.isNotEmpty()) {
            Text(
                text = "Confirmations: ${setup.supportingConfirmations.take(2).joinToString(" • ")}",
                fontSize = 9.sp,
                color = colors.textSecondary
            )
        }
        if (setup.rulesFailed.isNotEmpty()) {
            Text(
                text = "Pending/Blocked: ${setup.rulesFailed.first()}",
                fontSize = 10.sp,
                color = colors.warningOrange
            )
        }
        HorizontalDivider(color = colors.border.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = setup.invalidationRule,
                fontSize = 9.sp,
                color = colors.textMuted,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "Backtest L/S",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = colors.gold,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onBacktestClick() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

private fun fmtPrice(v: Double): String = when {
    abs(v) >= 100.0 -> String.format(Locale.US, "$%.2f", v)
    abs(v) >= 1.0 -> String.format(Locale.US, "$%.3f", v)
    else -> String.format(Locale.US, "$%.4f", v)
}
