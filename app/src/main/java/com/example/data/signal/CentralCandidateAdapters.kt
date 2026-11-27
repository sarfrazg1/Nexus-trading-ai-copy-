package com.example.data.signal

import com.example.data.model.CandleTimeframe
import com.example.data.model.MarketDataStatus
import com.example.data.model.ScannerItem
import com.example.data.providers.FuturesLiquiditySnapshot
import com.example.data.providers.FuturesTelemetryStatus
import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.FuturesScanSignal
import com.example.data.scanner.FuturesSignalLifecycle
import com.example.data.scanner.HighRewardOpportunity
import com.example.data.scanner.HighRewardScanResult
import com.example.data.scanner.OpportunityStatus
import com.example.data.scanner.RankedMultiMarketOpportunity
import com.example.data.scanner.SetupCandidateEvaluation
import com.example.data.scanner.SetupValidationStatus
import com.example.data.scanner.StrictMultiMarketEvaluation
import java.util.Locale

/**
 * Adapters connecting every existing candidate engine into CentralSignalAuthority
 * without rewriting their math or inflating missing data.
 */
object CentralCandidateAdapters {

    fun normalizeSymbol(raw: String): String {
        val clean = raw.uppercase(Locale.US).replace("/", "").trim()
        return clean
    }

    fun fromBinanceFuturesSignal(
        signal: FuturesScanSignal,
        dataStatus: MarketDataStatus = MarketDataStatus.LIVE,
        candleCount: Int = 50,
        liquidity: FuturesLiquiditySnapshot? = null
    ): CentralSignalCandidate {
        val isConfirmed = signal.lifecycle == FuturesSignalLifecycle.ENTRY_CONFIRMED ||
            signal.lifecycle == FuturesSignalLifecycle.ACTIVE
        val triggerTs = signal.entryTimer?.triggerTimestampMs ?: signal.timestamp
        val expiryMs = signal.exitPlan?.let {
            signal.timestamp + (it.setupExpiryBars.coerceAtLeast(1) * signal.primaryTimeframe.durationMillis)
        } ?: (signal.timestamp + 4 * 3_600_000L)
        val hasClosedTrigger = signal.entryTimer == null ||
            (signal.entryTimer.triggerTimestampMs ?: 0L) > 0L

        val fundAvailable = liquidity?.fundingRatePercent != null || signal.fundingRatePercent != null
        val fundStatus = when {
            liquidity != null && liquidity.status == FuturesTelemetryStatus.STALE -> "STALE"
            fundAvailable -> "LIVE"
            else -> "UNAVAILABLE"
        }
        val liqStatus = if (liquidity?.liquidationDataAvailable == true) "LIVE" else "UNAVAILABLE"

        val sigSetup = "${signal.detectedPattern.name}_${signal.primaryTimeframe.name}"
        return CentralSignalCandidate(
            provider = CandidateProviderSource.BINANCE_FUTURES_SCANNER,
            symbol = normalizeSymbol(signal.symbol),
            baseAsset = signal.baseAsset,
            timeframe = signal.primaryTimeframe,
            direction = signal.direction,
            isEntryConfirmed = isConfirmed,
            isWatchingOnly = signal.lifecycle == FuturesSignalLifecycle.WATCHLIST_SETUP,
            rawNativeScore = signal.qualityScore,
            entryPrice = signal.entryPrice,
            stopLoss = signal.stopLoss,
            takeProfit1 = signal.takeProfit1,
            takeProfit2 = signal.takeProfit2,
            takeProfit3 = signal.takeProfit3,
            grossRiskReward = signal.riskRewardRatio,
            netRiskReward = signal.riskRewardRatio,
            dataStatus = dataStatus,
            dataSourceName = signal.dataSource,
            currentPrice = signal.currentPrice,
            snapshotTimestampMs = signal.timestamp,
            confirmationCandleCloseMs = triggerTs,
            expiresAtMs = expiryMs,
            candleCount = candleCount,
            hasClosedCandleConfirmation = isConfirmed && hasClosedTrigger,
            setupTypeLabel = signal.detectedPattern.label,
            setupSignature = sigSetup,
            reasons = signal.reasonsForEntry,
            invalidationCondition = signal.invalidationCondition,
            fundingStatus = fundStatus,
            liquidationStatus = liqStatus,
            isFundingAvailable = fundAvailable,
            isLiquidationAvailable = liquidity?.liquidationDataAvailable == true,
            originalSignalId = signal.id
        )
    }

