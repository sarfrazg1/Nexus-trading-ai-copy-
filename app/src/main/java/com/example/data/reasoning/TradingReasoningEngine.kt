package com.example.data.reasoning

import com.example.data.indicators.OverallAnalysisResult
import com.example.data.model.AssetCategory
import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.model.MarketAsset
import com.example.data.model.MarketNews
import com.example.data.providers.UnifiedHybridMarketState
import com.example.data.remote.BinanceFuturesApiClient
import com.example.data.scanner.CryptoPatternDetector
import com.example.data.scanner.EntryTimerStatus
import com.example.data.scanner.FuturesScanSignal
import com.example.data.signal.AiTradingSignal
import com.example.data.smc.FvgStatus
import com.example.data.smc.PriceZoneType
import com.example.data.smc.SmcAnalysisResult
import com.example.util.TimeZoneUtils
import java.util.Locale

/**
 * NEXUS Trading Reasoning Engine.
 * Sits strictly between validated Market Intelligence and Nexus AI Assistant:
 *   MARKET INTELLIGENCE -> TRADING REASONING ENGINE -> NEXUS AI ASSISTANT
 *
 * Evaluates verified market data, completed candles, technical/SMC structure, Entry Timer status,
 * Exit Planner levels, derivatives positioning, and fact-vs-interpretation news separation to build
 * balanced scenarios without fabricating data or guaranteeing trade outcomes.
 */
data class TradingReasoningReport(
    val symbol: String,
    val instrumentType: String,
    val dataQualityNote: String,
    val biasLabel: String,
    val detectedSetupPattern: String,
    val entryTimerSummary: String,
    val exitPlanSummary: String,
    val confirmedFactsSummary: String,
    val interpretiveContextSummary: String,
    val primaryScenario: String,
    val counterScenario: String,
    val promptSummary: String
)

object TradingReasoningEngine {

