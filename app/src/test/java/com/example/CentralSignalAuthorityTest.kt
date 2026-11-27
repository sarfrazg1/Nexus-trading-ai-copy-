package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.alerts.AlertMonitorEngine
import com.example.data.alerts.AlertRuleCategory
import com.example.data.alerts.TradeAlertRule
import com.example.data.indicators.VolatilityRegime
import com.example.data.model.AssetCategory
import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.model.MarketDataStatus
import com.example.data.remote.BinanceFuturesApiClient
import com.example.data.scanner.ActiveSignalStateManager
import com.example.data.scanner.CryptoSetupPattern
import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.FuturesScanSignal
import com.example.data.scanner.FuturesScannerRepository
import com.example.data.scanner.FuturesSignalLifecycle
import com.example.data.scanner.HighRewardOpportunity
import com.example.data.scanner.HighRewardScanResult
import com.example.data.scanner.OpportunityPriorityTier
import com.example.data.scanner.OpportunityStatus
import com.example.data.signal.CandidateProviderSource
import com.example.data.signal.CentralCandidateAdapters
import com.example.data.signal.CentralEvaluationContext
import com.example.data.signal.CentralSignalAuthority
import com.example.data.signal.CentralSignalCandidate
import com.example.data.signal.CentralSignalState
import com.example.data.signal.CorrelatedIndicatorAudit
import com.example.data.signal.ForwardValidationOutcome
import com.example.data.signal.MandatoryFilterCheck
import com.example.data.signal.NetRiskRewardSummary
import com.example.data.signal.SignalDecision
import com.example.data.signal.StrictQualificationTier
import com.example.data.signal.StrictSignalQualityReport
import com.example.data.timemachine.MultiCoinBacktestConfig
import com.example.data.timemachine.SingleCoinHistoricalInput
import com.example.data.timemachine.StrategyBacktestEngine
import com.example.util.NexusNotificationManager
import com.example.util.TimeZoneUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CentralSignalAuthorityTest {

    private val baseNowMs = 1_780_000_200_000L

    private fun buildStrictReport(
        symbol: String = "BTCUSDT",
        direction: SignalDecision = SignalDecision.LONG,
        score: Int = 93,
        allFiltersPassed: Boolean = true,
        failedFilterIndex: Int? = null,
        netRr: Double = 4.6,
        entry: Double = 90_000.0,
        sl: Double = 89_000.0,
        tp: Double = 94_800.0
    ): StrictSignalQualityReport {
        val filters = (1..8).map { num ->
            val pass = if (!allFiltersPassed && (failedFilterIndex == null || failedFilterIndex == num)) {
                false
            } else {
                true
            }
            val id = if (num == 4) "F4_CONFIRMED_15M_TRIGGER" else "F$num"
            MandatoryFilterCheck(
                id = id,
                filterNumber = num,
                name = "Mandatory Filter #$num",
                passed = pass,
                reason = if (pass) "Verified" else "Failed mandatory filter #$num check"
            )
        }
        return StrictSignalQualityReport(
            symbol = symbol,
            assetCategory = AssetCategory.CRYPTO,
            candidateDirection = direction,
            qualificationTier = when {
                !allFiltersPassed || netRr < 4.0 || score < 85 -> StrictQualificationTier.NO_TRADE
                score >= 92 -> StrictQualificationTier.HIGH_QUALITY_SETUP
                else -> StrictQualificationTier.QUALIFIED_SETUP
            },
            totalScore = score,
            rawDirectionalScore = score,
            evidenceGroups = emptyList(),
            mandatoryFilters = filters,
            missingConfirmations = if (allFiltersPassed) emptyList() else listOf("Filter failure"),
            volatilityRegime = VolatilityRegime.NORMAL,
            netRiskRewardSummary = NetRiskRewardSummary(
                entryPrice = entry,
                stopLoss = sl,
                targetPrice = tp,
                grossRiskReward = netRr + 0.2,
                roundTripFeeSlippagePercent = 0.16,
                netRiskReward = netRr,
                meetsMinOneToFour = netRr >= 4.0,
                calculationSummary = "Net 1:$netRr"
            ),
            audit = CorrelatedIndicatorAudit(
                emaSupertrendClusterCapped = true,
                rsiStochRsiMerged = true,
                obvMfiCmfMerged = true,
                adxStrengthOnlyEnforced = true,
                volumeAvailable = true,
                futuresTelemetryStatus = "UNAVAILABLE"
            ),
            timestampMs = baseNowMs
        )
    }

    private fun buildCandidate(
        provider: CandidateProviderSource = CandidateProviderSource.HIGH_REWARD_DETECTOR,
        symbol: String = "BTCUSDT",
        direction: FuturesDirection? = FuturesDirection.LONG,
        score: Int? = 93,
        isConfirmed: Boolean = true,
        allFiltersPassed: Boolean = true,
        netRr: Double = 4.6,
        entry: Double = 90_000.0,
        sl: Double = 89_000.0,
        tp1: Double = 94_800.0,
        dataStatus: MarketDataStatus = MarketDataStatus.LIVE,
        source: String = "Binance USDT-M Futures Public REST",
        snapshotTsMs: Long = baseNowMs - 30_000L,
        closeTsMs: Long = baseNowMs - 60_000L,
        candleCount: Int = 60,
        strictReport: StrictSignalQualityReport? = null,
        fundingStatus: String = "LIVE"
    ): CentralSignalCandidate {
        val isShort = direction == FuturesDirection.SHORT
        val finalSl = if (isShort && sl < entry) entry + (entry - sl) else sl
        val finalTp = if (isShort && tp1 > entry) entry - (tp1 - entry) else tp1
        return CentralSignalCandidate(
            provider = provider,
            symbol = symbol,
            timeframe = CandleTimeframe.M15,
            direction = direction,
            isEntryConfirmed = isConfirmed,
            rawNativeScore = score,
            strictQualityScore = strictReport?.totalScore ?: score,
            entryPrice = entry,
            stopLoss = finalSl,
            takeProfit1 = finalTp,
            takeProfit2 = finalTp,
            takeProfit3 = finalTp,
            grossRiskReward = netRr + 0.2,
            netRiskReward = netRr,
            dataStatus = dataStatus,
            dataSourceName = source,
            currentPrice = entry,
            snapshotTimestampMs = snapshotTsMs,
            confirmationCandleCloseMs = closeTsMs,
            expiresAtMs = baseNowMs + 4 * 3_600_000L,
            candleCount = candleCount,
            hasClosedCandleConfirmation = isConfirmed && closeTsMs > 0L,
            setupTypeLabel = "BOS + Golden Zone Retest",
            setupSignature = "BOS_GZ_${symbol}_${direction?.name ?: "NONE"}",
            reasons = listOf("1D/4H/1H aligned", "Closed 15M bullish engulfing"),
            invalidationCondition = "15M close beyond SL",
            strictQualityReport = strictReport,
            allMandatoryFiltersPassed = strictReport?.allMandatoryFiltersPassed ?: allFiltersPassed,
            failedFilterReasons = if (allFiltersPassed) emptyList() else listOf("Mandatory filter failed"),
            fundingStatus = fundingStatus,
            liquidationStatus = "UNAVAILABLE"
        )
    }

    private fun buildContext(
        symbol: String = "BTCUSDT",
        dataStatus: MarketDataStatus = MarketDataStatus.LIVE,
        source: String = "Binance USDT-M Futures Public REST",
        price: Double? = 90_000.0,
        snapshotTsMs: Long = baseNowMs - 30_000L,
        candleCount: Int = 60,
        strictReport: StrictSignalQualityReport? = null,
        fundingStatus: String? = "LIVE"
    ) = CentralEvaluationContext(
        symbol = symbol,
        timeframe = CandleTimeframe.M15,
        dataStatus = dataStatus,
        dataSourceName = source,
        currentPrice = price,
        snapshotTimestampMs = snapshotTsMs,
        completedCandleCount = candleCount,
        hasClosedCandleConfirmation = candleCount >= 20,
        strictQualityReport = strictReport,
        fundingStatus = fundingStatus,
        liquidationStatus = "UNAVAILABLE",
        minNetRiskReward = 4.0,
        requireNetRiskRewardCheck = true,
        referenceTimeMs = baseNowMs
    )

    private fun freshNotificationManager(prefix: String): NexusNotificationManager {
        val context: Context = ApplicationProvider.getApplicationContext()
        val mgr = NexusNotificationManager(context)
        mgr.setMasterAlertsEnabled(true)
        mgr.setLongSignalsEnabled(true)
        mgr.setShortSignalsEnabled(true)
        return mgr
    }

    // 1. Score 91 is NOT approved and NEVER triggers a new-signal push notification
    @Test
    fun score91RejectedAndNeverTriggersNewSignalNotification() {
        val authority = CentralSignalAuthority()
        val mgr = freshNotificationManager("score91")
        val sym = "BTC91_${System.nanoTime()}USDT"
        val report = buildStrictReport(symbol = sym, score = 91, allFiltersPassed = true, netRr = 4.8)
        val candidate = buildCandidate(symbol = sym, score = 91, strictReport = report, netRr = 4.8)

        val verdict = authority.evaluateAndDecide(
            context = buildContext(symbol = sym, strictReport = report),
            candidates = listOf(candidate)
        )

        assertFalse("Score 91 must NEVER be approved", verdict.state.isApproved)
        assertEquals(CentralSignalState.WAIT, verdict.state)
        assertFalse(authority.isNotificationEligible(verdict))
        assertFalse(mgr.notifyCentralApprovedSignal(verdict, playSound = false, nowMs = baseNowMs))
    }

    // 2. Score 92 IS approved when all mandatory conditions pass (boundary inclusive >= 92)
    @Test
    fun score92ApprovedWhenAllMandatoryFiltersPass() {
        val authority = CentralSignalAuthority()
        val mgr = freshNotificationManager("score92")
        val sym = "ETH92_${System.nanoTime()}USDT"
        val report = buildStrictReport(symbol = sym, score = 92, allFiltersPassed = true, netRr = 4.5, entry = 3000.0, sl = 2950.0, tp = 3240.0)
        val candidate = buildCandidate(symbol = sym, score = 92, strictReport = report, netRr = 4.5, entry = 3000.0, sl = 2950.0, tp1 = 3240.0)

        val verdict = authority.evaluateAndDecide(
            context = buildContext(symbol = sym, price = 3000.0, strictReport = report),
            candidates = listOf(candidate)
        )

        assertEquals(CentralSignalState.APPROVED_LONG, verdict.state)
        assertEquals(92, verdict.finalQualityScore)
        assertTrue(authority.isNotificationEligible(verdict))
        assertTrue(mgr.notifyCentralApprovedSignal(verdict, playSound = false, nowMs = baseNowMs))
    }

    // 3. Score 93+ IS approved when all mandatory conditions pass
    @Test
    fun score93PlusApprovedWhenAllMandatoryFiltersPass() {
        val authority = CentralSignalAuthority()
        val mgr = freshNotificationManager("score95")
        val sym = "SOL95_${System.nanoTime()}USDT"
        val report = buildStrictReport(
            symbol = sym,
            direction = SignalDecision.SHORT,
            score = 95,
            allFiltersPassed = true,
            netRr = 5.4,
            entry = 180.0,
            sl = 183.0,
            tp = 163.0
        )
        val candidate = buildCandidate(
            symbol = sym,
            direction = FuturesDirection.SHORT,
            score = 95,
            strictReport = report,
            netRr = 5.4,
            entry = 180.0,
            sl = 183.0,
            tp1 = 163.0
        )

        val verdict = authority.evaluateAndDecide(
            context = buildContext(symbol = sym, price = 180.0, strictReport = report),
            candidates = listOf(candidate)
        )

        assertEquals(CentralSignalState.APPROVED_SHORT, verdict.state)
        assertEquals(FuturesDirection.SHORT, verdict.approvedDirection)
        assertTrue(authority.isNotificationEligible(verdict))
        assertTrue(mgr.notifyCentralApprovedSignal(verdict, playSound = false, nowMs = baseNowMs))
    }

    // 4. Failed mandatory filter at score 99 is STILL rejected
    @Test
    fun failedMandatoryFilterAtScore99RejectedEvenHighScore() {
        val authority = CentralSignalAuthority()
        val mgr = freshNotificationManager("score99_fail")
        val sym = "BNB99_${System.nanoTime()}USDT"
        val report = buildStrictReport(
            symbol = sym,
            score = 99,
            allFiltersPassed = false,
            failedFilterIndex = 2,
            netRr = 5.5
        )
        val candidate = buildCandidate(
            symbol = sym,
            score = 99,
            allFiltersPassed = false,
            strictReport = report,
            netRr = 5.5
        )

        val verdict = authority.evaluateAndDecide(
            context = buildContext(symbol = sym, strictReport = report),
            candidates = listOf(candidate)
        )

        assertFalse("Failed mandatory filter at score 99 MUST be rejected", verdict.state.isApproved)
        assertEquals(CentralSignalState.REJECTED, verdict.state)
        assertFalse(verdict.allMandatoryFiltersPassed)
        assertFalse(mgr.notifyCentralApprovedSignal(verdict, playSound = false, nowMs = baseNowMs))
    }

    // 5. Conflicting LONG and SHORT candidates on same symbol/TF produce WAIT (never pick slightly higher score)
    @Test
    fun conflictingLongAndShortCandidatesForceWaitNeverPickHigherScore() {
        val authority = CentralSignalAuthority()
        val sym = "XRPCONFLICT_${System.nanoTime()}USDT"
        val longCandidate = buildCandidate(
            provider = CandidateProviderSource.HIGH_REWARD_DETECTOR,
            symbol = sym,
            direction = FuturesDirection.LONG,
            score = 96,
            netRr = 5.2
        )
        val shortCandidate = buildCandidate(
            provider = CandidateProviderSource.BINANCE_FUTURES_SCANNER,
            symbol = sym,
            direction = FuturesDirection.SHORT,
            score = 93,
            netRr = 4.6
        )

        val verdict = authority.evaluateAndDecide(
            context = buildContext(symbol = sym),
            candidates = listOf(longCandidate, shortCandidate)
        )

        assertEquals(CentralSignalState.WAIT, verdict.state)
        assertNull(verdict.approvedDirection)
        assertTrue(verdict.hasDirectionConflict)
        assertFalse(verdict.shouldNotifyNewSignal)
    }

    // 6. Stale / delayed-broken / mixed-snapshot data is rejected cleanly at Stage A
    @Test
    fun staleDelayedMixedSnapshotDataRejectedAtStageA() {
        val authority = CentralSignalAuthority()

        // 6a: Stale market status & old timestamp
        val staleVerdict = authority.evaluateAndDecide(
            context = buildContext(
                symbol = "BTCUSDT",
                dataStatus = MarketDataStatus.STALE,
                snapshotTsMs = baseNowMs - (15 * 60_000L)
            ),
            candidates = listOf(buildCandidate(dataStatus = MarketDataStatus.STALE, snapshotTsMs = baseNowMs - (15 * 60_000L)))
        )
        assertEquals(CentralSignalState.REJECTED, staleVerdict.state)
        assertFalse(staleVerdict.stageSummaries.first { it.stageCode == "A" }.passed)

        // 6b: Mixed / inconsistent snapshots (>2.5% price divergence across providers)
        val c1 = buildCandidate(provider = CandidateProviderSource.BINANCE_FUTURES_SCANNER, entry = 90_000.0)
        val c2 = buildCandidate(provider = CandidateProviderSource.HIGH_REWARD_DETECTOR, entry = 95_000.0)
        val mixedVerdict = authority.evaluateAndDecide(
            context = buildContext(symbol = "BTCUSDT", price = 90_000.0),
            candidates = listOf(c1, c2)
        )
        assertEquals(CentralSignalState.REJECTED, mixedVerdict.state)
        assertTrue(mixedVerdict.explanation.contains("Mixed", ignoreCase = true))
    }

    // 7. Unconfirmed watching/pending entry is rejected (WAIT)
    @Test
    fun unconfirmedWatchingPendingEntryRejectedAsWait() {
        val authority = CentralSignalAuthority()
        val sym = "ADAWATCH_${System.nanoTime()}USDT"
        val watchingCandidate = buildCandidate(
            symbol = sym,
            score = 94,
            isConfirmed = false,
            netRr = 5.0
        )

        val verdict = authority.evaluateAndDecide(
            context = buildContext(symbol = sym),
            candidates = listOf(watchingCandidate)
        )

        assertEquals(CentralSignalState.WAIT, verdict.state)
        assertFalse(verdict.isEntryConfirmed)
        assertFalse(verdict.shouldNotifyNewSignal)
    }

    // 8. Net R:R below 1:4.0 is rejected
    @Test
    fun netRiskRewardBelowFourToOneRejected() {
        val authority = CentralSignalAuthority()
        val sym = "LINKRR_${System.nanoTime()}USDT"
        val lowRrReport = buildStrictReport(symbol = sym, score = 95, allFiltersPassed = true, netRr = 3.75)
        val candidate = buildCandidate(symbol = sym, score = 95, netRr = 3.75, strictReport = lowRrReport)

        val verdict = authority.evaluateAndDecide(
            context = buildContext(symbol = sym, strictReport = lowRrReport),
            candidates = listOf(candidate)
        )

        assertFalse(verdict.state.isApproved)
        assertTrue(verdict.explanation.contains("below required minimum 1:4.00", ignoreCase = true))
        assertFalse(authority.isNotificationEligible(verdict))
    }

    // 9. Duplicate candidates across repeated scans and multiple engines produce ONE notification only
    @Test
    fun duplicateCandidatesAcrossScansAndEnginesSuppressedSingleNotification() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val mgr = freshNotificationManager("dedup")
        val authority = CentralSignalAuthority()
        val sym = "SUIDEDUP_${System.nanoTime()}USDT"
        val report = buildStrictReport(symbol = sym, score = 94, allFiltersPassed = true, netRr = 5.1, entry = 2.50, sl = 2.40, tp = 3.05)

        val fromScanner = buildCandidate(
            provider = CandidateProviderSource.BINANCE_FUTURES_SCANNER,
            symbol = sym,
            score = 94,
            netRr = 5.1,
            entry = 2.50,
            sl = 2.40,
            tp1 = 3.05,
            strictReport = report
        )
        val fromHighReward = buildCandidate(
            provider = CandidateProviderSource.HIGH_REWARD_DETECTOR,
            symbol = sym,
            score = 95,
            netRr = 5.1,
            entry = 2.50,
            sl = 2.40,
            tp1 = 3.05,
            strictReport = report
        )

        val ctx = buildContext(symbol = sym, price = 2.50, strictReport = report)
        val firstVerdict = authority.evaluateAndDecide(ctx, listOf(fromScanner, fromHighReward))
        assertEquals(CentralSignalState.APPROVED_LONG, firstVerdict.state)
        assertTrue(authority.isNotificationEligible(firstVerdict))

        val firstSent = mgr.notifyCentralApprovedSignal(firstVerdict, playSound = false, nowMs = baseNowMs)
        assertTrue(firstSent)
        authority.markNewSignalNotificationDispatched(firstVerdict, baseNowMs)

        // Second scan on same candle & entry zone from another engine must suppress duplicate notification
        val secondVerdict = authority.evaluateAndDecide(
            ctx.copy(referenceTimeMs = baseNowMs + 60_000L),
            listOf(fromHighReward)
        )
        assertTrue(secondVerdict.suppressedAsDuplicate)
        assertFalse(authority.isNotificationEligible(secondVerdict))
        assertFalse(mgr.notifyCentralApprovedSignal(secondVerdict, playSound = false, nowMs = baseNowMs + 60_000L))
    }

    // 10. Trade lifecycle alerts (SL, TP, Expiry, Invalidation) remain functional & verdict transitions honestly
    @Test
    fun lifecycleAlertsPreservedAndVerdictSyncsOnStopLossExpiryInvalidation() {
        val mgr = freshNotificationManager("lifecycle")
        val authority = CentralSignalAuthority()
        val sym = "BTCLIFE_${System.nanoTime()}USDT"
        val report = buildStrictReport(symbol = sym, score = 94, allFiltersPassed = true, netRr = 4.8)
        val candidate = buildCandidate(symbol = sym, score = 94, strictReport = report)

        val approved = authority.evaluateAndDecide(buildContext(symbol = sym, strictReport = report), listOf(candidate))
        assertEquals(CentralSignalState.APPROVED_LONG, approved.state)

        val slRule = TradeAlertRule(
            ruleId = "rule_sl_${System.nanoTime()}",
            signalId = approved.signalId,
            symbol = sym,
            direction = FuturesDirection.LONG,
            category = AlertRuleCategory.STOP_LOSS,
            triggerPrice = 89_000.0,
            conditionDescription = "SL hit below 89,000"
        )
        val events = AlertMonitorEngine.evaluateRules(
            rules = listOf(slRule),
            livePrices = mapOf(sym to 88_900.0),
            nowMs = baseNowMs + 120_000L
        )
        assertEquals(1, events.size)
        val ev = events.first()
        assertTrue(
            mgr.notifyTradeLifecycleAlert(
                eventId = ev.eventId,
                symbol = ev.symbol,
                signalId = ev.signalId,
                alertType = ev.alertType,
                title = ev.title,
                body = ev.body,
                timestampMs = ev.timestampMs,
                source = "AlertMonitorEngine Test"
            )
        )

        val updatedVerdict = authority.onMarketPriceUpdate(sym, 88_900.0, baseNowMs + 120_000L)
        assertNotNull(updatedVerdict)
        assertEquals(CentralSignalState.INVALIDATED, updatedVerdict!!.state)
    }

    // 11. Foreground and Background paths enforce identical CentralSignalAuthority rules & cross-run dedup
    @Test
    fun foregroundAndBackgroundParityEnforcesIdenticalApprovalAndCrossRunPersistence() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val mgr = freshNotificationManager("parity")
        val repo1 = FuturesScannerRepository(context, mgr)
        val sym = "PARITY_${System.nanoTime()}USDT"

        // Inject an approved signal via evaluateThroughCentralAuthority (simulating Foreground Service)
        val report = buildStrictReport(symbol = sym, score = 93, allFiltersPassed = true, netRr = 4.7)
        val candidate = buildCandidate(symbol = sym, score = 93, strictReport = report)
        val fgVerdict = repo1.evaluateThroughCentralAuthority(
            context = buildContext(symbol = sym, strictReport = report),
            candidates = listOf(candidate),
            playSound = false,
            source = "Foreground Scanner Service"
        )
        assertEquals(CentralSignalState.APPROVED_LONG, fgVerdict.state)

        // Simulate new repository instance spun up by WorkManager BackgroundAlertWorker in fresh process/run
        val repo2 = FuturesScannerRepository(context, mgr)
        val bgDuplicate = repo2.evaluateThroughCentralAuthority(
            context = buildContext(symbol = sym, strictReport = report).copy(referenceTimeMs = baseNowMs + 45_000L),
            candidates = listOf(candidate),
            playSound = false,
            source = "WorkManager Background Scanner (15m)"
        )
        assertTrue("Background run must recognize persisted dedupKey from foreground run", bgDuplicate.suppressedAsDuplicate)
        assertFalse(bgDuplicate.shouldNotifyNewSignal)

        // Verify background path also blocks score 91 identically
        val lowSym = "PARITY91_${System.nanoTime()}USDT"
        val lowReport = buildStrictReport(symbol = lowSym, score = 91, allFiltersPassed = true, netRr = 4.7)
        val bgRejected = repo2.evaluateThroughCentralAuthority(
            context = buildContext(symbol = lowSym, strictReport = lowReport),
            candidates = listOf(buildCandidate(symbol = lowSym, score = 91, strictReport = lowReport)),
            playSound = false,
            source = "WorkManager Background Scanner (15m)"
        )
        assertFalse(bgRejected.state.isApproved)
    }

    // 12. Missing critical data (price <= 0, <20 candles, UNAVAILABLE source) is rejected cleanly with honest telemetry
    @Test
    fun missingCriticalDataRejectedWithHonestUnavailableTelemetry() {
        val authority = CentralSignalAuthority()
        val sym = "MISSING_${System.nanoTime()}USDT"
        val verdict = authority.evaluateAndDecide(
            context = CentralEvaluationContext(
                symbol = sym,
                timeframe = CandleTimeframe.M15,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "DATA UNAVAILABLE",
                currentPrice = 0.0,
                snapshotTimestampMs = 0L,
                completedCandleCount = 8,
                hasClosedCandleConfirmation = false,
                fundingStatus = "UNAVAILABLE",
                liquidationStatus = "UNAVAILABLE",
                referenceTimeMs = baseNowMs
            ),
            candidates = emptyList()
        )

        assertEquals(CentralSignalState.REJECTED, verdict.state)
        assertNull(verdict.approvedDirection)
        assertEquals("UNAVAILABLE", verdict.fundingStatus)
        assertEquals("UNAVAILABLE", verdict.liquidationStatus)
        assertFalse(verdict.shouldNotifyNewSignal)
        assertTrue(verdict.rejectionReasons.size >= 3)
    }

    // 13. Real market data provenance accurately distinguishes LIVE, STALE, and UNAVAILABLE
    @Test
    fun resolveCandleFetchStatusDistinguishesLiveStaleAndUnavailable() {
        val api = BinanceFuturesApiClient()
        val sym = "PROVUSDT"

        // Empty list -> UNAVAILABLE
        val unavail = api.resolveCandleFetchStatus(sym, CandleTimeframe.M15, emptyList(), baseNowMs)
        assertEquals(MarketDataStatus.UNAVAILABLE, unavail.dataStatus)
        assertEquals("DATA UNAVAILABLE", unavail.dataSourceName)
        assertEquals(0L, unavail.fetchedAtMs)

        // Bar closed 50 minutes ago (> 15m + 20m) -> STALE
        val staleCandles = listOf(
            Candle(
                openTime = baseNowMs - 65 * 60_000L,
                open = 100.0,
                high = 102.0,
                low = 99.0,
                close = 101.0,
                volume = 1000.0,
                closeTime = baseNowMs - 50 * 60_000L
            )
        )
        val stale = api.resolveCandleFetchStatus(sym, CandleTimeframe.M15, staleCandles, baseNowMs)
        assertEquals(MarketDataStatus.STALE, stale.dataStatus)

        // Bar closed 10 minutes ago (legitimate mid-bar 15M scan) -> LIVE
        val liveCandles = listOf(
            Candle(
                openTime = baseNowMs - 25 * 60_000L,
                open = 100.0,
                high = 102.0,
                low = 99.0,
                close = 101.0,
                volume = 1000.0,
                closeTime = baseNowMs - 10 * 60_000L
            )
        )
        val live = api.resolveCandleFetchStatus(sym, CandleTimeframe.M15, liveCandles, baseNowMs)
        assertEquals(MarketDataStatus.LIVE, live.dataStatus)
        assertEquals(baseNowMs - 10 * 60_000L, live.latestCompletedCloseMs)
    }

    // 14. Mid-bar live scan with confirmationCandleCloseMs 10 minutes ago and fresh fetchedAtMs passes Stage A
    @Test
    fun midBarLiveScanWithFreshFetchTimestampPassesStageA() {
        val authority = CentralSignalAuthority()
        val sym = "MIDBAR_${System.nanoTime()}USDT"
        val report = buildStrictReport(symbol = sym, score = 93, allFiltersPassed = true, netRr = 4.9)
        val candidate = buildCandidate(
            symbol = sym,
            score = 93,
            strictReport = report,
            snapshotTsMs = baseNowMs - 5_000L,
            closeTsMs = baseNowMs - 10 * 60_000L
        )
        val verdict = authority.evaluateAndDecide(
            context = buildContext(
                symbol = sym,
                snapshotTsMs = baseNowMs - 5_000L,
                strictReport = report
            ),
            candidates = listOf(candidate)
        )
        assertEquals(CentralSignalState.APPROVED_LONG, verdict.state)
        assertTrue(verdict.stageSummaries.first { it.stageCode == "A" }.passed)
    }

    // 15. External push dedup tracking prevents repeat FCM alerts via markDedupKeyDispatched
    @Test
    fun markDedupKeyDispatchedSuppressesRepeatNotification() {
        val authority = CentralSignalAuthority()
        val key = "FCM|BTCUSDT|LONG|94|1780000000"
        assertFalse(authority.hasNotifiedDedupKey(key))
        authority.markDedupKeyDispatched(key, baseNowMs)
        assertTrue(authority.hasNotifiedDedupKey(key))
    }

    // 16. Forward-Validation Ledger records Central-Approved signals, updates TP/SL/Expiry, and persists
    @Test
    fun forwardValidationLedgerTracksAndPersistsOutcomesSeparatelyFromBacktest() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val mgr = freshNotificationManager("fwd")
        val repo = FuturesScannerRepository(context, mgr)

        val longSym = "FWDL_${System.nanoTime()}USDT"
        val longReport = buildStrictReport(
            symbol = longSym,
            score = 94,
            allFiltersPassed = true,
            netRr = 5.0,
            entry = 100.0,
            sl = 98.0,
            tp = 110.5
        )
        val longCand = buildCandidate(
            symbol = longSym,
            direction = FuturesDirection.LONG,
            score = 94,
            strictReport = longReport,
            netRr = 5.0,
            entry = 100.0,
            sl = 98.0,
            tp1 = 105.0
        ).copy(takeProfit2 = 110.5)

        val vLong = repo.evaluateThroughCentralAuthority(
            context = buildContext(symbol = longSym, price = 100.0, strictReport = longReport),
            candidates = listOf(longCand),
            playSound = false,
            source = "Forward Validation Test"
        )
        assertEquals(CentralSignalState.APPROVED_LONG, vLong.state)

        // Duplicate evaluation must NOT create a second ledger row
        repo.evaluateThroughCentralAuthority(
            context = buildContext(symbol = longSym, price = 100.0, strictReport = longReport)
                .copy(referenceTimeMs = baseNowMs + 10_000L),
            candidates = listOf(longCand),
            playSound = false,
            source = "Forward Validation Test"
        )
        assertEquals(1, repo.forwardValidationRecords.value.count { it.symbol == longSym })
        assertEquals(
            ForwardValidationOutcome.STILL_OPEN,
            repo.forwardValidationRecords.value.first { it.symbol == longSym }.outcome
        )

        // Price drops to hit SL -> SL_HIT
        repo.evaluateForwardValidationOnLivePrices(mapOf(longSym to 97.5), baseNowMs + 60_000L)
        assertEquals(
            ForwardValidationOutcome.SL_HIT,
            repo.forwardValidationRecords.value.first { it.symbol == longSym }.outcome
        )

        // Approved SHORT hitting TP2 -> TP2_HIT
        val shortSym = "FWDS_${System.nanoTime()}USDT"
        val shortReport = buildStrictReport(
            symbol = shortSym,
            direction = SignalDecision.SHORT,
            score = 95,
            allFiltersPassed = true,
            netRr = 5.2,
            entry = 200.0,
            sl = 204.0,
            tp = 178.0
        )
        val shortCand = buildCandidate(
            symbol = shortSym,
            direction = FuturesDirection.SHORT,
            score = 95,
            strictReport = shortReport,
            netRr = 5.2,
            entry = 200.0,
            sl = 204.0,
            tp1 = 188.0
        ).copy(takeProfit2 = 178.0)

        val vShort = repo.evaluateThroughCentralAuthority(
            context = buildContext(symbol = shortSym, price = 200.0, strictReport = shortReport),
            candidates = listOf(shortCand),
            playSound = false,
            source = "Forward Validation Test"
        )
        assertEquals(CentralSignalState.APPROVED_SHORT, vShort.state)

        repo.evaluateForwardValidationOnLivePrices(mapOf(shortSym to 177.5), baseNowMs + 120_000L)
        assertEquals(
            ForwardValidationOutcome.TP2_HIT,
            repo.forwardValidationRecords.value.first { it.symbol == shortSym }.outcome
        )

        // Verify persistence across new repository instance
        val repoReloaded = FuturesScannerRepository(context, mgr)
        assertEquals(
            ForwardValidationOutcome.SL_HIT,
            repoReloaded.forwardValidationRecords.value.first { it.symbol == longSym }.outcome
        )
        assertEquals(
            ForwardValidationOutcome.TP2_HIT,
            repoReloaded.forwardValidationRecords.value.first { it.symbol == shortSym }.outcome
        )
    }

    // 17. Upgraded causal backtest computes funnel counts, avg win/loss/RR, IS/OOS PKT dates & zero look-ahead
    @Test
    fun upgradedCausalBacktestComputesFunnelMetricsIsOosAndZeroLookAhead() {
        val barMs = 15L * 60_000L
        val totalBars = 600
        val startMs = baseNowMs - totalBars * barMs
        val candles = ArrayList<Candle>(totalBars)
        var price = 100.0
        for (i in 0 until totalBars) {
            val trend = when {
                i < 220 -> 0.14
                i < 420 -> -0.12
                else -> 0.16
            }
            val wave = sin(i * 0.25) * 0.35
            val open = price
            val close = (open + trend + wave).coerceAtLeast(20.0)
            val high = maxOf(open, close) + 0.45
            val low = minOf(open, close) - 0.40
            val openT = startMs + i * barMs
            candles.add(
                Candle(
                    openTime = openT,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = 1200.0 + (i % 7) * 250.0,
                    closeTime = openT + barMs - 1L
                )
            )
            price = close
        }

        val fullInput = SingleCoinHistoricalInput(symbol = "BTCUSDT", candles15m = candles)
        val cfg = MultiCoinBacktestConfig(minSetupQualityScore = 88, minNetRiskReward = 4.0)
        val fullReport = StrategyBacktestEngine.runMultiCoinBacktest(
            inputs = listOf(fullInput),
            config = cfg,
            nowMs = baseNowMs
        )

        assertTrue("Expected directional candidate funnel to evaluate bars", fullReport.eligibleSignalsCount > 0)
        assertTrue("Expected rejected count from strict gates", fullReport.rejectedSignalsCount > 0)
        assertTrue(fullReport.rejectionReasonCounts.isNotEmpty())
        assertTrue(fullReport.timeframeLabel.contains("15M", ignoreCase = true))
        assertEquals(totalBars, fullReport.inOutSampleSplit.inSampleCandleCount + fullReport.inOutSampleSplit.outOfSampleCandleCount)
        assertTrue(fullReport.inOutSampleSplit.inSamplePeriodPkt != "N/A")
        assertTrue(fullReport.inOutSampleSplit.outOfSamplePeriodPkt != "N/A")

        // Causal zero-look-ahead test: prefix of first 350 bars must produce identical trades before bar 250
        val prefixCandles = candles.take(350)
        val prefixReport = StrategyBacktestEngine.runMultiCoinBacktest(
            inputs = listOf(SingleCoinHistoricalInput(symbol = "BTCUSDT", candles15m = prefixCandles)),
            config = cfg,
            nowMs = baseNowMs
        )
        val prefixBefore250 = prefixReport.sampleTrades.filter { it.exitBarIndex < 250 }
        val fullBefore250 = fullReport.sampleTrades.filter { it.exitBarIndex < 250 }
        assertEquals(prefixBefore250.size, fullBefore250.size)
        prefixBefore250.zip(fullBefore250).forEach { (a, b) ->
            assertEquals(a.entryBarIndex, b.entryBarIndex)
            assertEquals(a.direction, b.direction)
            assertEquals(a.entryPrice, b.entryPrice, 1e-6)
        }
    }

    // 18. PKT (Asia/Karachi, UTC+5) timestamp formatting consistency
    @Test
    fun pktTimezoneFormattingIsConsistentAsiaKarachi() {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(2026, java.util.Calendar.JUNE, 1, 0, 0, 0)
        }
        val utcMidnightMs = cal.timeInMillis
        val formatted = TimeZoneUtils.formatKarachiTimestamp(utcMidnightMs)
        assertTrue("Expected PKT label in '$formatted'", formatted.contains("PKT"))
        assertTrue("Expected UTC+5 05:00 in '$formatted'", formatted.contains("05:00"))
        assertEquals("Jun 01, 2026", TimeZoneUtils.formatKarachiDateOnly(utcMidnightMs))
        assertEquals("2026-06", TimeZoneUtils.formatKarachiMonthKey(utcMidnightMs))
    }
}
