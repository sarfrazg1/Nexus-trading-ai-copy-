package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.scanner.EntryTimerStatus
import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.FuturesScanSignal
import com.example.data.scanner.FuturesSignalLifecycle
import com.example.data.scanner.FuturesWatchlistPair
import com.example.data.scanner.ScannerEngineState
import com.example.data.scanner.ScannerStatistics
import com.example.data.scanner.ScannerStatus
import com.example.ui.theme.NexusTheme
import java.util.Locale

@Composable
fun FuturesScannerDashboardHeader(
    state: ScannerEngineState,
    onStartScanner: () -> Unit,
    onStopScanner: () -> Unit,
    onIntervalSelected: (Int) -> Unit,
    onTogglePaperTrading: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = NexusTheme.colors
    val isScanning = state.status == ScannerStatus.SCANNING

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, if (isScanning) colors.bullishGreen.copy(alpha = 0.5f) else colors.border, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = colors.card)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when (state.status) {
                                    ScannerStatus.SCANNING -> colors.bullishGreen
                                    ScannerStatus.IDLE -> colors.gold
                                    ScannerStatus.PAUSED -> colors.textMuted
                                    ScannerStatus.ERROR -> colors.bearishRed
                                    ScannerStatus.DISCONNECTED -> colors.bearishRed
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "BINANCE USDT-M FUTURES SCANNER",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = colors.gold,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.1.sp
                            )
                        )
                        Text(
                            text = when (state.status) {
                                ScannerStatus.SCANNING -> "Live Auto-Scanning Active"
                                ScannerStatus.IDLE -> "Scanner Idle / Standby"
                                ScannerStatus.PAUSED -> "Scanner Paused"
                                ScannerStatus.ERROR -> "Connection Error"
                                ScannerStatus.DISCONNECTED -> "Offline / Disconnected"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }

                Button(
                    onClick = { if (isScanning) onStopScanner() else onStartScanner() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isScanning) colors.bearishRed else colors.bullishGreen
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("toggle_futures_scanner_btn")
                ) {
                    Icon(
                        imageVector = if (isScanning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isScanning) "Stop" else "Start",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isScanning && state.currentlyScanningSymbol != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surface)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = colors.gold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Scanning ${state.currentlyScanningSymbol} across 4H, 1H, 15M, 5M...",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = colors.gold,
                            fontSize = 11.sp
                        )
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatChip(label = "Active Signals", value = "${state.activeSignals.size}", color = colors.bullishGreen)
                StatChip(label = "Setups Awaiting", value = "${state.watchlistSetups.size}", color = colors.gold)
                StatChip(
                    label = "Win Rate",
                    value = if (state.statistics.winRatePercent > 0) String.format(Locale.US, "%.1f%%", state.statistics.winRatePercent) else "N/A",
                    color = colors.textPrimary
                )
                StatChip(label = "Coins Scanned", value = "${state.selectedWatchlistCount}", color = colors.textSecondary)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Interval:",
                        style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 11.sp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    listOf(15, 30, 60).forEach { sec ->
                        val isSelected = state.scanIntervalSeconds == sec
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) colors.gold else colors.surfaceVariant)
                                .clickable { onIntervalSelected(sec) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${sec}s",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (isSelected) colors.onGold else colors.textSecondary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 10.sp
                                )
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Paper Trading",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (state.paperTradingEnabled) colors.bullishGreen else colors.textMuted,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(
                        checked = state.paperTradingEnabled,
                        onCheckedChange = onTogglePaperTrading,
                        modifier = Modifier.size(24.dp),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.bullishGreen,
                            checkedTrackColor = colors.bullishGreenBg,
                            uncheckedThumbColor = colors.textMuted,
                            uncheckedTrackColor = colors.surfaceVariant
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color) {
    val colors = NexusTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleMedium.copy(color = color, fontWeight = FontWeight.Bold))
        Text(text = label, style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 9.sp))
    }
}