    fun fromHighRewardOpportunity(
        opportunity: HighRewardOpportunity,
        dataStatus: MarketDataStatus = MarketDataStatus.LIVE,
        dataSourceName: String = "Binance USDT-M Futures Public REST",
        candleCount: Int = 50
    ): CentralSignalCandidate {
        val isConfirmed = opportunity.isQualified && opportunity.status == OpportunityStatus.ACTIVE
        val hasFreshFunding = opportunity.fundingRateTimestampMs != null && opportunity.fundingRateTimestampMs > 0L
        return CentralSignalCandidate(
            provider = CandidateProviderSource.HIGH_REWARD_DETECTOR,
            symbol = normalizeSymbol(opportunity.symbol),
            baseAsset = opportunity.baseAsset,
            timeframe = CandleTimeframe.M15,
            direction = opportunity.direction,
            isEntryConfirmed = isConfirmed,
            isWatchingOnly = !isConfirmed,
            rawNativeScore = opportunity.modelConfidencePercent,
            entryPrice = opportunity.entryPrice,
            stopLoss = opportunity.stopLoss,
            takeProfit1 = opportunity.takeProfit,
            takeProfit2 = opportunity.takeProfit,
            takeProfit3 = opportunity.takeProfit,
            grossRiskReward = opportunity.grossRiskRewardRatio,
            netRiskReward = opportunity.netRiskRewardRatio,
            dataStatus = dataStatus,
            dataSourceName = dataSourceName,
            currentPrice = opportunity.currentPrice,
            snapshotTimestampMs = opportunity.detectedAtTimestampMs,
            confirmationCandleCloseMs = opportunity.entryTriggerTimestampMs,
            expiresAtMs = opportunity.expiresAtTimestampMs,
            candleCount = candleCount,
            hasClosedCandleConfirmation = opportunity.entryTriggerTimestampMs > 0L && isConfirmed,
            setupTypeLabel = opportunity.tier.label,
            setupSignature = opportunity.signatureKey,
            reasons = opportunity.qualificationReasons + opportunity.supportingIndicatorsAndSmc,
            invalidationCondition = opportunity.risksAndInvalidations.firstOrNull()
                ?: "Stop Loss breach cancels setup",
            fundingStatus = if (hasFreshFunding) "LIVE" else "UNAVAILABLE",
            liquidationStatus = "UNAVAILABLE",
            isFundingAvailable = hasFreshFunding,
            isLiquidationAvailable = false,
            originalSignalId = opportunity.id
        )
    }

    fun fromHighRewardScanResult(
        result: HighRewardScanResult,
        dataStatus: MarketDataStatus = MarketDataStatus.LIVE,
        dataSourceName: String = "Binance USDT-M Futures Public REST",
        candleCount: Int = 50,
        referenceTimeMs: Long = System.currentTimeMillis()
    ): List<CentralSignalCandidate> {
        if (result.qualifiedOpportunities.isNotEmpty()) {
            return result.qualifiedOpportunities.map {
                fromHighRewardOpportunity(it, dataStatus, dataSourceName, candleCount)
            }
        }
        val diag = result.bestDiagnostic
        return listOf(
            CentralSignalCandidate(
                provider = CandidateProviderSource.HIGH_REWARD_DETECTOR,
                symbol = normalizeSymbol(diag.symbol),
                timeframe = CandleTimeframe.M15,
                direction = diag.bestDirection,
                isEntryConfirmed = false,
                isWatchingOnly = true,
                rawNativeScore = null,
                grossRiskReward = diag.grossRiskRewardRatio,
                netRiskReward = diag.netRiskRewardRatio,
                dataStatus = dataStatus,
                dataSourceName = dataSourceName,
                snapshotTimestampMs = referenceTimeMs,
                candleCount = candleCount,
                hasClosedCandleConfirmation = false,
                setupTypeLabel = diag.tier.label,
                setupSignature = "DIAG_${diag.symbol}_${diag.tier.name}",
                reasons = listOf(diag.reason),
                fundingStatus = if (diag.isFundingAvailable) "LIVE" else "UNAVAILABLE",
                liquidationStatus = "UNAVAILABLE",
                isFundingAvailable = diag.isFundingAvailable,
                isLiquidationAvailable = false
            )
        )
    }

