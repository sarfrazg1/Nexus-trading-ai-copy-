package com.example.data.signal

import com.example.data.model.CandleTimeframe
import com.example.data.model.MarketDataStatus
import com.example.data.scanner.ActiveSignalStateManager
import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.ManagedSignalState
import com.example.util.TimeZoneUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * Single Final Approval Authority for all NEW user-facing LONG and SHORT trading signals.
 *
 * Enforces the 5-stage pipeline:
 *  - Stage A: Data Validation (freshness, source, completed candles, mixed-snapshot guard, honest telemetry)
 *  - Stage B: Direction Confirmation (cross-engine disagreement -> WAIT; active opposite-direction block)
 *  - Stage C: Technical & Structural Confirmation (confirmed closed-candle trigger & S/R/MTF alignment)
 *  - Stage D: Strict Quality Gates (all 8 mandatory filters + Net R:R >= 1:4.0; no score override)
 *  - Stage E: Final Approval Threshold (finalQualityScore >= 92; score 91 rejected, 92+ approved)
 *
 * Also centralizes deduplication keys and new-signal push eligibility while preserving
 * lifecycle alerts (ENTRY_TRIGGER, SL, TP, INVALIDATION, EXPIRY) via ActiveSignalStateManager.
 */
class CentralSignalAuthority(
    val signalStateManager: ActiveSignalStateManager = ActiveSignalStateManager()
) {
    private val verdictsBySymbol = LinkedHashMap<String, CentralSignalVerdict>()
    private val notifiedDedupKeys = LinkedHashMap<String, Long>()

    private val _verdicts = MutableStateFlow<List<CentralSignalVerdict>>(emptyList())
    val verdicts: StateFlow<List<CentralSignalVerdict>> = _verdicts.asStateFlow()

    @Synchronized
    fun getVerdictForSymbol(symbol: String): CentralSignalVerdict? =
        verdictsBySymbol[CentralCandidateAdapters.normalizeSymbol(symbol)]

    @Synchronized
    fun getAllVerdicts(): List<CentralSignalVerdict> =
        verdictsBySymbol.values.sortedByDescending { it.evaluatedAtMs }

    @Synchronized
    fun hasNotifiedDedupKey(dedupKey: String): Boolean = notifiedDedupKeys.containsKey(dedupKey)

    @Synchronized
    fun evaluateAndDecide(
        context: CentralEvaluationContext,
        candidates: List<CentralSignalCandidate>
    ): CentralSignalVerdict {
        val normSymbol = CentralCandidateAdapters.normalizeSymbol(context.symbol)
        val nowMs = context.referenceTimeMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val displaySym = normSymbol
        val stages = mutableListOf<CentralValidationStageSummary>()
        val rejections = mutableListOf<String>()

        val substantiveCandidates = candidates.filter { !it.isContextOnly }
        val activeCandidates = substantiveCandidates.ifEmpty { candidates }
        val contributingProviders = candidates.map { it.provider }.distinct()

        // Resolve honest derivatives / liquidation status across context + candidates
        val fundingStatus = resolveTelemetryStatus(
            context.fundingStatus,
            activeCandidates.map { it.fundingStatus }
        )
        val liquidationStatus = resolveTelemetryStatus(
            context.liquidationStatus,
            activeCandidates.map { it.liquidationStatus }
        )

        val resolvedSource = context.dataSourceName.takeIf { isValidSource(it) }
            ?: activeCandidates.firstOrNull { isValidSource(it.dataSourceName) }?.dataSourceName
            ?: "DATA UNAVAILABLE"

        // =========================================================================
        // STAGE A: DATA VALIDATION
        // =========================================================================
        val prices = buildList {
            context.currentPrice?.takeIf { it.isFinite() && it > 0.0 }?.let { add(it) }
            activeCandidates.map { it.currentPrice }.filter { it.isFinite() && it > 0.0 }.forEach { add(it) }
        }
        val minPrice = prices.minOrNull() ?: 0.0
        val maxPrice = prices.maxOrNull() ?: 0.0
        val mixedPriceDivergence = minPrice > 0.0 && ((maxPrice - minPrice) / minPrice) > MAX_SNAPSHOT_PRICE_DIVERGENCE

        val snapTimes = buildList {
            if (context.snapshotTimestampMs > 0L) add(context.snapshotTimestampMs)
            activeCandidates.map { it.snapshotTimestampMs }.filter { it > 0L }.forEach { add(it) }
        }
        val mixedTimeSkew = snapTimes.size >= 2 &&
            ((snapTimes.maxOrNull()!! - snapTimes.minOrNull()!!) > MAX_SNAPSHOT_TIME_SKEW_MS)

        val effectiveDataStatus = when {
            context.dataStatus == MarketDataStatus.UNAVAILABLE -> MarketDataStatus.UNAVAILABLE
            context.dataStatus == MarketDataStatus.STALE -> MarketDataStatus.STALE
            activeCandidates.any { it.dataStatus == MarketDataStatus.UNAVAILABLE } &&
                activeCandidates.none { it.dataStatus == MarketDataStatus.LIVE || it.dataStatus == MarketDataStatus.DELAYED } ->
                MarketDataStatus.UNAVAILABLE
            activeCandidates.any { it.dataStatus == MarketDataStatus.STALE } -> MarketDataStatus.STALE
            else -> context.dataStatus
        }

        val effectivePrice = context.currentPrice?.takeIf { it.isFinite() && it > 0.0 }
            ?: activeCandidates.firstOrNull { it.currentPrice.isFinite() && it.currentPrice > 0.0 }?.currentPrice
            ?: 0.0

        val effectiveCandleCount = maxOf(
            context.completedCandleCount,
            activeCandidates.maxOfOrNull { it.candleCount } ?: 0
        )

        val latestSnapTs = snapTimes.maxOrNull() ?: 0L
        val isAgeStale = latestSnapTs <= 0L || (nowMs - latestSnapTs) > context.maxDataAgeMs

        val dataFailures = mutableListOf<String>()
        if (effectivePrice <= 0.0) dataFailures.add("Current market price is missing or non-positive")
        if (effectiveDataStatus == MarketDataStatus.UNAVAILABLE || effectiveDataStatus == MarketDataStatus.STALE) {
            dataFailures.add("Market data status is ${effectiveDataStatus.label}")
        }
        if (isAgeStale) {
            dataFailures.add("Snapshot timestamp is missing or older than ${context.maxDataAgeMs / 1000L}s")
        }
        if (!isValidSource(resolvedSource)) {
            dataFailures.add("Verified market data source is missing or unavailable")
        }
        if (effectiveCandleCount < MIN_REQUIRED_CANDLES) {
            dataFailures.add("Insufficient completed candles ($effectiveCandleCount/$MIN_REQUIRED_CANDLES required)")
        }
        if (mixedPriceDivergence) {
            dataFailures.add("Mixed/inconsistent price snapshots across providers (divergence > ${(MAX_SNAPSHOT_PRICE_DIVERGENCE * 100).toInt()}%)")
        }
        if (mixedTimeSkew) {
            dataFailures.add("Mixed/inconsistent snapshot timestamps across providers")
        }
        if (fundingStatus == "STALE" || fundingStatus == "INVALID") {
            dataFailures.add("Futures funding telemetry is $fundingStatus and cannot be used")
        }

        val stageAPassed = dataFailures.isEmpty()
        stages.add(
            CentralValidationStageSummary(
                stageCode = "A",
                stageName = "Data Validation",
                passed = stageAPassed,
                summary = if (stageAPassed) {
                    "Fresh ${effectiveDataStatus.label} data from $resolvedSource ($effectiveCandleCount completed candles; Funding=$fundingStatus, Liquidation=$liquidationStatus)"
                } else {
                    dataFailures.joinToString("; ")
                },
                details = dataFailures
            )
        )
        if (!stageAPassed) {
            rejections.addAll(dataFailures)
            return recordAndReturn(
                buildVerdict(
                    symbol = normSymbol,
                    displaySymbol = displaySym,
                    timeframe = context.timeframe,
                    state = CentralSignalState.REJECTED,
                    approvedDirection = null,
                    finalQualityScore = null,
                    allMandatoryFiltersPassed = false,
                    failedFilters = dataFailures,
                    isEntryConfirmed = false,
                    hasDirectionConflict = false,
                    suppressedAsDuplicate = false,
                    shouldNotifyNewSignal = false,
                    dataStatus = effectiveDataStatus,
                    dataSourceName = resolvedSource,
                    fundingStatus = fundingStatus,
                    liquidationStatus = liquidationStatus,
                    evaluatedAtMs = nowMs,
                    explanation = "REJECTED at Stage A (Data Validation): ${dataFailures.joinToString("; ")}. Funding=$fundingStatus, Liquidation=$liquidationStatus.",
                    rejectionReasons = rejections,
                    stageSummaries = stages,
                    contributingProviders = contributingProviders,
                    strictQualityReport = context.strictQualityReport
                )
            )
        }

        // =========================================================================
        // STAGE B: DIRECTION CONFIRMATION (CONFLICT & ANTI-FLIP CHECKS)
        // =========================================================================
        val directionalCandidates = substantiveCandidates.filter { it.direction != null }
        val distinctDirections = directionalCandidates.mapNotNull { it.direction }.distinct()
        val hasCrossEngineConflict = distinctDirections.size > 1

        val strictReportFromContext = context.strictQualityReport
            ?: substantiveCandidates.firstNotNullOfOrNull { it.strictQualityReport }
        val strictReportDir = strictReportFromContext?.candidateDirection?.let {
            when (it) {
                SignalDecision.LONG -> FuturesDirection.LONG
                SignalDecision.SHORT -> FuturesDirection.SHORT
                SignalDecision.NO_TRADE -> null
            }
        }
        val proposedDirection = when {
            hasCrossEngineConflict -> null
            distinctDirections.size == 1 -> distinctDirections.first()
            else -> strictReportDir
        }

        // Check conflict between StrictSignalQualityReport directional bias and candidates
        val strictVsCandidateConflict = proposedDirection != null &&
            strictReportDir != null &&
            strictReportDir != proposedDirection

        // Check active signal state machine for opposite live direction
        val existingActiveEntry = signalStateManager.getEntryForSymbol(normSymbol)
        val activeOppositeDirConflict = proposedDirection != null &&
            existingActiveEntry != null &&
            existingActiveEntry.state.isOpenOrLive &&
            existingActiveEntry.direction != proposedDirection

        val stageBDetails = mutableListOf<String>()
        if (hasCrossEngineConflict) {
            val longs = directionalCandidates.filter { it.direction == FuturesDirection.LONG }
                .joinToString { "${it.provider.displayName}(${it.rawNativeScore ?: "?"})" }
            val shorts = directionalCandidates.filter { it.direction == FuturesDirection.SHORT }
                .joinToString { "${it.provider.displayName}(${it.rawNativeScore ?: "?"})" }
            stageBDetails.add("Credible engines disagree: LONG=[$longs] vs SHORT=[$shorts] -> forcing WAIT")
        }
        if (strictVsCandidateConflict) {
            stageBDetails.add("StrictSignalQualityEngine disagrees with candidate direction ($strictReportDir vs $proposedDirection)")
        }
        if (activeOppositeDirConflict && existingActiveEntry != null) {
            stageBDetails.add("Active ${existingActiveEntry.direction.name} signal ${existingActiveEntry.signalId} (${existingActiveEntry.state.label}) blocks silent reversal to ${proposedDirection?.name}")
        }
        if (proposedDirection == null && !hasCrossEngineConflict) {
            stageBDetails.add("No confirmed directional candidate (LONG or SHORT) from upstream engines")
        }

        val stageBPassed = stageBDetails.isEmpty() && proposedDirection != null
        stages.add(
            CentralValidationStageSummary(
                stageCode = "B",
                stageName = "Direction Confirmation",
                passed = stageBPassed,
                summary = if (stageBPassed) {
                    "Unanimous direction ${proposedDirection!!.name} across ${directionalCandidates.size} candidate(s); no active opposite conflict"
                } else {
                    stageBDetails.joinToString("; ")
                },
                details = stageBDetails
            )
        )

        if (!stageBPassed || proposedDirection == null) {
            rejections.addAll(stageBDetails)
            val representative = directionalCandidates.firstOrNull() ?: activeCandidates.firstOrNull()
            return recordAndReturn(
                buildVerdict(
                    symbol = normSymbol,
                    displaySymbol = displaySym,
                    timeframe = context.timeframe,
                    state = CentralSignalState.WAIT,
                    approvedDirection = null,
                    finalQualityScore = resolveScore(representative, strictReportFromContext),
                    allMandatoryFiltersPassed = false,
                    failedFilters = stageBDetails,
                    isEntryConfirmed = false,
                    hasDirectionConflict = hasCrossEngineConflict || strictVsCandidateConflict || activeOppositeDirConflict,
                    suppressedAsDuplicate = false,
                    shouldNotifyNewSignal = false,
                    dataStatus = effectiveDataStatus,
                    dataSourceName = resolvedSource,
                    entryPrice = representative?.entryPrice ?: effectivePrice,
                    stopLoss = representative?.stopLoss ?: 0.0,
                    takeProfit1 = representative?.takeProfit1 ?: 0.0,
                    takeProfit2 = representative?.takeProfit2 ?: 0.0,
                    grossRiskReward = representative?.grossRiskReward ?: 0.0,
                    netRiskReward = representative?.netRiskReward ?: 0.0,
                    fundingStatus = fundingStatus,
                    liquidationStatus = liquidationStatus,
                    evaluatedAtMs = nowMs,
                    explanation = "WAIT at Stage B (Direction Confirmation): ${stageBDetails.joinToString("; ")}",
                    rejectionReasons = rejections,
                    stageSummaries = stages,
                    contributingProviders = contributingProviders,
                    strictQualityReport = strictReportFromContext
                )
            )
        }

        // Select strongest candidate in the unanimous direction (prefer Strict/HighReward/Confirmed)
        val alignedCandidates = directionalCandidates.filter { it.direction == proposedDirection }
        val primaryCandidate = alignedCandidates.maxWithOrNull(
            compareBy<CentralSignalCandidate> { it.isEntryConfirmed }
                .thenBy { it.strictQualityReport != null }
                .thenBy { it.provider == CandidateProviderSource.HIGH_REWARD_DETECTOR }
                .thenBy { it.provider == CandidateProviderSource.STRICT_MULTI_MARKET_SETUP }
                .thenBy { it.netRiskReward }
                .thenBy { it.strictQualityScore ?: it.rawNativeScore ?: 0 }
        )!!

        // =========================================================================
        // STAGE C: TECHNICAL & STRUCTURAL CONFIRMATION (CLOSED CANDLE + CONFIRMED ENTRY)
        // =========================================================================
        val entryConfirmed = alignedCandidates.any { it.isEntryConfirmed }
        val closedCandleConfirmed = context.hasClosedCandleConfirmation &&
            alignedCandidates.any { it.hasClosedCandleConfirmation || it.confirmationCandleCloseMs > 0L }
        val entryPrice = primaryCandidate.entryPrice.takeIf { it.isFinite() && it > 0.0 }
            ?: strictReportFromContext?.netRiskRewardSummary?.entryPrice
            ?: effectivePrice
        val stopLoss = primaryCandidate.stopLoss.takeIf { it.isFinite() && it > 0.0 }
            ?: strictReportFromContext?.netRiskRewardSummary?.stopLoss
            ?: 0.0
        val tp1 = primaryCandidate.takeProfit1.takeIf { it.isFinite() && it > 0.0 }
            ?: strictReportFromContext?.netRiskRewardSummary?.targetPrice
            ?: 0.0
        val tp2 = primaryCandidate.takeProfit2.takeIf { it.isFinite() && it > 0.0 } ?: tp1

        val hasValidLevels = entryPrice > 0.0 && stopLoss > 0.0 && tp1 > 0.0 &&
            if (proposedDirection == FuturesDirection.LONG) {
                stopLoss < entryPrice && tp1 > entryPrice
            } else {
                stopLoss > entryPrice && tp1 < entryPrice
            }

        val stageCFailures = mutableListOf<String>()
        if (!entryConfirmed) {
            stageCFailures.add("Entry trigger is NOT confirmed yet (setup is watching/pending trigger)")
        }
        if (!closedCandleConfirmed) {
            stageCFailures.add("Completed closed-candle confirmation is missing")
        }
        if (!hasValidLevels) {
            stageCFailures.add("Valid structural Entry, Stop Loss, and Take Profit levels are incomplete or inverted")
        }

        val stageCPassed = stageCFailures.isEmpty()
        stages.add(
            CentralValidationStageSummary(
                stageCode = "C",
                stageName = "Technical & Structural Confirmation",
                passed = stageCPassed,
                summary = if (stageCPassed) {
                    "Confirmed closed-candle ${proposedDirection.name} entry (${primaryCandidate.setupTypeLabel}); Entry/SL/TP geometry verified"
                } else {
                    stageCFailures.joinToString("; ")
                },
                details = stageCFailures
            )
        )

        if (!stageCPassed) {
            rejections.addAll(stageCFailures)
            return recordAndReturn(
                buildVerdict(
                    symbol = normSymbol,
                    displaySymbol = displaySym,
                    timeframe = context.timeframe,
                    state = CentralSignalState.WAIT,
                    approvedDirection = null,
                    finalQualityScore = resolveScore(primaryCandidate, strictReportFromContext),
                    allMandatoryFiltersPassed = false,
                    failedFilters = stageCFailures,
                    isEntryConfirmed = entryConfirmed,
                    hasDirectionConflict = false,
                    suppressedAsDuplicate = false,
                    shouldNotifyNewSignal = false,
                    dataStatus = effectiveDataStatus,
                    dataSourceName = resolvedSource,
                    entryPrice = entryPrice,
                    stopLoss = stopLoss,
                    takeProfit1 = tp1,
                    takeProfit2 = tp2,
                    grossRiskReward = primaryCandidate.grossRiskReward,
                    netRiskReward = primaryCandidate.netRiskReward,
                    fundingStatus = fundingStatus,
                    liquidationStatus = liquidationStatus,
                    setupTypeLabel = primaryCandidate.setupTypeLabel,
                    evaluatedAtMs = nowMs,
                    explanation = "WAIT at Stage C (Technical/Entry Confirmation): ${stageCFailures.joinToString("; ")}",
                    rejectionReasons = rejections,
                    stageSummaries = stages,
                    contributingProviders = contributingProviders,
                    strictQualityReport = strictReportFromContext
                )
            )
        }

        // =========================================================================
        // STAGE D: STRICT QUALITY GATES (ALL MANDATORY FILTERS + NET R:R >= 1:4)
        // =========================================================================
        val netRr = maxOf(
            primaryCandidate.netRiskReward,
            strictReportFromContext?.netRiskRewardSummary?.netRiskReward ?: 0.0
        )
        val grossRr = maxOf(
            primaryCandidate.grossRiskReward,
            strictReportFromContext?.netRiskRewardSummary?.grossRiskReward ?: netRr
        )

        val mandatoryFailedList = buildList {
            if (strictReportFromContext != null) {
                strictReportFromContext.mandatoryFilters.filter { !it.passed }.forEach {
                    add("Filter #${it.filterNumber} (${it.name}): ${it.reason}")
                }
            }
            alignedCandidates.forEach { c ->
                if (c.allMandatoryFiltersPassed == false && c.failedFilterReasons.isEmpty()) {
                    add("${c.provider.displayName} reported mandatory filter failure")
                } else {
                    c.failedFilterReasons.forEach { reason ->
                        if (!contains(reason)) add(reason)
                    }
                }
            }
        }

        val allMandatoryPassed = mandatoryFailedList.isEmpty() &&
            (strictReportFromContext?.allMandatoryFiltersPassed ?: alignedCandidates.all { it.allMandatoryFiltersPassed != false })

        val stageDFailures = mutableListOf<String>()
        if (!allMandatoryPassed) {
            if (mandatoryFailedList.isNotEmpty()) stageDFailures.addAll(mandatoryFailedList)
            else stageDFailures.add("One or more mandatory filters failed in StrictSignalQualityEngine")
        }
        if (context.requireNetRiskRewardCheck && netRr < context.minNetRiskReward) {
            stageDFailures.add(
                "Net R:R 1:${String.format(Locale.US, "%.2f", netRr)} is below required minimum 1:${String.format(Locale.US, "%.2f", context.minNetRiskReward)} after fees/slippage"
            )
        }

        val stageDPassed = stageDFailures.isEmpty()
        stages.add(
            CentralValidationStageSummary(
                stageCode = "D",
                stageName = "Strict Quality Gates",
                passed = stageDPassed,
                summary = if (stageDPassed) {
                    "All mandatory quality filters passed; Net R:R 1:${String.format(Locale.US, "%.2f", netRr)} (Gross 1:${String.format(Locale.US, "%.2f", grossRr)})"
                } else {
                    stageDFailures.joinToString("; ")
                },
                details = stageDFailures
            )
        )

        val resolvedQualityScore = resolveScore(primaryCandidate, strictReportFromContext)

        if (!stageDPassed) {
            rejections.addAll(stageDFailures)
            val stateD = if (context.requireNetRiskRewardCheck && netRr < context.minNetRiskReward && allMandatoryPassed) {
                CentralSignalState.WAIT
            } else {
                CentralSignalState.REJECTED
            }
            return recordAndReturn(
                buildVerdict(
                    symbol = normSymbol,
                    displaySymbol = displaySym,
                    timeframe = context.timeframe,
                    state = stateD,
                    approvedDirection = null,
                    finalQualityScore = resolvedQualityScore,
                    allMandatoryFiltersPassed = false,
                    failedFilters = stageDFailures,
                    isEntryConfirmed = true,
                    hasDirectionConflict = false,
                    suppressedAsDuplicate = false,
                    shouldNotifyNewSignal = false,
                    dataStatus = effectiveDataStatus,
                    dataSourceName = resolvedSource,
                    entryPrice = entryPrice,
                    stopLoss = stopLoss,
                    takeProfit1 = tp1,
                    takeProfit2 = tp2,
                    grossRiskReward = grossRr,
                    netRiskReward = netRr,
                    fundingStatus = fundingStatus,
                    liquidationStatus = liquidationStatus,
                    setupTypeLabel = primaryCandidate.setupTypeLabel,
                    evaluatedAtMs = nowMs,
                    explanation = "${stateD.label} at Stage D (Strict Quality Gates): ${stageDFailures.joinToString("; ")}",
                    rejectionReasons = rejections,
                    stageSummaries = stages,
                    contributingProviders = contributingProviders,
                    strictQualityReport = strictReportFromContext
                )
            )
        }

        // =========================================================================
        // STAGE E: FINAL APPROVAL GATE (SCORE >= 92; 91 NOT APPROVED, 92+ APPROVED)
        // =========================================================================
        val stageEFailures = mutableListOf<String>()
        if (resolvedQualityScore == null || resolvedQualityScore < 0) {
            stageEFailures.add("Final quality score is missing or uncomputable")
        } else if (resolvedQualityScore < MIN_APPROVAL_SCORE) {
            stageEFailures.add("Final quality score $resolvedQualityScore/100 is below required minimum threshold $MIN_APPROVAL_SCORE/100")
        }

        val stageEPassed = stageEFailures.isEmpty()
        stages.add(
            CentralValidationStageSummary(
                stageCode = "E",
                stageName = "Final Approval",
                passed = stageEPassed,
                summary = if (stageEPassed) {
                    "APPROVED ${proposedDirection.name} with Quality Score $resolvedQualityScore/100 (>= $MIN_APPROVAL_SCORE required)"
                } else {
                    stageEFailures.joinToString("; ")
                },
                details = stageEFailures
            )
        )

        if (!stageEPassed) {
            rejections.addAll(stageEFailures)
            val rejectedState = if (resolvedQualityScore == null) CentralSignalState.REJECTED else CentralSignalState.WAIT
            return recordAndReturn(
                buildVerdict(
                    symbol = normSymbol,
                    displaySymbol = displaySym,
                    timeframe = context.timeframe,
                    state = rejectedState,
                    approvedDirection = null,
                    finalQualityScore = resolvedQualityScore,
                    allMandatoryFiltersPassed = true,
                    failedFilters = stageEFailures,
                    isEntryConfirmed = true,
                    hasDirectionConflict = false,
                    suppressedAsDuplicate = false,
                    shouldNotifyNewSignal = false,
                    dataStatus = effectiveDataStatus,
                    dataSourceName = resolvedSource,
                    entryPrice = entryPrice,
                    stopLoss = stopLoss,
                    takeProfit1 = tp1,
                    takeProfit2 = tp2,
                    grossRiskReward = grossRr,
                    netRiskReward = netRr,
                    fundingStatus = fundingStatus,
                    liquidationStatus = liquidationStatus,
                    setupTypeLabel = primaryCandidate.setupTypeLabel,
                    evaluatedAtMs = nowMs,
                    explanation = "${rejectedState.label} at Stage E (Final Approval): ${stageEFailures.joinToString("; ")}",
                    rejectionReasons = rejections,
                    stageSummaries = stages,
                    contributingProviders = contributingProviders,
                    strictQualityReport = strictReportFromContext
                )
            )
        }

        // =========================================================================
        // DEDUPLICATION, DETERMINISTIC IDENTITY & STATE MANAGER REGISTRATION
        // =========================================================================
        val finalScore = resolvedQualityScore!!
        val closeMs = alignedCandidates.maxOfOrNull { it.confirmationCandleCloseMs }?.takeIf { it > 0L } ?: nowMs
        val tfDurationMs = context.timeframe.durationMillis.coerceAtLeast(60_000L)
        val candleBucket = closeMs / tfDurationMs
        val setupSig = primaryCandidate.setupSignature.takeIf { it.isNotBlank() }
            ?: primaryCandidate.setupTypeLabel.uppercase(Locale.US).replace(" ", "_")
        val entryZoneBucket = if (entryPrice >= 100.0) {
            String.format(Locale.US, "%.1f", entryPrice)
        } else {
            String.format(Locale.US, "%.4f", entryPrice)
        }
        val dedupKey = "CSA|${normSymbol}|${proposedDirection.name}|${context.timeframe.name}|${setupSig}|${candleBucket}|$entryZoneBucket"
        val detSignalId = primaryCandidate.originalSignalId?.takeIf { it.isNotBlank() }
            ?: ActiveSignalStateManager.buildDeterministicId(normSymbol, proposedDirection, closeMs)
        val expiresAtMs = primaryCandidate.expiresAtMs.takeIf { it > nowMs } ?: (nowMs + 4 * 3_600_000L)

        val intake = signalStateManager.submitCandidate(
            symbol = normSymbol,
            direction = proposedDirection,
            isConfirmed = true,
            entryPrice = entryPrice,
            stopLoss = stopLoss,
            takeProfit1 = tp1,
            takeProfit2 = tp2,
            netRiskReward = netRr,
            qualityScore = finalScore,
            dataSourceName = resolvedSource,
            setupReason = primaryCandidate.reasons.firstOrNull() ?: primaryCandidate.setupTypeLabel,
            confirmationCandleCloseMs = closeMs,
            expiresAtMs = expiresAtMs,
            nowMs = nowMs,
            preferredSignalId = detSignalId,
            setupSignature = dedupKey
        )

        val alreadyNotifiedSameKey = notifiedDedupKeys.containsKey(dedupKey)
        val canDispatchNotification = intake.accepted &&
            !intake.isDuplicateSuppressed &&
            !alreadyNotifiedSameKey &&
            signalStateManager.shouldDispatchNotification(
                signalId = intake.entry?.signalId ?: detSignalId,
                symbol = normSymbol,
                direction = proposedDirection,
                nowMs = nowMs
            )

        val isDup = alreadyNotifiedSameKey || intake.isDuplicateSuppressed || !canDispatchNotification
        val approvedState = if (proposedDirection == FuturesDirection.LONG) {
            CentralSignalState.APPROVED_LONG
        } else {
            CentralSignalState.APPROVED_SHORT
        }

        val verdict = buildVerdict(
            signalId = intake.entry?.signalId ?: detSignalId,
            dedupKey = dedupKey,
            symbol = normSymbol,
            displaySymbol = displaySym,
            timeframe = context.timeframe,
            state = approvedState,
            approvedDirection = proposedDirection,
            finalQualityScore = finalScore,
            allMandatoryFiltersPassed = true,
            failedFilters = emptyList(),
            isEntryConfirmed = true,
            hasDirectionConflict = false,
            suppressedAsDuplicate = isDup,
            shouldNotifyNewSignal = canDispatchNotification,
            dataStatus = effectiveDataStatus,
            dataSourceName = resolvedSource,
            entryPrice = entryPrice,
            stopLoss = stopLoss,
            takeProfit1 = tp1,
            takeProfit2 = tp2,
            grossRiskReward = grossRr,
            netRiskReward = netRr,
            fundingStatus = fundingStatus,
            liquidationStatus = liquidationStatus,
            setupTypeLabel = primaryCandidate.setupTypeLabel,
            setupSignature = dedupKey,
            invalidationCondition = primaryCandidate.invalidationCondition,
            confirmationCandleCloseMs = closeMs,
            expiresAtMs = expiresAtMs,
            evaluatedAtMs = nowMs,
            explanation = buildString {
                append("${approvedState.label} • Score $finalScore/100 (>= $MIN_APPROVAL_SCORE) • Net R:R 1:${String.format(Locale.US, "%.2f", netRr)} • $resolvedSource. ")
                append(primaryCandidate.reasons.take(2).joinToString("; "))
                if (fundingStatus == "UNAVAILABLE" || liquidationStatus == "UNAVAILABLE") {
                    append(" [Telemetry: Funding=$fundingStatus, Liquidation=$liquidationStatus]")
                }
                if (isDup) {
                    append(" [Duplicate new-entry alert suppressed]")
                }
            },
            rejectionReasons = emptyList(),
            stageSummaries = stages,
            contributingProviders = contributingProviders,
            strictQualityReport = strictReportFromContext
        )
        return recordAndReturn(verdict)
    }

    @Synchronized
    fun markNewSignalNotificationDispatched(verdict: CentralSignalVerdict, nowMs: Long = System.currentTimeMillis()) {
        notifiedDedupKeys[verdict.dedupKey] = nowMs
        trimDedupKeys()
        verdict.approvedDirection?.let { dir ->
            signalStateManager.markNotificationDispatched(verdict.signalId, verdict.symbol, dir, nowMs)
        }
    }

    @Synchronized
    fun markDedupKeyDispatched(dedupKey: String, nowMs: Long = System.currentTimeMillis()) {
        if (dedupKey.isNotBlank()) {
            notifiedDedupKeys[dedupKey] = nowMs
            trimDedupKeys()
        }
    }

    fun isNotificationEligible(verdict: CentralSignalVerdict): Boolean {
        return verdict.state.isApproved &&
            verdict.approvedDirection != null &&
            (verdict.finalQualityScore ?: 0) >= MIN_APPROVAL_SCORE &&
            verdict.allMandatoryFiltersPassed &&
            verdict.isEntryConfirmed &&
            (verdict.dataStatus == MarketDataStatus.LIVE || verdict.dataStatus == MarketDataStatus.DELAYED) &&
            !verdict.hasDirectionConflict &&
            !verdict.suppressedAsDuplicate &&
            verdict.shouldNotifyNewSignal
    }

    /**
     * Synchronizes verdict lifecycle on live price updates / expiry / structure invalidation.
     * Lifecycle alerts themselves continue via AlertMonitorEngine; this keeps CentralSignalVerdict
     * honest and unlocks re-evaluation once a prior active signal becomes EXPIRED or INVALIDATED.
     */
    @Synchronized
    fun onMarketPriceUpdate(
        symbol: String,
        currentPrice: Double,
        nowMs: Long = System.currentTimeMillis(),
        invalidatedByStructure: Boolean = false,
        invalidationReason: String? = null
    ): CentralSignalVerdict? {
        val normSymbol = CentralCandidateAdapters.normalizeSymbol(symbol)
        val transition = signalStateManager.evaluateMarketUpdate(
            symbol = normSymbol,
            currentPrice = currentPrice,
            nowMs = nowMs,
            invalidatedByStructure = invalidatedByStructure,
            invalidationReason = invalidationReason
        )
        val existing = verdictsBySymbol[normSymbol] ?: return null
        if (!existing.state.isApproved) return existing

        val newVerdict = when (transition?.toState) {
            ManagedSignalState.EXPIRED -> existing.copy(
                state = CentralSignalState.EXPIRED,
                shouldNotifyNewSignal = false,
                evaluatedAtMs = nowMs,
                evaluatedAtPkt = TimeZoneUtils.formatKarachiTimestamp(nowMs),
                explanation = "EXPIRED: ${transition.reason}"
            )
            ManagedSignalState.INVALIDATED, ManagedSignalState.STOP_LOSS_HIT -> existing.copy(
                state = CentralSignalState.INVALIDATED,
                shouldNotifyNewSignal = false,
                evaluatedAtMs = nowMs,
                evaluatedAtPkt = TimeZoneUtils.formatKarachiTimestamp(nowMs),
                explanation = "INVALIDATED: ${transition.reason}"
            )
            ManagedSignalState.TARGET_HIT -> existing.copy(
                state = CentralSignalState.EXPIRED,
                shouldNotifyNewSignal = false,
                evaluatedAtMs = nowMs,
                evaluatedAtPkt = TimeZoneUtils.formatKarachiTimestamp(nowMs),
                explanation = "COMPLETED (Target Hit): ${transition.reason}"
            )
            else -> {
                if (existing.expiresAtMs > 0L && nowMs >= existing.expiresAtMs) {
                    existing.copy(
                        state = CentralSignalState.EXPIRED,
                        shouldNotifyNewSignal = false,
                        evaluatedAtMs = nowMs,
                        evaluatedAtPkt = TimeZoneUtils.formatKarachiTimestamp(nowMs),
                        explanation = "EXPIRED: Signal validity window ended at ${TimeZoneUtils.formatKarachiTimestamp(existing.expiresAtMs)}"
                    )
                } else existing
            }
        }
        if (newVerdict != existing) {
            verdictsBySymbol[normSymbol] = newVerdict
            _verdicts.value = getAllVerdicts()
        }
        return newVerdict
    }

    @Synchronized
    fun exportDedupStateJson(): String {
        val obj = JSONObject()
        val dedupArr = JSONArray()
        notifiedDedupKeys.entries.forEach { (k, ts) ->
            dedupArr.put(JSONObject().apply {
                put("key", k)
                put("ts", ts)
            })
        }
        obj.put("dedup", dedupArr)
        val verdictArr = JSONArray()
        verdictsBySymbol.values.take(40).forEach { v ->
            verdictArr.put(JSONObject().apply {
                put("verdictId", v.verdictId)
                put("signalId", v.signalId)
                put("dedupKey", v.dedupKey)
                put("symbol", v.symbol)
                put("displaySymbol", v.displaySymbol)
                put("tf", v.timeframe.name)
                put("state", v.state.name)
                v.approvedDirection?.let { put("dir", it.name) }
                v.finalQualityScore?.let { put("score", it) }
                put("filtersOk", v.allMandatoryFiltersPassed)
                put("confirmed", v.isEntryConfirmed)
                put("conflict", v.hasDirectionConflict)
                put("dup", v.suppressedAsDuplicate)
                put("dataStatus", v.dataStatus.name)
                put("source", v.dataSourceName)
                put("entry", v.entryPrice)
                put("sl", v.stopLoss)
                put("tp1", v.takeProfit1)
                put("tp2", v.takeProfit2)
                put("grossRr", v.grossRiskReward)
                put("netRr", v.netRiskReward)
                put("fundingStatus", v.fundingStatus)
                put("liqStatus", v.liquidationStatus)
                put("setupLabel", v.setupTypeLabel)
                put("setupSig", v.setupSignature)
                put("closeMs", v.confirmationCandleCloseMs)
                put("expMs", v.expiresAtMs)
                put("evalMs", v.evaluatedAtMs)
                put("expText", v.explanation)
            })
        }
        obj.put("verdicts", verdictArr)
        return obj.toString()
    }

    @Synchronized
    fun restoreDedupStateJson(raw: String) {
        if (raw.isBlank()) return
        try {
            val obj = JSONObject(raw)
            val dedupArr = obj.optJSONArray("dedup")
            if (dedupArr != null) {
                for (i in 0 until dedupArr.length()) {
                    val item = dedupArr.optJSONObject(i) ?: continue
                    val key = item.optString("key")
                    val ts = item.optLong("ts", 0L)
                    if (key.isNotBlank() && ts > 0L) {
                        notifiedDedupKeys[key] = ts
                    }
                }
            }
            val verdictArr = obj.optJSONArray("verdicts")
            if (verdictArr != null) {
                for (i in 0 until verdictArr.length()) {
                    val o = verdictArr.optJSONObject(i) ?: continue
                    val sym = o.optString("symbol")
                    if (sym.isBlank()) continue
                    val state = CentralSignalState.entries.firstOrNull { it.name == o.optString("state") } ?: continue
                    val dir = o.optString("dir").takeIf { it.isNotBlank() }?.let { d ->
                        FuturesDirection.entries.firstOrNull { it.name == d }
                    }
                    val tf = CandleTimeframe.entries.firstOrNull { it.name == o.optString("tf") } ?: CandleTimeframe.M15
                    val ds = MarketDataStatus.entries.firstOrNull { it.name == o.optString("dataStatus") }
                        ?: MarketDataStatus.UNAVAILABLE
                    val evalMs = o.optLong("evalMs", System.currentTimeMillis())
                    verdictsBySymbol[sym] = CentralSignalVerdict(
                        verdictId = o.optString("verdictId"),
                        signalId = o.optString("signalId"),
                        dedupKey = o.optString("dedupKey"),
                        symbol = sym,
                        displaySymbol = o.optString("displaySymbol", sym),
                        timeframe = tf,
                        state = state,
                        approvedDirection = dir,
                        finalQualityScore = if (o.has("score")) o.optInt("score") else null,
                        allMandatoryFiltersPassed = o.optBoolean("filtersOk", false),
                        failedFilters = emptyList(),
                        isEntryConfirmed = o.optBoolean("confirmed", false),
                        hasDirectionConflict = o.optBoolean("conflict", false),
                        suppressedAsDuplicate = o.optBoolean("dup", false),
                        shouldNotifyNewSignal = false,
                        dataStatus = ds,
                        dataSourceName = o.optString("source", "UNAVAILABLE"),
                        entryPrice = o.optDouble("entry", 0.0),
                        stopLoss = o.optDouble("sl", 0.0),
                        takeProfit1 = o.optDouble("tp1", 0.0),
                        takeProfit2 = o.optDouble("tp2", 0.0),
                        grossRiskReward = o.optDouble("grossRr", 0.0),
                        netRiskReward = o.optDouble("netRr", 0.0),
                        fundingStatus = o.optString("fundingStatus", "UNAVAILABLE"),
                        liquidationStatus = o.optString("liqStatus", "UNAVAILABLE"),
                        setupTypeLabel = o.optString("setupLabel", ""),
                        setupSignature = o.optString("setupSig", ""),
                        confirmationCandleCloseMs = o.optLong("closeMs", 0L),
                        expiresAtMs = o.optLong("expMs", 0L),
                        evaluatedAtMs = evalMs,
                        explanation = o.optString("expText", "")
                    )
                }
                _verdicts.value = getAllVerdicts()
            }
        } catch (_: Exception) {
        }
    }

    private fun resolveScore(
        candidate: CentralSignalCandidate?,
        strictReport: StrictSignalQualityReport?
    ): Int? {
        return strictReport?.totalScore
            ?: candidate?.strictQualityScore
            ?: candidate?.rawNativeScore
    }

    private fun isValidSource(source: String?): Boolean {
        if (source.isNullOrBlank()) return false
        val upper = source.uppercase(Locale.US).trim()
        return upper != "UNAVAILABLE" &&
            !upper.startsWith("DATA UNAVAILABLE") &&
            upper != "UNVERIFIED SOURCE"
    }

    private fun resolveTelemetryStatus(
        explicit: String?,
        candidateStatuses: List<String>
    ): String {
        if (!explicit.isNullOrBlank()) return explicit
        if (candidateStatuses.any { it == "STALE" || it == "INVALID" }) return "STALE"
        if (candidateStatuses.any { it == "LIVE" }) return "LIVE"
        return "UNAVAILABLE"
    }

    private fun recordAndReturn(verdict: CentralSignalVerdict): CentralSignalVerdict {
        verdictsBySymbol[verdict.symbol] = verdict
        _verdicts.value = getAllVerdicts()
        return verdict
    }

    private fun buildVerdict(
        signalId: String = "",
        dedupKey: String = "",
        symbol: String,
        displaySymbol: String,
        timeframe: CandleTimeframe,
        state: CentralSignalState,
        approvedDirection: FuturesDirection?,
        finalQualityScore: Int?,
        allMandatoryFiltersPassed: Boolean,
        failedFilters: List<String>,
        isEntryConfirmed: Boolean,
        hasDirectionConflict: Boolean,
        suppressedAsDuplicate: Boolean,
        shouldNotifyNewSignal: Boolean,
        dataStatus: MarketDataStatus,
        dataSourceName: String,
        entryPrice: Double = 0.0,
        stopLoss: Double = 0.0,
        takeProfit1: Double = 0.0,
        takeProfit2: Double = 0.0,
        grossRiskReward: Double = 0.0,
        netRiskReward: Double = 0.0,
        fundingStatus: String = "UNAVAILABLE",
        liquidationStatus: String = "UNAVAILABLE",
        setupTypeLabel: String = "",
        setupSignature: String = "",
        invalidationCondition: String = "",
        confirmationCandleCloseMs: Long = 0L,
        expiresAtMs: Long = 0L,
        evaluatedAtMs: Long,
        explanation: String,
        rejectionReasons: List<String>,
        stageSummaries: List<CentralValidationStageSummary>,
        contributingProviders: List<CandidateProviderSource>,
        strictQualityReport: StrictSignalQualityReport?
    ): CentralSignalVerdict {
        val resolvedSigId = signalId.ifBlank { "csa_${symbol}_${state.name}_$evaluatedAtMs" }
        val resolvedDedup = dedupKey.ifBlank { "CSA|$symbol|${state.name}|${timeframe.name}|$evaluatedAtMs" }
        return CentralSignalVerdict(
            verdictId = "verdict_${symbol}_$evaluatedAtMs",
            signalId = resolvedSigId,
            dedupKey = resolvedDedup,
            symbol = symbol,
            displaySymbol = displaySymbol,
            timeframe = timeframe,
            state = state,
            approvedDirection = approvedDirection,
            finalQualityScore = finalQualityScore,
            allMandatoryFiltersPassed = allMandatoryFiltersPassed,
            failedFilters = failedFilters,
            isEntryConfirmed = isEntryConfirmed,
            hasDirectionConflict = hasDirectionConflict,
            suppressedAsDuplicate = suppressedAsDuplicate,
            shouldNotifyNewSignal = shouldNotifyNewSignal,
            dataStatus = dataStatus,
            dataSourceName = dataSourceName,
            entryPrice = entryPrice,
            stopLoss = stopLoss,
            takeProfit1 = takeProfit1,
            takeProfit2 = takeProfit2,
            grossRiskReward = grossRiskReward,
            netRiskReward = netRiskReward,
            fundingStatus = fundingStatus,
            liquidationStatus = liquidationStatus,
            setupTypeLabel = setupTypeLabel,
            setupSignature = setupSignature,
            invalidationCondition = invalidationCondition,
            confirmationCandleCloseMs = confirmationCandleCloseMs,
            expiresAtMs = expiresAtMs,
            evaluatedAtMs = evaluatedAtMs,
            evaluatedAtPkt = TimeZoneUtils.formatKarachiTimestamp(evaluatedAtMs),
            explanation = explanation,
            rejectionReasons = rejectionReasons,
            stageSummaries = stageSummaries,
            contributingProviders = contributingProviders,
            strictQualityReport = strictQualityReport
        )
    }

    private fun trimDedupKeys() {
        while (notifiedDedupKeys.size > MAX_PERSISTED_DEDUP_KEYS) {
            val firstKey = notifiedDedupKeys.keys.firstOrNull() ?: break
            notifiedDedupKeys.remove(firstKey)
        }
    }

    companion object {
        const val MIN_APPROVAL_SCORE = 92
        const val MIN_REQUIRED_CANDLES = 20
        const val MAX_SNAPSHOT_PRICE_DIVERGENCE = 0.025
        const val MAX_SNAPSHOT_TIME_SKEW_MS = 10 * 60 * 1000L
        private const val MAX_PERSISTED_DEDUP_KEYS = 300
    }
}
