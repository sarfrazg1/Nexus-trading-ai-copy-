package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Candle
import com.example.data.smc.FairValueGap
import com.example.data.smc.FvgStatus
import com.example.data.smc.FvgType
import com.example.data.smc.LiquidityPool
import com.example.data.smc.LiquidityPoolType
import com.example.data.smc.MarketStructureBreakType
import com.example.data.smc.MarketTrendState
import com.example.data.smc.MtfConfluenceStance
import com.example.data.smc.OrderBlock
import com.example.data.smc.OrderBlockStatus
import com.example.data.smc.OrderBlockType
import com.example.data.smc.PriceZoneType
import com.example.data.smc.SmcAnalysisResult
import com.example.data.smc.SwingType
import com.example.ui.theme.NexusTheme
import com.example.ui.viewmodel.SmcCategoryTab
import kotlin.math.max
import kotlin.math.min

/**
 * SMC and Fibonacci UI Components for NEXUS TRADING AI.
 */

@Composable
fun SmcHeaderWithInfo(
    title: String,
    termKey: String,
    onInfoClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = NexusTheme.colors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textSecondary,
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(colors.gold.copy(alpha = 0.12f))
                .clickable { onInfoClick(termKey) }
                .testTag("info_btn_$termKey"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = "Roman Urdu info for $title",
                tint = colors.gold,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

// ==========================================
// 1. SMC CANDLESTICK CHART WITH OVERLAYS
// ==========================================

@Composable
fun SmcCandlestickChart(
    candles: List<Candle>,
    smc: SmcAnalysisResult?,
    modifier: Modifier = Modifier
) {
    val colors = NexusTheme.colors
    val bullColor = colors.bullishGreen
    val bearColor = colors.bearishRed
    val goldColor = colors.gold
    val warningColor = colors.warningOrange
    val cyanColor = colors.neutralCyan

    if (candles.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(colors.surface, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("Awaiting candle data...", color = colors.textMuted, fontSize = 12.sp)
        }
        return
    }

    val displayCandles = candles.takeLast(35)
    val minPrice = displayCandles.minOfOrNull { it.low } ?: 0.0
    val maxPrice = displayCandles.maxOfOrNull { it.high } ?: 1.0
    val priceRange = if (maxPrice > minPrice) maxPrice - minPrice else 1.0

    val orderBlocks = smc?.orderBlocks?.take(4) ?: emptyList()
    val fvgs = smc?.fairValueGaps?.filter { it.status != FvgStatus.FULLY_FILLED }?.take(3) ?: emptyList()
    val fibLevels = smc?.fibonacci?.retracementLevels ?: emptyList()
    val bsl = smc?.liquidity?.bslNearestPrice
    val ssl = smc?.liquidity?.sslNearestPrice

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(bullColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "SMC CHART OVERLAYS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
            }
            Text(
                text = "Last ${displayCandles.size} Candles • OB / FVG / Fib",
                fontSize = 10.sp,
                color = colors.textMuted
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height

                fun priceToY(price: Double): Float {
                    val normalized = (price - minPrice) / priceRange
                    return (canvasHeight - (normalized * canvasHeight)).toFloat().coerceIn(0f, canvasHeight)
                }

                // 1. Draw Order Block shaded zones
                orderBlocks.forEach { ob ->
                    val yTop = priceToY(ob.topPrice)
                    val yBottom = priceToY(ob.bottomPrice)
                    val zoneHeight = max(2f, yBottom - yTop)

                    val zoneColor = if (ob.type == OrderBlockType.BULLISH) {
                        bullColor.copy(alpha = if (ob.status == OrderBlockStatus.UNMITIGATED) 0.16f else 0.08f)
                    } else {
                        bearColor.copy(alpha = if (ob.status == OrderBlockStatus.UNMITIGATED) 0.16f else 0.08f)
                    }

                    drawRect(
                        color = zoneColor,
                        topLeft = Offset(0f, yTop),
                        size = Size(canvasWidth, zoneHeight)
                    )

                    // Draw boundary line
                    drawLine(
                        color = if (ob.type == OrderBlockType.BULLISH) bullColor.copy(alpha = 0.5f) else bearColor.copy(alpha = 0.5f),
                        start = Offset(0f, yTop),
                        end = Offset(canvasWidth, yTop),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                    )
                }

                // 2. Draw Fair Value Gap (FVG) shaded zones
                fvgs.forEach { fvg ->
                    val yTop = priceToY(fvg.topPrice)
                    val yBottom = priceToY(fvg.bottomPrice)
                    val gapHeight = max(2f, yBottom - yTop)

                    drawRect(
                        color = if (fvg.type == FvgType.BULLISH) goldColor.copy(alpha = 0.12f) else warningColor.copy(alpha = 0.12f),
                        topLeft = Offset(0f, yTop),
                        size = Size(canvasWidth, gapHeight)
                    )
                }

                // 3. Draw Fibonacci 0.618 Golden Pocket & 0.500 lines
                fibLevels.forEach { fib ->
                    val y = priceToY(fib.price)
                    if (fib.isGoldenPocket) {
                        drawLine(
                            color = goldColor,
                            start = Offset(0f, y),
                            end = Offset(canvasWidth, y),
                            strokeWidth = 1.5f
                        )
                    } else if (fib.isEquilibrium) {
                        drawLine(
                            color = cyanColor,
                            start = Offset(0f, y),
                            end = Offset(canvasWidth, y),
                            strokeWidth = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
                        )
                    }
                }

                // 4. Draw BSL & SSL Liquidity lines
                if (bsl != null && bsl in minPrice..maxPrice) {
                    val yBsl = priceToY(bsl)
                    drawLine(
                        color = cyanColor,
                        start = Offset(0f, yBsl),
                        end = Offset(canvasWidth, yBsl),
                        strokeWidth = 1.2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                    )
                }
                if (ssl != null && ssl in minPrice..maxPrice) {
                    val ySsl = priceToY(ssl)
                    drawLine(
                        color = bearColor,
                        start = Offset(0f, ySsl),
                        end = Offset(canvasWidth, ySsl),
                        strokeWidth = 1.2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                    )
                }

                // 5. Draw Candlesticks (Wicks and Bodies)
                val candleCount = displayCandles.size
                val slotWidth = canvasWidth / candleCount
                val candleWidth = max(2f, slotWidth * 0.65f)

                displayCandles.forEachIndexed { i, c ->
                    val xCenter = (i * slotWidth) + (slotWidth / 2f)
                    val isGreen = c.close >= c.open
                    val candleColor = if (isGreen) bullColor else bearColor

                    val yHigh = priceToY(c.high)
                    val yLow = priceToY(c.low)
                    val yOpen = priceToY(c.open)
                    val yClose = priceToY(c.close)

                    // Wick
                    drawLine(
                        color = candleColor,
                        start = Offset(xCenter, yHigh),
                        end = Offset(xCenter, yLow),
                        strokeWidth = 1.5f
                    )

                    // Body
                    val bodyTop = min(yOpen, yClose)
                    val bodyBottom = max(yOpen, yClose)
                    val bodyHeight = max(1.5f, bodyBottom - bodyTop)

                    drawRect(
                        color = candleColor,
                        topLeft = Offset(xCenter - (candleWidth / 2f), bodyTop),
                        size = Size(candleWidth, bodyHeight)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Legend row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendItem(color = bullColor, label = "Bullish OB")
            LegendItem(color = bearColor, label = "Bearish OB")
            LegendItem(color = goldColor, label = "Golden Fib (0.618)")
            LegendItem(color = warningColor, label = "FVG Imbalance")
            LegendItem(color = cyanColor, label = "BSL (Liquidity)")
            LegendItem(color = bearColor, label = "SSL (Liquidity)")
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    val colors = NexusTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, fontSize = 9.sp, color = colors.textMuted)
    }
}

// ==========================================
// 2. MARKET STRUCTURE SECTION
// ==========================================

@Composable
fun MarketStructureSection(
    smc: SmcAnalysisResult,
    onInfoClick: (String) -> Unit
) {
    val colors = NexusTheme.colors
    val structure = smc.marketStructure
    val trendColor = when (structure.trendState) {
        MarketTrendState.BULLISH_UPTREND -> colors.bullishGreen
        MarketTrendState.BEARISH_DOWNTREND -> colors.bearishRed
        MarketTrendState.TRANSITION_REVERSAL -> colors.gold
        MarketTrendState.NEUTRAL_RANGING -> colors.textSecondary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("smc_market_structure_card"),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmcHeaderWithInfo(
                    title = "MARKET STRUCTURE (BOS & CHOCH)",
                    termKey = "BOS",
                    onInfoClick = onInfoClick
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(trendColor.copy(alpha = 0.15f))
                        .border(1.dp, trendColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = structure.trendState.label,
                        color = trendColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Swings count row (HH, HL, LH, LL)
            val hhCount = structure.swingPoints.count { it.type == SwingType.HIGHER_HIGH }
            val hlCount = structure.swingPoints.count { it.type == SwingType.HIGHER_LOW }
            val lhCount = structure.swingPoints.count { it.type == SwingType.LOWER_HIGH }
            val llCount = structure.swingPoints.count { it.type == SwingType.LOWER_LOW }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SwingBadge(
                    label = "HH",
                    count = hhCount,
                    color = colors.bullishGreen,
                    modifier = Modifier.weight(1f),
                    onInfoClick = { onInfoClick("HH_HL") }
                )
                SwingBadge(
                    label = "HL",
                    count = hlCount,
                    color = colors.bullishGreen.copy(alpha = 0.8f),
                    modifier = Modifier.weight(1f),
                    onInfoClick = { onInfoClick("HH_HL") }
                )
                SwingBadge(
                    label = "LH",
                    count = lhCount,
                    color = colors.bearishRed.copy(alpha = 0.8f),
                    modifier = Modifier.weight(1f),
                    onInfoClick = { onInfoClick("LH_LL") }
                )
                SwingBadge(
                    label = "LL",
                    count = llCount,
                    color = colors.bearishRed,
                    modifier = Modifier.weight(1f),
                    onInfoClick = { onInfoClick("LH_LL") }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Recent BOS / CHoCH Breaks
            if (structure.structureBreaks.isEmpty()) {
                Text(
                    text = "No recent structure breaks detected within current window.",
                    fontSize = 11.sp,
                    color = colors.textMuted
                )
            } else {
                Text(
                    text = "RECENT STRUCTURE BREAK EVENTS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textMuted,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                structure.structureBreaks.takeLast(3).reversed().forEach { brk ->
                    val isBullish = brk.type.isBullish
                    val breakColor = if (isBullish) colors.bullishGreen else colors.bearishRed

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(colors.surface, RoundedCornerShape(8.dp))
                            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isBullish) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                contentDescription = null,
                                tint = breakColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = brk.type.label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = breakColor
                                )
                                Text(
                                    text = brk.description,
                                    fontSize = 10.sp,
                                    color = colors.textSecondary
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(colors.surfaceVariant)
                                .clickable {
                                    onInfoClick(if (brk.type.label.contains("CHoCH")) "CHOCH" else "BOS")
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Info",
                                tint = colors.gold,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }
            }

            if (smc.breakoutRetests.isNotEmpty() || smc.priceActionPatterns.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "BREAKOUT-RETEST & CONTEXTUAL PRICE ACTION",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textMuted
                )
                Spacer(modifier = Modifier.height(4.dp))
                smc.breakoutRetests.take(2).forEach { ev ->
                    val evColor = if (ev.type.isBullish) colors.bullishGreen else colors.bearishRed
                    Text(
                        text = "• ${ev.type.label}: ${ev.description}",
                        fontSize = 10.sp,
                        color = evColor
                    )
                }
                smc.priceActionPatterns.take(2).forEach { pa ->
                    val paColor = if (pa.type.isBullish) colors.bullishGreen else colors.bearishRed
                    Text(
                        text = "• ${pa.contextDescription}",
                        fontSize = 10.sp,
                        color = paColor
                    )
                }
            }
        }
    }
}

@Composable
private fun SwingBadge(
    label: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier,
    onInfoClick: () -> Unit
) {
    val colors = NexusTheme.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .clickable { onInfoClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Black, color = color)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = "$count", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
        }
    }
}

// ==========================================
// 3. ORDER BLOCKS SECTION
// ==========================================

@Composable
fun OrderBlocksSection(
    orderBlocks: List<OrderBlock>,
    onInfoClick: (String) -> Unit
) {
    val colors = NexusTheme.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("smc_order_blocks_card"),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmcHeaderWithInfo(
                    title = "ORDER BLOCKS (OB)",
                    termKey = "BULLISH_OB",
                    onInfoClick = onInfoClick
                )

                Text(
                    text = "${orderBlocks.size} Detected",
                    fontSize = 11.sp,
                    color = colors.gold,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (orderBlocks.isEmpty()) {
                Text(
                    text = "No clear institutional Order Blocks detected in current candle range.",
                    fontSize = 11.sp,
                    color = colors.textMuted
                )
            } else {
                orderBlocks.take(6).forEach { ob ->
                    val isBull = ob.type == OrderBlockType.BULLISH
                    val color = if (isBull) colors.bullishGreen else colors.bearishRed

                    val statusBadgeColor = when (ob.status) {
                        OrderBlockStatus.UNMITIGATED -> colors.gold
                        OrderBlockStatus.MITIGATED -> colors.neutralCyan
                        OrderBlockStatus.INVALIDATED -> colors.textMuted
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(colors.surface, RoundedCornerShape(10.dp))
                            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(color, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = ob.type.label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = color
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .background(statusBadgeColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = ob.status.label,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = statusBadgeColor
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "Zone: $%.2f - $%.2f • 50%% Mean: $%.2f".format(
                                    ob.bottomPrice,
                                    ob.topPrice,
                                    ob.meanThresholdPrice
                                ),
                                fontSize = 11.sp,
                                color = colors.textPrimary
                            )
                        }

                        IconButton(
                            onClick = {
                                onInfoClick(if (isBull) "BULLISH_OB" else "BEARISH_OB")
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Info",
                                tint = colors.gold,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 4. FAIR VALUE GAPS (FVG) SECTION
// ==========================================

@Composable
fun FairValueGapsSection(
    fvgs: List<FairValueGap>,
    onInfoClick: (String) -> Unit
) {
    val colors = NexusTheme.colors
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("smc_fvg_card"),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmcHeaderWithInfo(
                    title = "FAIR VALUE GAPS (FVG / IMBALANCE)",
                    termKey = "FVG",
                    onInfoClick = onInfoClick
                )

                val unfilledCount = fvgs.count { it.status == FvgStatus.UNFILLED }
                Text(
                    text = "$unfilledCount Fresh Gaps",
                    fontSize = 11.sp,
                    color = colors.gold,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (fvgs.isEmpty()) {
                Text(
                    text = "No Fair Value Gaps found in recent candles.",
                    fontSize = 11.sp,
                    color = colors.textMuted
                )
            } else {
                fvgs.take(6).forEach { fvg ->
                    val isBull = fvg.type == FvgType.BULLISH
                    val color = if (isBull) colors.bullishGreen else colors.bearishRed

                    val statusColor = when (fvg.status) {
                        FvgStatus.UNFILLED -> colors.gold
                        FvgStatus.PARTIALLY_FILLED -> colors.neutralCyan
                        FvgStatus.FULLY_FILLED -> colors.textMuted
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(colors.surface, RoundedCornerShape(10.dp))
                            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(color, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = fvg.type.label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = color
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .background(statusColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = fvg.status.label,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Gap: $%.2f - $%.2f (Mid: $%.2f, Size: %.2f%%)".format(
                                fvg.bottomPrice,
                                fvg.topPrice,
                                fvg.midPrice,
                                fvg.gapSizePercent
                            ),
                            fontSize = 11.sp,
                            color = colors.textPrimary
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        LinearProgressIndicator(
                            progress = { (fvg.fillPercentage / 100.0).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = statusColor,
                            trackColor = colors.surfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// 5. LIQUIDITY & STOP-HUNT ANALYSIS SECTION
// ==========================================

@Composable
fun LiquidityAnalysisSection(
    smc: SmcAnalysisResult,
    onInfoClick: (String) -> Unit
) {
    val colors = NexusTheme.colors
    val liquidity = smc.liquidity

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("smc_liquidity_card"),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmcHeaderWithInfo(
                    title = "LIQUIDITY & STOP-HUNT ANALYSIS",
                    termKey = "BSL",
                    onInfoClick = onInfoClick
                )

                if (liquidity.potentialStopHuntActive) {
                    Box(
                        modifier = Modifier
                            .background(colors.warningOrange.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                            .border(1.dp, colors.warningOrange, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "POTENTIAL STOP-HUNT",
                            color = colors.warningOrange,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // BSL & SSL Target Levels Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LiquidityTargetBox(
                    label = "BUY-SIDE LIQUIDITY (BSL)",
                    price = liquidity.bslNearestPrice,
                    color = colors.neutralCyan,
                    modifier = Modifier.weight(1f),
                    onInfoClick = { onInfoClick("BSL") }
                )
                LiquidityTargetBox(
                    label = "SELL-SIDE LIQUIDITY (SSL)",
                    price = liquidity.sslNearestPrice,
                    color = colors.bearishRed,
                    modifier = Modifier.weight(1f),
                    onInfoClick = { onInfoClick("SSL") }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Stop-hunt explicit advisory banner (Not Guaranteed requirement)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.warningOrange.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .border(1.dp, colors.warningOrange.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Advisory",
                        tint = colors.warningOrange,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Stop-Hunt Status: Potential Only (Not Guaranteed)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.warningOrange
                        )
                        Text(
                            text = "Wick expansions capture liquidity pools. Always confirm with displacement candle closes before executing.",
                            fontSize = 10.sp,
                            color = colors.textSecondary,
                            lineHeight = 14.sp
                        )
                    }
                }
            }

            // Recent Sweeps list
            if (liquidity.recentSweeps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "RECENT LIQUIDITY SWEEPS",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textMuted
                )
                Spacer(modifier = Modifier.height(4.dp))
                liquidity.recentSweeps.take(3).forEach { sweep ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .background(colors.surface, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = sweep.note,
                            fontSize = 10.sp,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onInfoClick("LIQUIDITY_SWEEP") },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = colors.gold,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiquidityTargetBox(
    label: String,
    price: Double?,
    color: Color,
    modifier: Modifier = Modifier,
    onInfoClick: () -> Unit
) {
    val colors = NexusTheme.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .clickable { onInfoClick() }
            .padding(10.dp)
    ) {
        Column {
            Text(text = label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = price?.let { "$%.2f".format(it) } ?: "Swept / None",
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                color = colors.textPrimary
            )
        }
    }
}

// ==========================================
// 6. FIBONACCI & SUPPORT/RESISTANCE SECTION
// ==========================================

@Composable
fun FibonacciAndSupportResistanceSection(
    smc: SmcAnalysisResult,
    onInfoClick: (String) -> Unit
) {
    val colors = NexusTheme.colors
    val fib = smc.fibonacci
    val zones = smc.priceZones

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("smc_fibonacci_card"),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmcHeaderWithInfo(
                    title = "FIBONACCI & S/R ZONES",
                    termKey = "FIB_RETRACEMENT",
                    onInfoClick = onInfoClick
                )

                if (fib != null) {
                    Box(
                        modifier = Modifier
                            .background(colors.gold.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (fib.isUptrendSwing) "Uptrend Swing" else "Downtrend Swing",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.gold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (fib != null) {
                Text(
                    text = fib.currentFibZoneDescription,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (fib.validationReport?.isStrictlyValid == true) colors.bullishGreen else colors.gold
                )

                fib.validationReport?.let { rep ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "7-Gate Golden Zone (0.500–0.618): ${rep.passedGatesCount}/7 passed | Range $%.2f - $%.2f".format(
                            fib.goldenZone0500To0618Low,
                            fib.goldenZone0500To0618High
                        ),
                        fontSize = 10.sp,
                        color = colors.textSecondary
                    )
                    Text(
                        text = "Premium: $%.2f-$%.2f | Discount: $%.2f-$%.2f".format(
                            fib.premiumZoneLow, fib.premiumZoneHigh,
                            fib.discountZoneLow, fib.discountZoneHigh
                        ),
                        fontSize = 10.sp,
                        color = colors.textMuted
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Retracement Levels Row
                Text(
                    text = "RETRACEMENT LEVELS (0.236, 0.382, 0.500, 0.618, 0.786)",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textMuted
                )
                Spacer(modifier = Modifier.height(6.dp))

                fib.retracementLevels.forEach { lvl ->
                    val isGp = lvl.isGoldenPocket
                    val isEq = lvl.isEquilibrium
                    val rowColor = when {
                        isGp -> colors.gold
                        isEq -> colors.neutralCyan
                        else -> colors.textPrimary
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .background(if (isGp) colors.gold.copy(alpha = 0.08f) else colors.surface, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = lvl.label + (if (lvl.ratio == 0.618) " (Golden Ratio 0.618)" else if (isEq) " (0.500 Golden Zone Top)" else ""),
                                fontSize = 11.sp,
                                fontWeight = if (isGp) FontWeight.Black else FontWeight.Normal,
                                color = rowColor
                            )
                        }
                        Text(
                            text = "$%.2f".format(lvl.price),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = rowColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Extension Levels Row
                Text(
                    text = "EXTENSION TARGETS (1.272, 1.618, 2.618)",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textMuted
                )
                Spacer(modifier = Modifier.height(6.dp))

                fib.extensionLevels.forEach { ext ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .background(colors.surface, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = ext.label + (if (ext.ratio == 1.618) " (Golden Extension)" else ""),
                            fontSize = 11.sp,
                            color = if (ext.ratio == 1.618) colors.gold else colors.textSecondary,
                            fontWeight = if (ext.ratio == 1.618) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            text = "$%.2f".format(ext.price),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary
                        )
                    }
                }
            }

            // Volume Profile (POC / VAH / VAL) & PDH/PDL/PWH/PWL
            if (smc.volumeProfile != null || smc.previousDayHigh != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    smc.volumeProfile?.let { vp ->
                        Text(
                            text = "Volume Profile — POC: $%.2f | VAH: $%.2f | VAL: $%.2f".format(
                                vp.pocPrice, vp.vahPrice, vp.valPrice
                            ),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.gold
                        )
                    }
                    if (smc.previousDayHigh != null && smc.previousDayLow != null) {
                        Text(
                            text = "PDH: $%.2f | PDL: $%.2f".format(smc.previousDayHigh, smc.previousDayLow) +
                                (if (smc.previousWeekHigh != null && smc.previousWeekLow != null) {
                                    " | PWH: $%.2f | PWL: $%.2f".format(smc.previousWeekHigh, smc.previousWeekLow)
                                } else ""),
                            fontSize = 10.sp,
                            color = colors.textSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Support & Resistance + Supply & Demand
            SmcHeaderWithInfo(
                title = "SUPPLY & DEMAND / S&R ZONES",
                termKey = "SUPPLY_DEMAND",
                onInfoClick = onInfoClick
            )
            Spacer(modifier = Modifier.height(6.dp))

            zones.take(6).forEach { zone ->
                val zoneColor = when (zone.type) {
                    PriceZoneType.DEMAND, PriceZoneType.SUPPORT -> colors.bullishGreen
                    PriceZoneType.SUPPLY, PriceZoneType.RESISTANCE -> colors.bearishRed
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .background(colors.surface, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(zoneColor, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${zone.type.label} (${zone.strengthScore}/100)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = zoneColor
                            )
                        }
                        Text(
                            text = "$%.2f-$%.2f (%.1f%%)".format(zone.lowerPrice, zone.upperPrice, zone.distancePercent),
                            fontSize = 10.sp,
                            color = colors.textPrimary
                        )
                    }
                    Text(
                        text = zone.sourceSummary,
                        fontSize = 9.sp,
                        color = colors.textMuted
                    )
                }
            }
        }
    }
}

// ==========================================
// 7. MULTI-TIMEFRAME ANALYSIS SECTION
// ==========================================

@Composable
fun MultiTimeframeAnalysisSection(
    smc: SmcAnalysisResult,
    onInfoClick: (String) -> Unit
) {
    val colors = NexusTheme.colors
    val mtf = smc.multiTimeframe

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("smc_mtf_card"),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmcHeaderWithInfo(
                    title = "MULTI-TIMEFRAME ANALYSIS (MTF)",
                    termKey = "MTF_CONFLUENCE",
                    onInfoClick = onInfoClick
                )

                if (mtf != null) {
                    val stanceColor = when (mtf.confluenceStance) {
                        MtfConfluenceStance.STRONG_BULLISH_CONFLUENCE, MtfConfluenceStance.MODERATE_BULLISH -> colors.bullishGreen
                        MtfConfluenceStance.STRONG_BEARISH_CONFLUENCE, MtfConfluenceStance.MODERATE_BEARISH -> colors.bearishRed
                        MtfConfluenceStance.NEUTRAL_MIXED -> colors.gold
                    }
                    Box(
                        modifier = Modifier
                            .background(stanceColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                            .border(1.dp, stanceColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = mtf.confluenceStance.label,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = stanceColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (mtf != null) {
                Text(
                    text = mtf.agreementSummary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.gold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = mtf.disagreementDetails,
                    fontSize = 10.sp,
                    color = colors.textSecondary,
                    lineHeight = 14.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Timeframe breakdown cards
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    mtf.snapshots.forEach { snap ->
                        val isBull = snap.isBullishBias
                        val color = if (isBull) colors.bullishGreen else colors.bearishRed

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.surface, RoundedCornerShape(8.dp))
                                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .background(colors.gold.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = snap.timeframe.label,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Black,
                                        color = colors.gold
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = snap.trend.label,
                                    fontSize = 11.sp,
                                    color = color,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${snap.unmitigatedOBCount} OBs • ${snap.unfilledFvgCount} FVGs",
                                    fontSize = 10.sp,
                                    color = colors.textMuted
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "Computing multi-timeframe alignment across 1m, 5m, 15m, 30m, 1h, 4h, 1D and 1W...",
                    fontSize = 11.sp,
                    color = colors.textMuted
                )
            }
        }
    }
}