@Composable
fun BatteryOptimizationNoticeCard() {
    val colors = NexusTheme.colors
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, colors.gold.copy(alpha = 0.25f), RoundedCornerShape(10.dp)),
        colors = CardDefaults.cardColors(containerColor = colors.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Default.BatteryAlert, contentDescription = null, tint = colors.gold, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Android Background & Battery Optimization",
                        style = MaterialTheme.typography.labelSmall.copy(color = colors.gold, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(16.dp)
                )
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• Foreground Service: NEXUS Scanner runs an Android Foreground Service with an ongoing notification to maintain scanning while the app is in the background.\n" +
                            "• Doze Mode & Restrictions: Android battery optimization/Doze limits background network access when closed; add NEXUS to 'Unrestricted' battery usage in Android App Settings.\n" +
                            "• Offline Safety: If the device loses Wi-Fi/cellular connection, the scanner pauses gracefully and resumes automatically.",
                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FuturesSignalCard(
    signal: FuturesScanSignal,
    onExplainClick: () -> Unit,
    onCopyLevels: () -> Unit,
    onSimulatePaperTrade: () -> Unit,
    onOpenWorkflow: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = NexusTheme.colors
    val isLong = signal.direction == FuturesDirection.LONG
    val dirColor = if (isLong) colors.bullishGreen else colors.bearishRed
    val dirBg = if (isLong) colors.bullishGreenBg else colors.bearishRedBg
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(
                1.dp,
                if (signal.lifecycle == FuturesSignalLifecycle.ENTRY_CONFIRMED) dirColor.copy(alpha = 0.6f) else colors.border,
                RoundedCornerShape(14.dp)
            ),
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
                        text = signal.symbol,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(dirBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = signal.direction.name,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = dirColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(signal.lifecycle.badgeColorHex).copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = signal.lifecycle.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color(signal.lifecycle.badgeColorHex),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.gold.copy(alpha = 0.14f))
                        .border(1.dp, colors.gold.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                        .testTag("pattern_badge_${signal.symbol}")
                ) {
                    Text(
                        text = signal.detectedPattern.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colors.gold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    )
                }

                val timer = signal.entryTimer
                val timerStatus = timer?.status ?: EntryTimerStatus.WAITING
                val timerColor = Color(timerStatus.badgeColorHex)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(timerColor.copy(alpha = 0.16f))
                        .border(1.dp, timerColor.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                        .testTag("entry_timer_chip_${signal.symbol}")
                ) {
                    Text(
                        text = "TIMER: ${timerStatus.label}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = timerColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    )
                }
            }

            signal.entryTimer?.let { timer ->
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surface)
                        .padding(8.dp)
                        .testTag("entry_timer_box_${signal.symbol}")
                ) {
                    val headline = if (timer.status == EntryTimerStatus.TRIGGERED && timer.triggerTimestampPkt != null) {
                        val tp = timer.triggerPrice?.let { String.format(Locale.US, "$%.2f", it) } ?: signal.currentPriceFormatted
                        "Triggered on completed close @ $tp • ${timer.triggerTimestampPkt}"
                    } else {
                        timer.statusExplanation
                    }
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = colors.textPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 14.sp
                        )
                    )
                    if (timer.wickRejectionCount > 0) {
                        Text(
                            text = "Wick-only probes ignored: ${timer.wickRejectionCount} (completed candle close required)",
                            style = MaterialTheme.typography.labelSmall.copy(color = colors.gold, fontSize = 9.sp)
                        )
                    }
                    Text(
                        text = "${signal.setupDisclaimer} (PKT: ${signal.formattedTimestamp})",
                        style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 9.sp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("ENTRY ZONE", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 9.sp))
                    Text(signal.entryZoneFormatted, style = MaterialTheme.typography.bodyMedium.copy(color = colors.textPrimary, fontWeight = FontWeight.Bold))
                    Text("Live: ${signal.currentPriceFormatted}", style = MaterialTheme.typography.labelSmall.copy(color = colors.gold, fontSize = 10.sp))
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("STOP LOSS", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 9.sp))
                    Text(signal.stopLossFormatted, style = MaterialTheme.typography.bodyMedium.copy(color = colors.bearishRed, fontWeight = FontWeight.Bold))
                    Text("-${String.format(Locale.US, "%.2f", signal.slDistancePercent)}%", style = MaterialTheme.typography.labelSmall.copy(color = colors.bearishRed, fontSize = 10.sp))
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("TARGET (TP2)", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 9.sp))
                    Text(signal.tp2Formatted, style = MaterialTheme.typography.bodyMedium.copy(color = colors.bullishGreen, fontWeight = FontWeight.Bold))
                    Text("+${String.format(Locale.US, "%.2f", signal.tp2DistancePercent)}%", style = MaterialTheme.typography.labelSmall.copy(color = colors.bullishGreen, fontSize = 10.sp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "R:R 1:${String.format(Locale.US, "%.2f", signal.riskRewardRatio)}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colors.gold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Lev: ${signal.suggestedLeverage}x (Cap)",
                        style = MaterialTheme.typography.labelSmall.copy(color = colors.textSecondary, fontSize = 10.sp)
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Model Conf: ${signal.qualityScore}%",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (signal.qualityScore >= 92) colors.bullishGreen else if (signal.qualityScore >= 75) colors.gold else colors.textSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    )
                    Text(
                        text = "Model Conf != Hist. Win Rate",
                        style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 8.sp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { signal.qualityScore / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = if (signal.qualityScore >= 75) colors.bullishGreen else colors.gold,
                trackColor = colors.surfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                signal.timeframeConfirmations.forEach { tf ->
                    val isBull = tf.trend.contains("Bullish", ignoreCase = true)
                    val isBear = tf.trend.contains("Bearish", ignoreCase = true)
                    val chipColor = if (isBull) colors.bullishGreen else if (isBear) colors.bearishRed else colors.textSecondary

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.surface)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${tf.timeframe.label}: ${tf.structure}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = chipColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            AnimatedVisibility(visible = isExpanded) {
                Column {
                    Text(
                        text = "Confluence Reasoning:",
                        style = MaterialTheme.typography.labelSmall.copy(color = colors.gold, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    )
                    signal.reasonsForEntry.forEach { reason ->
                        Text(
                            text = "• $reason",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary, fontSize = 11.sp, lineHeight = 15.sp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.bearishRedBg.copy(alpha = 0.5f))
                            .padding(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = colors.bearishRed, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Invalidation Rule: ${signal.invalidationCondition}",
                                style = MaterialTheme.typography.bodySmall.copy(color = colors.bearishRed, fontSize = 10.sp, lineHeight = 14.sp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("TP1 (40%): ${signal.tp1Formatted}", style = MaterialTheme.typography.labelSmall.copy(color = colors.bullishGreen, fontSize = 10.sp))
                        Text("TP2 (35%): ${signal.tp2Formatted}", style = MaterialTheme.typography.labelSmall.copy(color = colors.bullishGreen, fontSize = 10.sp))
                        Text("TP3 (25%): ${signal.tp3Formatted}", style = MaterialTheme.typography.labelSmall.copy(color = colors.bullishGreen, fontSize = 10.sp))
                    }

                    signal.exitPlan?.let { plan ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surface)
                                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                                .padding(10.dp)
                                .testTag("exit_planner_card_${signal.symbol}")
                        ) {
                            Text(
                                text = "PRE-ENTRY EXIT PLANNER",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = colors.gold,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Stop Basis: ${plan.invalidationBasis} (ATR Ref: ${String.format(Locale.US, "$%.2f", plan.atrBasedStopLevel)})",
                                style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary, fontSize = 10.sp)
                            )
                            Text(
                                text = "Setup Expiry (PKT): ${plan.expiryDeadlinePkt}",
                                style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary, fontSize = 10.sp)
                            )
                            plan.calculationAssumptions.take(3).forEach { assumption ->
                                Text(
                                    text = "• $assumption",
                                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textMuted, fontSize = 9.sp, lineHeight = 13.sp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "⚠️ ${plan.stopOrderDisclaimer}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = colors.bearishRed,
                                    fontSize = 9.sp,
                                    lineHeight = 13.sp
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { isExpanded = !isExpanded }
                        .padding(horizontal = 6.dp, vertical = 10.dp)
                        .testTag("view_details_${signal.symbol}")
                ) {
                    Text(
                        text = if (isExpanded) "Hide Details ▲" else "View Details ▼",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colors.gold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCopyLevels, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Levels", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.bullishGreen.copy(alpha = 0.16f))
                            .border(1.dp, colors.bullishGreen.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                            .clickable { onOpenWorkflow() }
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                            .testTag("workflow_btn_${signal.symbol}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, contentDescription = "Open Trade Workflow for ${signal.symbol}", tint = colors.bullishGreen, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Workflow", style = MaterialTheme.typography.labelSmall.copy(color = colors.bullishGreen, fontWeight = FontWeight.Bold, fontSize = 11.sp))
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.gold.copy(alpha = 0.15f))
                            .clickable { onExplainClick() }
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                            .testTag("ask_ai_signal_${signal.symbol}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "Ask AI about ${signal.symbol}", tint = colors.gold, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Ask AI", style = MaterialTheme.typography.labelSmall.copy(color = colors.gold, fontWeight = FontWeight.Bold, fontSize = 11.sp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FuturesWatchlistManagerSection(
    watchlist: List<FuturesWatchlistPair>,
    onToggleScanning: (String) -> Unit,
    onAddCustomPair: (String) -> Unit,
    onRemovePair: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = NexusTheme.colors
    var newPairInput by remember { mutableStateOf("") }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, colors.border, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = colors.card)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "SELECTED SCANNER WATCHLIST",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = colors.gold,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp
                )
            )
            Text(
                text = "USDT-M Futures Coins to Scan",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = "The scanner will ONLY fetch and analyze candles for coins enabled below to optimize speed and battery.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = colors.textMuted,
                    fontSize = 11.sp
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newPairInput,
                    onValueChange = { newPairInput = it },
                    placeholder = { Text("e.g. SUI, NEAR, PEPE", color = colors.textMuted, fontSize = 12.sp) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.gold,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (newPairInput.isNotBlank()) {
                            onAddCustomPair(newPairInput)
                            newPairInput = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.gold),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = colors.onGold, modifier = Modifier.size(16.dp))
                    Text("Add", color = colors.onGold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                watchlist.forEach { pair ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.surface)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = pair.isSelectedForScanning,
                                onCheckedChange = { onToggleScanning(pair.symbol) },
                                modifier = Modifier.size(20.dp),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = colors.bullishGreen,
                                    checkedTrackColor = colors.bullishGreenBg,
                                    uncheckedThumbColor = colors.textMuted,
                                    uncheckedTrackColor = colors.surfaceVariant
                                )
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = pair.symbol,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = if (pair.isSelectedForScanning) colors.textPrimary else colors.textMuted,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = pair.displayName,
                                    style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 10.sp)
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (pair.currentPrice > 0.0) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "$${String.format(Locale.US, "%.2f", pair.currentPrice)}",
                                        style = MaterialTheme.typography.bodySmall.copy(color = colors.textPrimary, fontWeight = FontWeight.Medium)
                                    )
                                    pair.fundingRatePercent?.let { fr ->
                                        Text(
                                            text = "FR: ${String.format(Locale.US, "%.4f%%", fr)}",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = if (fr >= 0) colors.bullishGreen else colors.bearishRed,
                                                fontSize = 9.sp
                                            )
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(onClick = { onRemovePair(pair.symbol) }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = colors.textMuted, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FuturesSignalHistoryTab(
    history: List<FuturesScanSignal>,
    statistics: ScannerStatistics,
    modifier: Modifier = Modifier
) {
    val colors = NexusTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
            colors = CardDefaults.cardColors(containerColor = colors.card)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "HISTORICAL SCANNER PERFORMANCE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = colors.gold,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    )
                )
                Text(
                    text = "Verified Outcome Statistics",
                    style = MaterialTheme.typography.titleMedium.copy(color = colors.textPrimary, fontWeight = FontWeight.Bold)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatChip(label = "Total Scanned", value = "${statistics.totalSignalsScanned}", color = colors.textPrimary)
                    StatChip(label = "TP1 Hits", value = "${statistics.tp1HitsCount}", color = colors.bullishGreen)
                    StatChip(label = "TP2 Hits", value = "${statistics.tp2HitsCount}", color = colors.bullishGreen)
                    StatChip(label = "TP3 Hits", value = "${statistics.tp3HitsCount}", color = colors.bullishGreen)
                    StatChip(label = "Stop Loss", value = "${statistics.stopLossHitsCount}", color = colors.bearishRed)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surface)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Win Rate (Closed Trades)", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 10.sp))
                        Text(
                            text = if (statistics.winRatePercent > 0) String.format(Locale.US, "%.1f%%", statistics.winRatePercent) else "0.0%",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = if (statistics.winRatePercent >= 50) colors.bullishGreen else colors.gold,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text("Paper Trading Net PnL", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 10.sp))
                        Text(
                            text = String.format(Locale.US, "%+.2f%%", statistics.paperTradingNetPnlPercent),
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = if (statistics.paperTradingNetPnlPercent >= 0) colors.bullishGreen else colors.bearishRed,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (history.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No closed signals yet. Active signals will appear here as targets or stop losses are reached.",
                    style = MaterialTheme.typography.bodySmall.copy(color = colors.textMuted, textAlign = TextAlign.Center)
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                history.forEach { sig ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.card)
                            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(sig.symbol, style = MaterialTheme.typography.bodyMedium.copy(color = colors.textPrimary, fontWeight = FontWeight.Bold))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(sig.direction.name, style = MaterialTheme.typography.labelSmall.copy(color = if (sig.direction == FuturesDirection.LONG) colors.bullishGreen else colors.bearishRed, fontWeight = FontWeight.Bold))
                            }
                            Text(sig.formattedTimestamp, style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 10.sp))
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = sig.lifecycle.label,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = Color(sig.lifecycle.badgeColorHex),
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            sig.outcomeSummary?.let { outcome ->
                                Text(outcome, style = MaterialTheme.typography.labelSmall.copy(color = colors.textSecondary, fontSize = 10.sp))
                            }
                        }
                    }
                }
            }
        }
    }
}
