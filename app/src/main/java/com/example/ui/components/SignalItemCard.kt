package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Signal
import com.example.data.model.SignalStatus
import com.example.data.model.SignalType
import com.example.ui.theme.NexusTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignalItemCard(
    signal: Signal,
    onSetAlertClick: () -> Unit,
    onCopyLevelsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = NexusTheme.colors
    val isBullish = signal.type == SignalType.BUY || signal.type == SignalType.STRONG_BUY
    val isBearish = signal.type == SignalType.SELL || signal.type == SignalType.STRONG_SELL

    val accentColor = when {
        isBullish -> colors.bullishGreen
        isBearish -> colors.bearishRed
        else -> colors.gold
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("signal_card_${signal.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.verticalGradient(
                listOf(accentColor.copy(alpha = 0.35f), colors.border)
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Asset Symbol + Signal Badge + Confidence Score
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = signal.assetSymbol,
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = signal.timeframe,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = colors.textSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }

                // Confidence pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.surfaceVariant)
                        .border(1.dp, colors.gold.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = colors.gold,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${signal.confidenceScore}% AI Conviction",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colors.gold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Signal Action Pill & Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isBullish) colors.bullishGreenBg else if (isBearish) colors.bearishRedBg else colors.surfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = if (isBullish) Icons.Default.TrendingUp else if (isBearish) Icons.Default.TrendingDown else Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = signal.type.label.uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = accentColor,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    )
                }

                Text(
                    text = signal.status.label,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = when (signal.status) {
                            SignalStatus.ACTIVE -> colors.bullishGreen
                            SignalStatus.TRIGGERED -> colors.gold
                            SignalStatus.EXPIRED -> colors.textMuted
                        },
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Pricing Grid: Entry, Target, Stop Loss, Risk/Reward
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceVariant.copy(alpha = 0.7f))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("ENTRY ZONE", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 9.sp))
                    Text(signal.entryPrice, style = MaterialTheme.typography.bodySmall.copy(color = colors.textPrimary, fontWeight = FontWeight.SemiBold))
                }
                Column {
                    Text("TAKE PROFIT", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 9.sp))
                    Text(signal.targetPrice, style = MaterialTheme.typography.bodySmall.copy(color = colors.bullishGreen, fontWeight = FontWeight.SemiBold))
                }
                Column {
                    Text("STOP LOSS", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 9.sp))
                    Text(signal.stopLoss, style = MaterialTheme.typography.bodySmall.copy(color = colors.bearishRed, fontWeight = FontWeight.SemiBold))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("R : R", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 9.sp))
                    Text(signal.riskReward, style = MaterialTheme.typography.bodySmall.copy(color = colors.gold, fontWeight = FontWeight.Bold))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Algorithmic Thesis
            Text(
                text = signal.thesis,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Technical Indicators flow
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                signal.indicators.forEach { indicator ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = indicator,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = colors.textMuted,
                                fontSize = 10.sp
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons: Set Alert & Copy Levels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onSetAlertClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("signal_alert_btn_${signal.id}"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.gold)
                ) {
                    Icon(imageVector = Icons.Default.AddAlert, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Set Alert", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onCopyLevelsClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("signal_copy_btn_${signal.id}"),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textSecondary)
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Levels", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