    fun evaluate(
        asset: MarketAsset?,
        candles: List<Candle>,
        timeframe: CandleTimeframe,
        technicalAnalysis: OverallAnalysisResult?,
        smcAnalysis: SmcAnalysisResult?,
        signal: AiTradingSignal?,
        futuresSignal: FuturesScanSignal?,
        newsList: List<MarketNews>,
        derivatives: BinanceFuturesApiClient.DerivativesSnapshot?,
        hybridState: UnifiedHybridMarketState? = null
    ): TradingReasoningReport {
        val completedCandles = TimeZoneUtils.filterCompletedCandles(candles)
        val symbol = asset?.symbol ?: futuresSignal?.symbol ?: signal?.symbol ?: "BTCUSDT"
        val baseAsset = asset?.id ?: futuresSignal?.baseAsset ?: symbol.removeSuffix("USDT").removeSuffix("/USDT")
        val currentPrice = asset?.currentPrice ?: futuresSignal?.currentPrice ?: completedCandles.lastOrNull()?.close ?: 0.0

        val instrumentType = when {
            asset?.category == AssetCategory.COMMODITIES -> "Commodities / Macro Quote"
            (asset?.dataSourceName ?: "").contains("spot", ignoreCase = true) -> "Spot Market"
            else -> "USDT-M Perpetual Futures"
        }

        val dataQualityNote = buildString {
            val status = asset?.dataStatus?.label ?: if (currentPrice > 0.0) "LIVE" else "UNAVAILABLE"
            append("Status: $status | Completed ${timeframe.label} candles analyzed: ${completedCandles.size}")
            if (candles.size > completedCandles.size) {
                append(" (${candles.size - completedCandles.size} unclosed candle excluded)")
            }
            val updatedAt = asset?.lastUpdated?.let { TimeZoneUtils.formatKarachiTimestamp(it) }
                ?: TimeZoneUtils.formatKarachiTimestamp(System.currentTimeMillis())
            append(" | Time (${TimeZoneUtils.ASIA_KARACHI_ID}): $updatedAt")
        }

        val rsi = technicalAnalysis?.momentum?.rsi14
        val ema20 = technicalAnalysis?.trend?.emaSet?.ema20
        val ema50 = technicalAnalysis?.trend?.emaSet?.ema50
        val ema200 = technicalAnalysis?.trend?.emaSet?.ema200
        val atr = technicalAnalysis?.volatility?.atr?.value

        val support = smcAnalysis?.priceZones
            ?.firstOrNull { it.type == PriceZoneType.SUPPORT || it.type == PriceZoneType.DEMAND }
            ?.centralPrice ?: ema50
        val resistance = smcAnalysis?.priceZones
            ?.firstOrNull { it.type == PriceZoneType.RESISTANCE || it.type == PriceZoneType.SUPPLY }
            ?.centralPrice ?: ema200

        val trendBullish = ema20 != null && ema50 != null && ema20 > ema50
        val trendBearish = ema20 != null && ema50 != null && ema20 < ema50
        val biasLabel = when {
            futuresSignal != null -> "${futuresSignal.direction.name} Bias (Score ${futuresSignal.qualityScore}/100)"
            trendBullish && (rsi ?: 50.0) >= 48.0 -> "Bullish Structure Bias (Monitor Confirmation)"
            trendBearish && (rsi ?: 50.0) <= 52.0 -> "Bearish Structure Bias (Monitor Confirmation)"
            else -> "Neutral / Range-Bound (Await Clean Trigger)"
        }

        val detectedPattern = futuresSignal?.detectedPattern?.label ?: run {
            if (completedCandles.size >= 25 && technicalAnalysis != null && smcAnalysis != null) {
                CryptoPatternDetector.detect(completedCandles, technicalAnalysis, smcAnalysis)?.pattern?.label
                    ?: "No distinct chart pattern detected"
            } else {
                "Insufficient completed candles for pattern scan"
            }
        }

        val entryTimerSummary = futuresSignal?.entryTimer?.let { timer ->
            val tsNote = timer.triggerTimestampPkt?.let { " at $it" } ?: ""
            val priceNote = timer.triggerPrice?.let { " (Close $${fmt(it)})" } ?: ""
            val wickNote = if (timer.wickRejectionCount > 0) " | Wick-only probes rejected: ${timer.wickRejectionCount}" else ""
            "Entry Timer [${timer.status.label}]$priceNote$tsNote$wickNote — ${timer.statusExplanation} (Manual confirmation only; never auto-executes)"
        } ?: if (signal != null) {
            "Signal Engine Decision: ${signal.decision.label} (Confidence ${signal.confidence.totalScore}/100) — Requires manual completed-candle confirmation."
        } else {
            "Entry Timer [WAITING/UNSCANNED] — Run Futures Auto-Scanner on completed 15M candles to monitor bar-by-bar candle close & RVOL confirmation."
        }

        val exitPlanSummary = futuresSignal?.exitPlan?.let { plan ->
            val tiers = plan.takeProfitTiers.joinToString(" / ") { "${it.label}: $${fmt(it.price)} (${it.allocationPercent}%)" }
            "Pre-Entry Exit Plan | SL: $${fmt(plan.stopLoss)} (${plan.invalidationBasis}) | ATR Stop Ref: $${fmt(plan.atrBasedStopLevel)} | Partials: $tiers | R:R 1:${String.format(Locale.US, "%.2f", plan.riskRewardRatio)} | Expiry: ${plan.expiryDeadlinePkt} | Caveat: ${plan.stopOrderDisclaimer}"
        } ?: futuresSignal?.let { fs ->
            "Proposed Levels | SL: ${fs.stopLossFormatted} | TP1: ${fs.tp1Formatted}, TP2: ${fs.tp2Formatted}, TP3: ${fs.tp3Formatted} | R:R 1:${String.format(Locale.US, "%.2f", fs.riskRewardRatio)} | Caveat: Stop orders are not guaranteed to fill at exact price under slippage or gaps."
        } ?: run {
            val slHint = support?.let { "$${fmt(it)}" } ?: "swing invalidation"
            val tpHint = resistance?.let { "$${fmt(it)}" } ?: "next supply/liquidity zone"
            val atrHint = atr?.let { String.format(Locale.US, " (ATR %.4f)", it) } ?: ""
            "Pre-Entry Plan Guidance: Use S/R invalidation ($slHint) vs ATR$atrHint before entry, target $tpHint with partial profit tiers, and note stop orders are never guaranteed to fill at exact trigger price."
        }

        val confirmedFactsSummary = buildString {
            appendLine("• Instrument: $symbol ($instrumentType) | Price: ${if (currentPrice > 0.0) "$${fmt(currentPrice)}" else "Unavailable"}")
            appendLine("• $dataQualityNote")
            rsi?.let { appendLine("• RSI(14): ${String.format(Locale.US, "%.2f", it)} | ATR: ${atr?.let { a -> String.format(Locale.US, "%.4f", a) } ?: "N/A"}") }
            smcAnalysis?.let { smc ->
                val unfilled = smc.fairValueGaps.count { it.status == FvgStatus.UNFILLED }
                appendLine("• SMC Structure: ${smc.marketStructure.trendState.label} | Latest Break: ${smc.marketStructure.latestBreak?.type?.label ?: "None"} | Unfilled FVGs: $unfilled")
            }
            derivatives?.let { d ->
                val fStr = d.fundingRatePercent?.let { String.format(Locale.US, "%+.4f%%", it) } ?: "N/A"
                val oiStr = d.openInterestContracts?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A"
                appendLine("• Derivatives (${d.source}): Funding $fStr | OI $oiStr | L/S Ratio ${d.longShortAccountRatio ?: "N/A"}")
            }
            val matchedNews = newsList.filter { item ->
                item.affectedAssets.any { it.equals(baseAsset, ignoreCase = true) || it.equals("MACRO", ignoreCase = true) }
            }.take(3)
            if (matchedNews.isNotEmpty()) {
                appendLine("• Verified News Dispatches (Reported Headlines):")
                matchedNews.forEach { n ->
                    appendLine("  - [${n.eventStatus.label} • ${n.timestamp}] ${n.title} (${n.source})")
                }
            }
        }.trim()

        val interpretiveContextSummary = buildString {
            appendLine("• Technical Pattern Inference: $detectedPattern ($biasLabel) — potential setup only, not a guaranteed outcome.")
            val matchedNews = newsList.filter { item ->
                item.affectedAssets.any { it.equals(baseAsset, ignoreCase = true) || it.equals("MACRO", ignoreCase = true) }
            }.take(2)
            if (matchedNews.isNotEmpty()) {
                matchedNews.forEach { n ->
                    val interp = n.aiInterpretation.ifBlank { "${n.impact.label}/${n.impactLevel.label} probabilistic catalyst context" }
                    appendLine("  - Interpretation [${n.affectedAssets.joinToString(",")}]: $interp")
                }
            } else {
                appendLine("• News Interpretation: No asset-specific catalyst headline matched for $baseAsset.")
            }
        }.trim()

        val supText = support?.let { "$${fmt(it)}" } ?: "key support"
        val resText = resistance?.let { "$${fmt(it)}" } ?: "key resistance"

        val primaryScenario = when {
            futuresSignal != null && futuresSignal.entryTimer?.status == EntryTimerStatus.TRIGGERED ->
                "${futuresSignal.direction.name} Triggered Scenario: Setup confirmed on completed candle close (${futuresSignal.detectedPattern.label}). Monitor ${futuresSignal.exitPlan?.takeProfitTiers?.firstOrNull()?.price?.let { "$${fmt(it)}" } ?: futuresSignal.tp1Formatted} partial take-profit; invalidation at ${futuresSignal.stopLossFormatted}."
            trendBullish ->
                "Bullish Continuation Scenario: Holding above $supText with completed 15M/1H close above $resText opens upside toward liquidity above $resText. Invalidation: decisive close below $supText."
            trendBearish ->
                "Bearish Continuation Scenario: Rejection near $resText and completed candle close below $supText targets lower liquidity pockets. Invalidation: reclaim above $resText."
            else ->
                "Range / Confirmation Scenario: Await completed candle breakout above $resText or defended liquidity sweep near $supText with volume confirmation."
        }

        val counterScenario = "Counter / Risk Scenario: If liquidity sweeps opposite to bias or funding/OI overheats, expect false breakout whipsaws; limit capital risk to 1-2% per trade and remember stop orders can experience slippage."

        val promptSummary = buildString {
            appendLine("=== TRADING REASONING ENGINE EVALUATION (USE AS EVIDENCE) ===")
            appendLine("Bias: $biasLabel | Detected Pattern: $detectedPattern")
            appendLine("Entry Timer: $entryTimerSummary")
            appendLine("Exit Planner: $exitPlanSummary")
            appendLine("Primary Scenario: $primaryScenario")
            appendLine("Counter Risk Scenario: $counterScenario")
            appendLine("CONFIRMED FACTS:")
            appendLine(confirmedFactsSummary)
            appendLine("PROBABILISTIC / AI INTERPRETATION (NEVER PRESENT AS PROVEN CAUSE):")
            appendLine(interpretiveContextSummary)
        }.trim()

        return TradingReasoningReport(
            symbol = symbol,
            instrumentType = instrumentType,
            dataQualityNote = dataQualityNote,
            biasLabel = biasLabel,
            detectedSetupPattern = detectedPattern,
            entryTimerSummary = entryTimerSummary,
            exitPlanSummary = exitPlanSummary,
            confirmedFactsSummary = confirmedFactsSummary,
            interpretiveContextSummary = interpretiveContextSummary,
            primaryScenario = primaryScenario,
            counterScenario = counterScenario,
            promptSummary = promptSummary
        )
    }

    private fun fmt(value: Double): String = when {
        value >= 100.0 -> String.format(Locale.US, "%.2f", value)
        value >= 1.0 -> String.format(Locale.US, "%.3f", value)
        else -> String.format(Locale.US, "%.4f", value)
    }
}
