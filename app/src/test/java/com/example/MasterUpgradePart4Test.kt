package com.example

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AlertType
import com.example.data.model.Candle
import com.example.data.remote.NexusFirebaseMessagingService
import com.example.data.risk.BudgetConfig
import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.FuturesScannerRepository
import com.example.data.scanner.HighRewardOpportunity
import com.example.data.scanner.HighRewardOpportunityDetector
import com.example.data.scanner.HighRewardScanResult
import com.example.data.scanner.HighRewardUrduExplanation
import com.example.data.scanner.OpportunityPriorityTier
import com.example.data.scanner.OpportunityStatus
import com.example.util.NexusNotificationManager
import com.example.util.TimeZoneUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MasterUpgradePart4Test {

    @Test
    fun detectsGenuineLongOneToFivePlusOpportunityAfterFeesAndSlippage() = runBlocking {
        val nowMs = 1795521600000L // 2026-11-24 12:00:00 UTC -> 17:00:00 PKT
        // Construct realistic HTF bullish pullback + 15M bullish structure with structural HTF liquidity target above
        val candles4h = buildBullishHtfPullbackWithSupplyAbove(
            count = 30,
            barMs = 4 * 3_600_000L,
            nowMs = nowMs,
            priorPeak = 106_000.0,
            pullbackBottom = 99_800.0,
            currentPrice = 100_000.0
        )
        val candles1h = buildBullishHtfPullbackWithSupplyAbove(
            count = 50,
            barMs = 3_600_000L,
            nowMs = nowMs,
            priorPeak = 105_500.0,
            pullbackBottom = 99_700.0,
            currentPrice = 100_000.0
        )
        val candles15m = buildLongBreakout15m(
            count = 50,
            nowMs = nowMs,
            supportFloor = 99_600.0,
            breakoutClose = 100_050.0,
            atrStep = 140.0
        )
        val candles5m = buildLongBreakout15m(
            count = 30,
            nowMs = nowMs,
            barMs = 300_000L,
            supportFloor = 99_650.0,
            breakoutClose = 100_050.0,
            atrStep = 80.0
        )

        val result = HighRewardOpportunityDetector.scanPair(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            currentPrice = 100_050.0,
            candles4h = candles4h,
            candles1h = candles1h,
            candles15m = candles15m,
            candles5m = candles5m,
            fundingRatePercent = 0.05,
            budgetConfig = BudgetConfig(
                accountBalanceUsdt = 1000.0,
                maxRiskPerTradeAmount = 20.0,
                leverageLimit = 5,
                feeRatePercentPerSide = 0.05,
                slippagePercentPerSide = 0.03
            ),
            referenceTimeMs = nowMs
        )

        assertTrue(
            "Expected qualified LONG opportunity >=1:5 (diag=${result.bestDiagnostic.reason})",
            result.qualifiedOpportunities.isNotEmpty()
        )
        val opp = result.qualifiedOpportunities.first()
        assertEquals(FuturesDirection.LONG, opp.direction)
        assertEquals(OpportunityPriorityTier.HIGHEST_1_TO_5_PLUS, opp.tier)
        assertTrue("Expected net R:R >= 5.0 but got ${opp.netRiskRewardRatio}", opp.netRiskRewardRatio >= 5.0)
        assertTrue(opp.grossRiskRewardRatio > opp.netRiskRewardRatio)
        assertTrue(opp.estimatedFeesUsdt > 0.0)
        assertTrue(opp.estimatedSlippageUsdt > 0.0)
        assertTrue("Positive funding must deduct adverse funding reserve on LONG", opp.estimatedFundingReserveUsdt > 0.0)
        assertTrue(opp.estimatedNetProfitUsdt > opp.estimatedNetLossUsdt * 5.0)
        assertEquals(1.0, opp.normalizedRiskUsd, 0.0001)
        assertEquals(opp.netRiskRewardRatio, opp.normalizedNetRewardUsd, 0.001)
        assertTrue(opp.entryTriggerTimestampPkt.contains("PKT"))
        assertTrue(opp.expiresAtPkt.contains("PKT"))
        assertTrue(opp.isAlertOnlyNoAutoTrade)
        assertTrue(opp.historicalProbabilityNote.contains("Confidence", ignoreCase = true) || opp.historicalProbabilityNote.isNotBlank())
        if (opp.historicalSampleSize < HighRewardOpportunityDetector.MIN_HISTORICAL_OCCURRENCES_FOR_PROB) {
            assertNull("Never fabricate historical hit probability on low sample", opp.historicalTargetHitProbabilityPercent)
        }
    }

    @Test
    fun detectsGenuineShortAndRejectsBelowOneToFourOrUnconfirmedTriggerWithoutWideningTp() = runBlocking {
        val nowMs = 1795521600000L
        // 1. Genuine SHORT setup with HTF sell-side liquidity target well below
        val candles4hShort = buildBearishHtfBounceWithLiquidityBelow(
            count = 30,
            barMs = 4 * 3_600_000L,
            nowMs = nowMs,
            priorBottom = 2_820.0,
            bounceTop = 3_018.0,
            currentPrice = 3_000.0
        )
        val candles1hShort = buildBearishHtfBounceWithLiquidityBelow(
            count = 50,
            barMs = 3_600_000L,
            nowMs = nowMs,
            priorBottom = 2_840.0,
            bounceTop = 3_016.0,
            currentPrice = 3_000.0
        )
        val candles15mShort = buildShortBreakdown15m(
            count = 50,
            nowMs = nowMs,
            resistanceCeiling = 3_012.0,
            breakdownClose = 2_996.0
        )
        val shortResult = HighRewardOpportunityDetector.scanPair(
            symbol = "ETHUSDT",
            baseAsset = "ETH",
            currentPrice = 2_996.0,
            candles4h = candles4hShort,
            candles1h = candles1hShort,
            candles15m = candles15mShort,
            candles5m = candles15mShort.takeLast(30),
            fundingRatePercent = -0.04,
            budgetConfig = BudgetConfig(accountBalanceUsdt = 1000.0, maxRiskPerTradeAmount = 25.0, leverageLimit = 5),
            referenceTimeMs = nowMs
        )
        assertTrue(
            "Expected genuine SHORT >=1:4 setup (diag=${shortResult.bestDiagnostic.reason})",
            shortResult.qualifiedOpportunities.any { it.direction == FuturesDirection.SHORT }
        )
        val shortOpp = shortResult.qualifiedOpportunities.first { it.direction == FuturesDirection.SHORT }
        assertTrue("Negative funding must deduct adverse funding reserve on SHORT", shortOpp.estimatedFundingReserveUsdt > 0.0)

        // 2. Tight range with < 1:4 R:R MUST be rejected cleanly as WAIT (never widen TP artificially!)
        val tight4h = buildBullishHtfPullbackWithSupplyAbove(
            count = 30,
            barMs = 4 * 3_600_000L,
            nowMs = nowMs,
            priorPeak = 100_900.0, // only +0.85% above vs ~0.45% SL -> ~1.9 gross R:R (< 4.0)
            pullbackBottom = 99_600.0,
            currentPrice = 100_000.0
        )
        val tight1h = buildBullishHtfPullbackWithSupplyAbove(
            count = 50,
            barMs = 3_600_000L,
            nowMs = nowMs,
            priorPeak = 100_850.0,
            pullbackBottom = 99_600.0,
            currentPrice = 100_000.0
        )
        val tight15m = buildLongBreakout15m(
            count = 50,
            nowMs = nowMs,
            supportFloor = 99_550.0,
            breakoutClose = 100_050.0,
            atrStep = 140.0
        )
        val rejected = HighRewardOpportunityDetector.scanPair(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            currentPrice = 100_050.0,
            candles4h = tight4h,
            candles1h = tight1h,
            candles15m = tight15m,
            candles5m = tight15m.takeLast(30),
            fundingRatePercent = 0.01,
            referenceTimeMs = nowMs
        )
        assertTrue("Setups < 1:4 net R:R must be rejected", rejected.qualifiedOpportunities.isEmpty())
        assertFalse(rejected.bestDiagnostic.tier.isQualified)
        assertEquals(OpportunityPriorityTier.WAIT_BELOW_1_TO_4, rejected.bestDiagnostic.tier)

        // 3. Unconfirmed entry trigger (wick sweeps above 100,090 but completed candle closes inside range at 99,810) MUST be rejected
        val unconfirmed15m = tight15m.dropLast(1) + tight15m.last().copy(
            open = 99_790.0,
            high = 100_090.0,
            low = 99_770.0,
            close = 99_810.0
        )
        val unconfirmed = HighRewardOpportunityDetector.scanPair(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            currentPrice = 99_810.0,
            candles4h = buildBullishHtfPullbackWithSupplyAbove(30, 4 * 3_600_000L, nowMs, 106_000.0, 99_800.0, 100_000.0),
            candles1h = buildBullishHtfPullbackWithSupplyAbove(50, 3_600_000L, nowMs, 105_500.0, 99_700.0, 100_000.0),
            candles15m = unconfirmed15m,
            candles5m = unconfirmed15m.takeLast(30),
            fundingRatePercent = 0.01,
            referenceTimeMs = nowMs
        )
        assertTrue("Unconfirmed entry trigger must never qualify (diag=${unconfirmed.bestDiagnostic.reason})", unconfirmed.qualifiedOpportunities.isEmpty())
        assertFalse(unconfirmed.bestDiagnostic.tier.isQualified)
    }

    @Test
    fun separateNotificationChannelsDeduplicationPktTimestampsAndFcmRoutingWork() = runBlocking {
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val notifMgr = NexusNotificationManager(ctx)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val sysNm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val highRewardChannel = sysNm.getNotificationChannel(NexusNotificationManager.CHANNEL_ID_HIGH_REWARD)
                assertNotNull("Dedicated 1:5 high-reward channel must exist", highRewardChannel)
                assertEquals(NotificationManager.IMPORTANCE_HIGH, highRewardChannel.importance)
                assertNotNull("1:5 high-reward channel must configure sound", highRewardChannel.sound)

                val qualified14Channel = sysNm.getNotificationChannel(NexusNotificationManager.CHANNEL_ID_QUALIFIED_1_4)
                assertNotNull("Separate 1:4+ qualified channel must exist", qualified14Channel)
                assertEquals(NotificationManager.IMPORTANCE_DEFAULT, qualified14Channel.importance)
            }

            val triggerMs = 1795521600000L // 05:00:00 PM PKT
            val opp15 = buildSampleOpportunity(
                signatureKey = "hr_BTCUSDT_LONG_${triggerMs}_1_5",
                netRR = 5.45,
                tier = OpportunityPriorityTier.HIGHEST_1_TO_5_PLUS,
                triggerMs = triggerMs
            )
            val opp14 = buildSampleOpportunity(
                signatureKey = "hr_SOLUSDT_LONG_${triggerMs}_1_4",
                netRR = 4.35,
                tier = OpportunityPriorityTier.QUALIFIED_1_TO_4,
                triggerMs = triggerMs,
                symbol = "SOLUSDT",
                baseAsset = "SOL"
            )

            assertTrue(opp15.entryTriggerTimestampPkt.contains("PKT"))
            assertTrue(opp15.entryTriggerTimestampPkt.contains("05:00:00 PM"))
            assertTrue(opp15.romanUrduExplanation.whereOneDollarRiskComesFrom.contains("$1"))
            assertTrue(opp15.romanUrduExplanation.howFiveDollarRewardCalculated.contains("5.45"))
            assertTrue(opp15.urduScriptExplanation.whySelected.any { it in '\u0600'..'\u06FF' })

            val first15Sent = notifMgr.notifyHighRewardOpportunity(opp15, playSound = true)
            val duplicate15Sent = notifMgr.notifyHighRewardOpportunity(opp15, playSound = true)
            val first14Sent = notifMgr.notifyHighRewardOpportunity(opp14, playSound = true)

            assertTrue("First 1:5 alert must dispatch", first15Sent)
            assertFalse("Duplicate 1:5 alert for same signatureKey must be suppressed", duplicate15Sent)
            assertTrue("Qualified 1:4 alert must dispatch separately", first14Sent)

            val history15 = notifMgr.alertHistory.value.firstOrNull { it.eventId == opp15.signatureKey }
            assertNotNull(history15)
            assertEquals(AlertType.OPPORTUNITY_1_TO_5, history15!!.alertType)
            assertTrue(history15.title.contains("NEXUS 1:5 HIGH REWARD OPPORTUNITY"))
            assertTrue(history15.timestampPkt.contains("PKT"))
            assertFalse(history15.isOrderExecution)

            val history14 = notifMgr.alertHistory.value.firstOrNull { it.eventId == opp14.signatureKey }
            assertNotNull(history14)
            assertEquals(AlertType.OPPORTUNITY_1_TO_4, history14!!.alertType)
            assertTrue(history14.title.contains("NEXUS QUALIFIED 1:4 OPPORTUNITY"))
            assertFalse(history14.title.contains("NEXUS 1:5 HIGH REWARD OPPORTUNITY"))

            // Lifecycle invalidation on SL breach & expiry
            val invalidated = HighRewardOpportunityDetector.evaluateLifecycle(opp15, livePrice = opp15.stopLoss - 5.0, nowMs = triggerMs + 60_000L)
            assertEquals(OpportunityStatus.INVALIDATED, invalidated.status)
            assertFalse(invalidated.isQualified)

            val expired = HighRewardOpportunityDetector.evaluateLifecycle(opp15, livePrice = opp15.entryPrice, nowMs = opp15.expiresAtTimestampMs + 10_000L)
            assertEquals(OpportunityStatus.EXPIRED, expired.status)
            assertFalse(expired.isQualified)

            // Repository sorting priority: >=1:5 first, then 1:4..<5 (use current clock because repository evaluates expiry against System.currentTimeMillis)
            val liveTriggerMs = System.currentTimeMillis()
            val repoOpp15 = buildSampleOpportunity(
                signatureKey = "hr_BTCUSDT_LONG_${liveTriggerMs}_1_5",
                netRR = 5.45,
                tier = OpportunityPriorityTier.HIGHEST_1_TO_5_PLUS,
                triggerMs = liveTriggerMs
            )
            val repoOpp14 = buildSampleOpportunity(
                signatureKey = "hr_SOLUSDT_LONG_${liveTriggerMs}_1_4",
                netRR = 4.35,
                tier = OpportunityPriorityTier.QUALIFIED_1_TO_4,
                triggerMs = liveTriggerMs,
                symbol = "SOLUSDT",
                baseAsset = "SOL"
            )
            val repo = FuturesScannerRepository(ctx, notifMgr)
            repo.onHighRewardScanEvaluated("SOLUSDT", HighRewardScanResult(listOf(repoOpp14), diagnosticFor(repoOpp14)))
            repo.onHighRewardScanEvaluated("BTCUSDT", HighRewardScanResult(listOf(repoOpp15), diagnosticFor(repoOpp15)))

            val active = repo.highRewardState.value.activeOpportunities
            assertEquals(2, active.size)
            assertEquals("BTCUSDT", active[0].symbol)
            assertEquals(OpportunityPriorityTier.HIGHEST_1_TO_5_PLUS, active[0].tier)

            // Verify FCM payload parsing & deep-link Intent routing for BOTH 1:5 and 1:4 tiers
            val fcmPayload15 = NexusFirebaseMessagingService.parsePayload(
                data = mapOf(
                    "title" to "NEXUS 1:5 HIGH REWARD OPPORTUNITY",
                    "body" to "BTCUSDT LONG triggered at PKT",
                    "symbol" to "BTCUSDT",
                    "alert_type" to "OPPORTUNITY_1_TO_5",
                    "event_id" to "fcm_hr_15"
                )
            )
            assertNotNull(fcmPayload15)
            assertEquals(AlertType.OPPORTUNITY_1_TO_5, fcmPayload15!!.alertType)
            assertTrue(fcmPayload15.openWorkflow)

            val fcmPayload14 = NexusFirebaseMessagingService.parsePayload(
                data = mapOf(
                    "title" to "NEXUS 1:4.35 Qualified Opportunity",
                    "body" to "SOLUSDT LONG triggered at PKT",
                    "symbol" to "SOLUSDT",
                    "alert_type" to "OPPORTUNITY_1_TO_4",
                    "event_id" to "fcm_hr_14"
                )
            )
            assertNotNull(fcmPayload14)
            assertEquals(AlertType.OPPORTUNITY_1_TO_4, fcmPayload14!!.alertType)
            assertTrue(fcmPayload14.openWorkflow)

            val deepLink = notifMgr.buildDeepLinkIntent(
                targetContext = ctx,
                symbol = "BTCUSDT",
                signalId = opp15.id,
                openWorkflow = true,
                alertType = AlertType.OPPORTUNITY_1_TO_5
            )
            assertEquals("BTCUSDT", deepLink.getStringExtra(NexusNotificationManager.EXTRA_SIGNAL_SYMBOL))
            assertEquals(AlertType.OPPORTUNITY_1_TO_5.name, deepLink.getStringExtra(NexusNotificationManager.EXTRA_ALERT_TYPE))
            assertTrue(deepLink.getBooleanExtra(NexusNotificationManager.EXTRA_OPEN_WORKFLOW, false))
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    private fun buildSampleOpportunity(
        signatureKey: String,
        netRR: Double,
        tier: OpportunityPriorityTier,
        triggerMs: Long,
        symbol: String = "BTCUSDT",
        baseAsset: String = "BTC"
    ): HighRewardOpportunity {
        val pkt = TimeZoneUtils.formatKarachiTimestamp(triggerMs)
        return HighRewardOpportunity(
            signatureKey = signatureKey,
            symbol = symbol,
            baseAsset = baseAsset,
            direction = FuturesDirection.LONG,
            tier = tier,
            status = OpportunityStatus.ACTIVE,
            isQualified = true,
            entryTriggerTimestampMs = triggerMs,
            entryTriggerTimestampPkt = pkt,
            detectedAtTimestampMs = triggerMs,
            detectedAtPkt = pkt,
            expiresAtTimestampMs = triggerMs + 3 * 3_600_000L,
            expiresAtPkt = TimeZoneUtils.formatKarachiTimestamp(triggerMs + 3 * 3_600_000L),
            currentPrice = 100_000.0,
            entryPrice = 100_000.0,
            stopLoss = 99_500.0,
            takeProfit = 103_000.0,
            slDistancePercent = 0.5,
            tpDistancePercent = 3.0,
            grossRiskRewardRatio = 6.0,
            netRiskRewardRatio = netRR,
            normalizedRiskUsd = 1.0,
            normalizedNetRewardUsd = netRR,
            budgetMarginUsdt = 100.0,
            leverageAssumed = 5,
            notionalUsdt = 500.0,
            positionSizeUnits = 0.005,
            estimatedGrossProfitUsdt = 15.0,
            estimatedFeesUsdt = 0.5,
            estimatedSlippageUsdt = 0.3,
            estimatedFundingReserveUsdt = 0.05,
            estimatedNetProfitUsdt = 14.15,
            estimatedNetLossUsdt = 2.60,
            estimatedNetRoiPercent = 14.15,
            estimatedRiskMarginPercent = 2.60,
            feeRatePercentPerSide = 0.05,
            slippagePercentPerSide = 0.03,
            modelConfidencePercent = 82,
            historicalTargetHitProbabilityPercent = null,
            historicalSampleSize = 2,
            historicalProbabilityNote = "Insufficient real historical sample (2 found).",
            qualificationReasons = listOf("Net R:R >= 1:5 after fees & slippage"),
            supportingIndicatorsAndSmc = listOf("1H Bullish EMA alignment", "15M Bullish CHoCH"),
            risksAndInvalidations = listOf("Close below $99,500 cancels setup"),
            romanUrduExplanation = HighRewardUrduExplanation(
                whySelected = "$symbol par confirmed trigger aur 1:$netRR net R:R mila.",
                whereOneDollarRiskComesFrom = "Har $1 risk SL aur fees/slippage se aata hai.",
                howFiveDollarRewardCalculated = "TP hit hone par $1 ke muqablay $$netRR net reward banta hai.",
                supportingSignals = "EMA50, SMC CHoCH aur FVG.",
                cancellationConditions = "SL break hone par setup cancel."
            ),
            urduScriptExplanation = HighRewardUrduExplanation(
                whySelected = "$symbol پر مضبوط 1:$netRR موقع ملا ہے۔",
                whereOneDollarRiskComesFrom = "ہر $1 رِسک اسٹاپ لاس اور فیس سے بنتا ہے۔",
                howFiveDollarRewardCalculated = "ٹارگٹ پر $$netRR خالص منافع متوقع ہے۔",
                supportingSignals = "اسٹرکچر اور انڈیکیٹرز کی تصدیق۔",
                cancellationConditions = "اسٹاپ لاس ٹوٹنے پر سیٹ اپ منسوخ۔"
            )
        )
    }

    private fun diagnosticFor(opp: HighRewardOpportunity) = com.example.data.scanner.HighRewardCandidateDiagnostic(
        symbol = opp.symbol,
        bestDirection = opp.direction,
        grossRiskRewardRatio = opp.grossRiskRewardRatio,
        netRiskRewardRatio = opp.netRiskRewardRatio,
        tier = opp.tier,
        reason = opp.alertBannerTitle
    )

    private fun buildBullishHtfPullbackWithSupplyAbove(
        count: Int,
        barMs: Long,
        nowMs: Long,
        priorPeak: Double,
        pullbackBottom: Double,
        currentPrice: Double
    ): List<Candle> {
        val list = mutableListOf<Candle>()
        val startBase = currentPrice * 0.945 // EMA50/200 sits well below currentPrice (bullish HTF trend)
        val spikeIdx = (count * 2) / 3
        for (i in 0 until count) {
            val openT = nowMs - ((count - i).toLong() * barMs) - barMs
            val closeT = openT + barMs - 1L
            val price = when {
                i < spikeIdx -> startBase + ((currentPrice * 0.995 - startBase) * i / maxOf(1, spikeIdx - 1))
                i == spikeIdx -> priorPeak - 150.0
                i < count - 3 -> pullbackBottom + ((currentPrice - pullbackBottom) * (i - spikeIdx) / maxOf(1, count - 3 - spikeIdx))
                else -> currentPrice - (count - 1 - i) * 40.0
            }
            val barHigh = if (i == spikeIdx) priorPeak else price + (currentPrice * 0.004)
            val barLow = price - (currentPrice * 0.004)
            list.add(
                Candle(
                    openTime = openT,
                    open = price - 35.0,
                    high = barHigh,
                    low = barLow,
                    close = price,
                    volume = 1200.0 + (i % 5) * 150.0,
                    closeTime = closeT
                )
            )
        }
        return list
    }

    private fun buildLongBreakout15m(
        count: Int,
        nowMs: Long,
        barMs: Long = 900_000L,
        supportFloor: Double,
        breakoutClose: Double,
        atrStep: Double
    ): List<Candle> {
        val list = mutableListOf<Candle>()
        val startBase = breakoutClose * 0.986
        val waveOffsets = doubleArrayOf(-160.0, -60.0, 70.0, 190.0, 90.0, -30.0, -140.0, -180.0)
        for (i in 0 until count) {
            val openT = nowMs - ((count - i).toLong() * barMs) - barMs
            val closeT = openT + barMs - 1L
            val isLast = i == count - 1
            val isDip = i in (count - 8)..(count - 4)
            val baseClose = when {
                isLast -> breakoutClose
                isDip -> supportFloor + 70.0 + (i - (count - 8)) * 12.0
                i == count - 3 -> breakoutClose - 260.0
                i == count - 2 -> breakoutClose - 190.0
                else -> {
                    val trend = startBase + ((breakoutClose - 280.0 - startBase) * i / maxOf(1, count - 9))
                    trend + waveOffsets[i % waveOffsets.size]
                }
            }
            val lowPrice = if (isDip) supportFloor else baseClose - atrStep * 0.45
            val highPrice = if (isLast) breakoutClose + 40.0 else baseClose + atrStep * 0.45
            list.add(
                Candle(
                    openTime = openT,
                    open = if (isLast) breakoutClose - 180.0 else baseClose - 30.0,
                    high = highPrice,
                    low = lowPrice,
                    close = baseClose,
                    volume = if (isLast) 3200.0 else 1100.0,
                    closeTime = closeT
                )
            )
        }
        return list
    }

    private fun buildBearishHtfBounceWithLiquidityBelow(
        count: Int,
        barMs: Long,
        nowMs: Long,
        priorBottom: Double,
        bounceTop: Double,
        currentPrice: Double
    ): List<Candle> {
        val list = mutableListOf<Candle>()
        val startBase = currentPrice * 1.055 // EMA50/200 sits above currentPrice (bearish HTF trend)
        val flushIdx = (count * 2) / 3
        for (i in 0 until count) {
            val openT = nowMs - ((count - i).toLong() * barMs) - barMs
            val closeT = openT + barMs - 1L
            val price = when {
                i < flushIdx -> startBase - ((startBase - currentPrice * 1.004) * i / maxOf(1, flushIdx - 1))
                i == flushIdx -> priorBottom + 4.0
                i < count - 3 -> bounceTop - ((bounceTop - currentPrice) * (i - flushIdx) / maxOf(1, count - 3 - flushIdx))
                else -> currentPrice + (count - 1 - i) * 2.0
            }
            val barLow = if (i == flushIdx) priorBottom else price - (currentPrice * 0.004)
            val barHigh = price + (currentPrice * 0.004)
            list.add(
                Candle(
                    openTime = openT,
                    open = price + 2.0,
                    high = barHigh,
                    low = barLow,
                    close = price,
                    volume = 1200.0 + (i % 4) * 100.0,
                    closeTime = closeT
                )
            )
        }
        return list
    }

    private fun buildShortBreakdown15m(
        count: Int,
        nowMs: Long,
        barMs: Long = 900_000L,
        resistanceCeiling: Double,
        breakdownClose: Double
    ): List<Candle> {
        val list = mutableListOf<Candle>()
        val startBase = breakdownClose * 1.025
        for (i in 0 until count) {
            val openT = nowMs - ((count - i).toLong() * barMs) - barMs
            val closeT = openT + barMs - 1L
            val isLast = i == count - 1
            val isBounce = i in (count - 8)..(count - 4)
            val baseClose = when {
                isLast -> breakdownClose
                isBounce -> resistanceCeiling - 3.0
                else -> startBase - ((startBase - (breakdownClose + 8.0)) * i / maxOf(1, count - 9)) - (i % 3) * 1.0
            }
            list.add(
                Candle(
                    openTime = openT,
                    open = if (isLast) breakdownClose + 8.0 else baseClose + 1.5,
                    high = if (isBounce) resistanceCeiling else baseClose + 3.5,
                    low = if (isLast) breakdownClose - 1.5 else baseClose - 3.0,
                    close = baseClose,
                    volume = if (isLast) 3000.0 else 1000.0,
                    closeTime = closeT
                )
            )
        }
        return list
    }
}