    fun fromStrictMultiMarketEvaluation(
        evaluation: StrictMultiMarketEvaluation,
        setupOverride: SetupCandidateEvaluation? = null,
        qualityReport: StrictSignalQualityReport? = null,
        dataStatus: MarketDataStatus = if (evaluation.status == SetupValidationStatus.DATA_UNAVAILABLE) {
            MarketDataStatus.UNAVAILABLE
        } else {
            MarketDataStatus.LIVE
        },
        snapshotTimestampMs: Long = System.currentTimeMillis()
    ): CentralSignalCandidate {
        val setup = setupOverride ?: evaluation.bestSetup
        val isConfirmed = evaluation.status == SetupValidationStatus.CONFIRMED &&
            setup != null &&
            setup.status == SetupValidationStatus.CONFIRMED
        val closeMs = setup?.confirmationCandleCloseTimeMs ?: 0L
        val effectiveScore = qualityReport?.totalScore ?: setup?.score

        return CentralSignalCandidate(
            provider = CandidateProviderSource.STRICT_MULTI_MARKET_SETUP,
            symbol = normalizeSymbol(evaluation.symbol),
            baseAsset = evaluation.displaySymbol,
            timeframe = CandleTimeframe.M15,
            direction = setup?.direction,
            isEntryConfirmed = isConfirmed,
            isWatchingOnly = !isConfirmed,
            rawNativeScore = setup?.score,
            strictQualityScore = qualityReport?.totalScore,
            entryPrice = setup?.entryPrice ?: evaluation.currentPrice,
            stopLoss = setup?.stopLoss ?: 0.0,
            takeProfit1 = setup?.adjustedTarget1 ?: 0.0,
            takeProfit2 = setup?.adjustedTarget2 ?: 0.0,
            takeProfit3 = setup?.adjustedTarget2 ?: 0.0,
            grossRiskReward = setup?.grossRiskReward ?: 0.0,
            netRiskReward = setup?.estimatedNetRiskReward ?: 0.0,
            dataStatus = dataStatus,
            dataSourceName = evaluation.venueName,
            currentPrice = evaluation.currentPrice,
            snapshotTimestampMs = snapshotTimestampMs,
            confirmationCandleCloseMs = closeMs,
            expiresAtMs = snapshotTimestampMs + 6 * 3_600_000L,
            candleCount = evaluation.mtfHierarchy?.fifteenMin?.candleCount ?: 0,
            hasClosedCandleConfirmation = isConfirmed && closeMs > 0L,
            setupTypeLabel = setup?.setupType?.label ?: evaluation.status.label,
            setupSignature = "${setup?.setupType?.name ?: "NONE"}_${evaluation.symbol}",
            reasons = setup?.supportingConfirmations ?: listOfNotNull(evaluation.unavailableReason),
            invalidationCondition = setup?.invalidationRule ?: "",
            strictQualityReport = qualityReport,
            allMandatoryFiltersPassed = qualityReport?.allMandatoryFiltersPassed,
            failedFilterReasons = qualityReport?.mandatoryFilters?.filter { !it.passed }?.map {
                "Filter #${it.filterNumber} (${it.name}): ${it.reason}"
            } ?: setup?.rulesFailed.orEmpty(),
            fundingStatus = "UNAVAILABLE",
            liquidationStatus = "UNAVAILABLE",
            isFundingAvailable = false,
            isLiquidationAvailable = false
        )
    }

    fun fromRankedOpportunity(
        ranked: RankedMultiMarketOpportunity,
        snapshotTimestampMs: Long = System.currentTimeMillis()
    ): CentralSignalCandidate {
        return fromStrictMultiMarketEvaluation(
            evaluation = ranked.evaluation,
            setupOverride = ranked.setup,
            qualityReport = ranked.qualityReport,
            dataStatus = ranked.dataStatus,
            snapshotTimestampMs = snapshotTimestampMs
        ).copy(provider = CandidateProviderSource.MULTI_MARKET_RANKER)
    }

