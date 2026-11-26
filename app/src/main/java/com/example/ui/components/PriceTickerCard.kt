package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MarketAsset
import com.example.data.model.MarketDataStatus
import com.example.ui.theme.NexusTheme
import java.util.Locale

@Composable
fun PriceTickerCard(
    asset: MarketAsset,
    onCardClick: () -> Unit,
    onConnectFeedClick: () -> Unit,
    onSetAlertClick: () -> Unit,
    onToggleFavorite: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = NexusTheme.colors
    val borderColor = when (asset.dataStatus) {
        MarketDataStatus.LIVE -> colors.bullishGreen.copy(alpha = 0.4f)
        MarketDataStatus.DELAYED -> colors.gold.copy(alpha = 0.4f)
        MarketDataStatus.STALE -> colors.warningOrange.copy(alpha = 0.4f)
        MarketDataStatus.UNAVAILABLE -> colors.border
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onCardClick)
            .testTag("price_card_${asset.id.lowercase()}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        border = BorderStroke(1.dp, Brush.verticalGradient(listOf(borderColor, colors.border)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Row: Asset icon/badge + Name + Favorite + Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Symbol circle
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceVariant)
                            .border(1.dp, colors.border, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = asset.id.take(3),
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = when (asset.id) {
                                    "BTC", "BNB", "XAU" -> colors.gold
                                    "ETH", "SOL", "NATGAS" -> colors.neutralCyan
                                    "XAG" -> colors.textSecondary
                                    "WTI", "BRENT" -> colors.warningOrange
                                    else -> colors.textPrimary
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = asset.name,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = asset.symbol,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = colors.textMuted,
                                    fontSize = 11.sp
                                )
                            )
                        }
                        Text(
                            text = asset.category.label,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = colors.textSecondary,
                                fontSize = 11.sp
                            )
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onToggleFavorite != null) {
                        IconButton(
                            onClick = onToggleFavorite,
                            modifier = Modifier.size(32.dp).testTag("fav_btn_${asset.id.lowercase()}")
                        ) {
                            Icon(
                                imageVector = if (asset.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = "Toggle Favorite",
                                tint = if (asset.isFavorite) colors.gold else colors.textMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    // Data Status Badge (LIVE / DELAYED / STALE / UNAVAILABLE)
                    MarketStatusBadge(status = asset.dataStatus)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Body: If Price Available (LIVE, DELAYED, STALE) -> Show Price & 24h stats
            if (asset.currentPrice != null && asset.dataStatus != MarketDataStatus.UNAVAILABLE) {
                val isPositive = (asset.change24hPercent ?: 0.0) >= 0.0

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = String.format(Locale.US, "$%,.2f", asset.currentPrice),
                            style = MaterialTheme.typography.headlineMedium.copy(
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            )
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isPositive) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                                contentDescription = null,
                                tint = if (isPositive) colors.bullishGreen else colors.bearishRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%s%.2f (%.2f%%)",
                                    if (isPositive) "+" else "",
                                    asset.change24h ?: 0.0,
                                    asset.change24hPercent ?: 0.0
                                ),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = if (isPositive) colors.bullishGreen else colors.bearishRed,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    }

                    // Mini Sparkline
                    if (asset.sparklinePoints.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .width(110.dp)
                                .height(46.dp)
                        ) {
                            MiniSparkline(
                                points = asset.sparklinePoints,
                                isPositive = isPositive,
                                modifier = Modifier.fillMaxWidth().height(46.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Stats row: 24h High, Low, Volume
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceVariant.copy(alpha = 0.7f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("24h High", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 10.sp))
                        Text(
                            text = if (asset.high24h != null) String.format(Locale.US, "$%,.2f", asset.high24h) else "N/A",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textPrimary, fontWeight = FontWeight.Medium)
                        )
                    }
                    Column {
                        Text("24h Low", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 10.sp))
                        Text(
                            text = if (asset.low24h != null) String.format(Locale.US, "$%,.2f", asset.low24h) else "N/A",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textPrimary, fontWeight = FontWeight.Medium)
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("24h Volume", style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 10.sp))
                        Text(
                            text = asset.volume24h ?: "N/A",
                            style = MaterialTheme.typography.bodySmall.copy(color = colors.textPrimary, fontWeight = FontWeight.Medium)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Metadata Footer: Source Name + Last Update Time
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Source: ${asset.dataSourceName}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = colors.gold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    Text(
                        text = "Updated: ${asset.lastUpdatedFormatted}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = colors.textMuted,
                            fontSize = 11.sp
                        )
                    )
                }

            } else {
                // UNAVAILABLE STATE (No fake live prices!)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surfaceVariant.copy(alpha = 0.7f))
                        .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "---.--",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    color = colors.textMuted,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = "Source: ${asset.dataSourceName}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = colors.textSecondary,
                                    fontSize = 11.sp
                                )
                            )
                        }

                        // Set Alert shortcut button
                        OutlinedButton(
                            onClick = onSetAlertClick,
                            modifier = Modifier.testTag("set_alert_btn_${asset.id.lowercase()}"),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.gold)
                        ) {
                            Icon(imageVector = Icons.Default.AddAlert, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Alert", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Setup explanation note if provider key is needed
                    if (asset.setupInstructions != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.card)
                                .padding(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = colors.warningOrange, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = asset.setupInstructions,
                                style = MaterialTheme.typography.bodySmall.copy(color = colors.textSecondary, fontSize = 11.sp, lineHeight = 15.sp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Functional Connect / Configure API button
                    Button(
                        onClick = onConnectFeedClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("connect_feed_btn_${asset.id.lowercase()}"),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (asset.category == com.example.data.model.AssetCategory.CRYPTO) colors.gold else colors.hover,
                            contentColor = if (asset.category == com.example.data.model.AssetCategory.CRYPTO) colors.onGold else colors.textPrimary
                        )
                    ) {
                        Icon(
                            imageVector = if (asset.category == com.example.data.model.AssetCategory.CRYPTO) Icons.Default.Sync else Icons.Default.Cable,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when (asset.category) {
                                com.example.data.model.AssetCategory.CRYPTO -> "Reconnect WebSocket Stream"
                                com.example.data.model.AssetCategory.COMMODITIES -> "Configure Commodities Feed"
                                else -> "View Market Feed Status"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MarketStatusBadge(status: MarketDataStatus) {
    val colors = NexusTheme.colors
    val (bgColor, textColor, label, _) = when (status) {
        MarketDataStatus.LIVE -> Tuple4(colors.bullishGreenBg, colors.bullishGreen, "LIVE", Icons.Default.Sync)
        MarketDataStatus.DELAYED -> Tuple4(colors.gold.copy(alpha = 0.14f), colors.gold, "DELAYED", Icons.Default.AccessTime)
        MarketDataStatus.STALE -> Tuple4(colors.warningOrange.copy(alpha = 0.14f), colors.warningOrange, "STALE", Icons.Default.History)
        MarketDataStatus.UNAVAILABLE -> Tuple4(colors.surfaceVariant, colors.textMuted, "UNAVAILABLE", Icons.Default.CloudOff)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .border(1.dp, textColor.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(textColor)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
        )
    }
}

private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