    fun fromStrictQualityReport(
        report: StrictSignalQualityReport,
        dataStatus: MarketDataStatus = MarketDataStatus.LIVE,
        dataSourceName: String = "Strict Signal Quality Engine",
        candleCount: Int = 50,
        confirmationCandleCloseMs: Long = report.timestampMs,
        timeframe: CandleTimeframe = CandleTimeframe.M15
    ): CentralSignalCandidate {
        val dir = when (report.candidateDirection) {
            SignalDecision.LONG -> FuturesDirection.LONG
            SignalDecision.SHORT -> FuturesDirection.SHORT
            SignalDecision.NO_TRADE -> null
        }
        val rr = report.netRiskRewardSummary
        val confirmed = report.qualificationTier != StrictQualificationTier.NO_TRADE &&
            report.allMandatoryFiltersPassed &&
            dir != null
        val fundGroup = report.evidenceGroups.firstOrNull { it.groupId == EvidenceGroupId.G_FUTURES_LIQUIDITY }
        val fundStatus = when (report.audit.futuresTelemetryStatus) {
            "LIVE_FRESH" -> "LIVE"
            "STALE_OR_INVALID" -> "STALE"
            else -> if (fundGroup?.status?.name == "VALID") "LIVE" else "UNAVAILABLE"
        }

        return CentralSignalCandidate(
            provider = CandidateProviderSource.STRICT_SIGNAL_QUALITY,
            symbol = normalizeSymbol(report.symbol),
            timeframe = timeframe,
            direction = dir,
            isEntryConfirmed = confirmed,
            isWatchingOnly = !confirmed,
            rawNativeScore = report.totalScore,
            strictQualityScore = report.totalScore,
            entryPrice = rr?.entryPrice ?: 0.0,
            stopLoss = rr?.stopLoss ?: 0.0,
            takeProfit1 = rr?.targetPrice ?: 0.0,
            takeProfit2 = rr?.targetPrice ?: 0.0,
            takeProfit3 = rr?.targetPrice ?: 0.0,
            grossRiskReward = rr?.grossRiskReward ?: 0.0,
            netRiskReward = rr?.netRiskReward ?: 0.0,
            dataStatus = dataStatus,
            dataSourceName = dataSourceName,
            currentPrice = rr?.entryPrice ?: 0.0,
            snapshotTimestampMs = report.timestampMs,
            confirmationCandleCloseMs = confirmationCandleCloseMs,
            expiresAtMs = report.timestampMs + 4 * 3_600_000L,
            candleCount = candleCount,
            hasClosedCandleConfirmation = report.mandatoryFilters.firstOrNull {
                it.id == "F4_CONFIRMED_15M_TRIGGER"
            }?.passed ?: false,
            setupTypeLabel = report.qualificationTier.label,
            setupSignature = "STRICT_QUALITY_${report.symbol}_${timeframe.name}",
            reasons = report.evidenceGroups.flatMap { it.evidencePassed }.take(6),
            invalidationCondition = report.missingConfirmations.firstOrNull() ?: "Strict mandatory filter violation",
            strictQualityReport = report,
            allMandatoryFiltersPassed = report.allMandatoryFiltersPassed,
            failedFilterReasons = report.mandatoryFilters.filter { !it.passed }.map {
                "Filter #${it.filterNumber} (${it.name}): ${it.reason}"
            },
            fundingStatus = fundStatus,
            liquidationStatus = "UNAVAILABLE",
            isFundingAvailable = fundStatus == "LIVE",
            isLiquidationAvailable = false
        )
    }

    fun fromAiTradingSignal(
        signal: AiTradingSignal,
        candleCount: Int = 60,
        confirmationCandleCloseMs: Long = signal.timestamp
    ): CentralSignalCandidate {
        val dir = when (signal.decision) {
            SignalDecision.LONG -> FuturesDirection.LONG
            SignalDecision.SHORT -> FuturesDirection.SHORT
            SignalDecision.NO_TRADE -> null
        }
        val status = when {
            signal.dataFreshness.equals("UNAVAILABLE", ignoreCase = true) -> MarketDataStatus.UNAVAILABLE
            signal.dataFreshness.equals("STALE", ignoreCase = true) -> MarketDataStatus.STALE
            signal.dataFreshness.equals("DELAYED", ignoreCase = true) -> MarketDataStatus.DELAYED
            signal.noTradeReason?.code == "DATA_UNAVAILABLE" -> MarketDataStatus.UNAVAILABLE
            signal.noTradeReason?.code == "MARKET_DATA_STALE" -> MarketDataStatus.STALE
            else -> MarketDataStatus.LIVE
        }
        val strictReport = signal.strictQualityReport
        val netRr = strictReport?.netRiskRewardSummary?.netRiskReward
            ?: signal.riskPlan?.riskRewardRatio
            ?: 0.0
        val grossRr = strictReport?.netRiskRewardSummary?.grossRiskReward
            ?: signal.riskPlan?.riskRewardRatio
            ?: 0.0

        val confirmed = dir != null &&
            signal.riskPlan != null &&
            (strictReport == null || strictReport.allMandatoryFiltersPassed)

        return CentralSignalCandidate(
            provider = CandidateProviderSource.AI_TRADING_SIGNAL,
            symbol = normalizeSymbol(signal.symbol),
            baseAsset = signal.symbol.uppercase(Locale.US),
            timeframe = signal.primaryTimeframe,
            direction = dir,
            isEntryConfirmed = confirmed,
            isWatchingOnly = !confirmed,
            rawNativeScore = signal.confidence.totalScore,
            strictQualityScore = strictReport?.totalScore,
            entryPrice = signal.riskPlan?.entryPrice ?: 0.0,
            stopLoss = signal.riskPlan?.stopLoss ?: 0.0,
            takeProfit1 = signal.riskPlan?.takeProfit1 ?: 0.0,
            takeProfit2 = signal.riskPlan?.takeProfit2 ?: 0.0,
            takeProfit3 = signal.riskPlan?.takeProfit3 ?: 0.0,
            grossRiskReward = grossRr,
            netRiskReward = netRr,
            dataStatus = status,
            dataSourceName = signal.dataSource,
            currentPrice = signal.riskPlan?.entryPrice ?: 0.0,
            snapshotTimestampMs = signal.timestamp,
            confirmationCandleCloseMs = confirmationCandleCloseMs,
            expiresAtMs = signal.timestamp + 4 * 3_600_000L,
            candleCount = candleCount,
            hasClosedCandleConfirmation = strictReport?.mandatoryFilters?.firstOrNull {
                it.id == "F4_CONFIRMED_15M_TRIGGER"
            }?.passed ?: confirmed,
            setupTypeLabel = signal.confidence.rating,
            setupSignature = "AI_SIGNAL_${signal.symbol}_${signal.primaryTimeframe.name}",
            reasons = signal.supportingIndicators + signal.smcConfirmations,
            invalidationCondition = signal.riskPlan?.invalidationCondition
                ?: signal.noTradeReason?.detailedExplanation.orEmpty(),
            strictQualityReport = strictReport,
            allMandatoryFiltersPassed = strictReport?.allMandatoryFiltersPassed,
            failedFilterReasons = strictReport?.mandatoryFilters?.filter { !it.passed }?.map {
                "Filter #${it.filterNumber} (${it.name}): ${it.reason}"
            } ?: listOfNotNull(signal.noTradeReason?.detailedExplanation),
            fundingStatus = "UNAVAILABLE",
            liquidationStatus = "UNAVAILABLE",
            isFundingAvailable = false,
            isLiquidationAvailable = false,
            originalSignalId = signal.id
        )
    }

    /**
     * Adapts MarketScannerEngine's ScannerItem as non-entry anomaly context only.
     * Anomaly items never independently trigger LONG/SHORT entry approvals.
     */
    fun fromMarketScannerItem(
        item: ScannerItem,
        dataStatus: MarketDataStatus = MarketDataStatus.LIVE,
        snapshotTimestampMs: Long = System.currentTimeMillis()
    ): CentralSignalCandidate {
        val dir = when {
            item.matchedCriteria.any { it.contains("Strong Bullish Trend", ignoreCase = true) } -> FuturesDirection.LONG
            item.matchedCriteria.any { it.contains("Strong Bearish Trend", ignoreCase = true) } -> FuturesDirection.SHORT
            else -> null
        }
        return CentralSignalCandidate(
            provider = CandidateProviderSource.MARKET_SCANNER,
            symbol = normalizeSymbol(item.symbol),
            baseAsset = item.symbol.uppercase(Locale.US),
            timeframe = CandleTimeframe.entries.firstOrNull { it.label.equals(item.timeframe, ignoreCase = true) }
                ?: CandleTimeframe.H1,
            direction = dir,
            isEntryConfirmed = false,
            isWatchingOnly = true,
            rawNativeScore = item.aiScore,
            currentPrice = item.verifiedPrice,
            dataStatus = if (item.verifiedPrice > 0.0) dataStatus else MarketDataStatus.UNAVAILABLE,
            dataSourceName = "Market Anomaly Screener (${item.timeframe})",
            snapshotTimestampMs = snapshotTimestampMs,
            candleCount = 30,
            hasClosedCandleConfirmation = false,
            setupTypeLabel = item.detectedCondition,
            setupSignature = "SCANNER_${item.id}",
            reasons = item.matchedCriteria,
            isContextOnly = true
        )
    }
}
