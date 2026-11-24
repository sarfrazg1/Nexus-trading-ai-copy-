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
import com.example.data.scanner.HighRewardOpportunity
import com.example.data.scanner.HighRewardOpportunityDetector
import com.example.data.scanner.HighRewardUrduExplanation
import com.example.data.scanner.OpportunityPriorityTier
import com.example.data.scanner.OpportunityStatus
import com.example.util.NexusNotificationManager
import com.example.util.TimeZoneUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MasterUpgradePart5Test {

    @Test
    fun missingStaleOrUnreliableFundingReturnsWaitAndNeverAssumesZero() = runBlocking {
        val nowMs = 1795521600000L
        val candles4h = buildBullishHtfPullback(30, 4 * 3_600_000L, nowMs, 106_000.0, 99_800.0, 100_000.0)
        val candles1h = buildBullishHtfPullback(50, 3_600_000L, nowMs, 105_500.0, 99_700.0, 100_000.0)
        val candles15m = buildLongBreakout15m(50, nowMs, supportFloor = 99_600.0, breakoutClose = 100_050.0, atrStep = 140.0)
        val candles5m = buildLongBreakout15m(30, nowMs, barMs = 300_000L, supportFloor = 99_650.0, breakoutClose = 100_050.0, atrStep = 80.0)

        // 1. Null (missing) funding rate MUST NOT be treated as 0.0%
        val missingRes = HighRewardOpportunityDetector.scanPair(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            currentPrice = 100_050.0,
            candles4h = candles4h,
            candles1h = candles1h,
            candles15m = candles15m,
            candles5m = candles5m,
            fundingRatePercent = null,
            referenceTimeMs = nowMs
        )
        assertTrue("Missing funding must never qualify any opportunity", missingRes.qualifiedOpportunities.isEmpty())
        assertEquals(OpportunityPriorityTier.WAIT_FUNDING_UNAVAILABLE, missingRes.bestDiagnostic.tier)
        assertFalse(missingRes.bestDiagnostic.isFundingAvailable)
        assertTrue(missingRes.bestDiagnostic.reason.contains("WAIT / FUNDING DATA UNAVAILABLE"))
        assertTrue("Gross R:R should still be reported transparently", missingRes.bestDiagnostic.grossRiskRewardRatio > 0.0)
        assertEquals(0.0, missingRes.bestDiagnostic.netRiskRewardRatio, 1e-9)

        // 2. NaN / Out-of-bounds funding MUST fail safely
        val nanRes = HighRewardOpportunityDetector.scanPair(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            currentPrice = 100_050.0,
            candles4h = candles4h,
            candles1h = candles1h,
            candles15m = candles15m,
            candles5m = candles5m,
            fundingRatePercent = Double.NaN,
            referenceTimeMs = nowMs
        )
        assertTrue(nanRes.qualifiedOpportunities.isEmpty())
        assertEquals(OpportunityPriorityTier.WAIT_FUNDING_UNAVAILABLE, nanRes.bestDiagnostic.tier)

        val extremeRes = HighRewardOpportunityDetector.scanPair(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            currentPrice = 100_050.0,
            candles4h = candles4h,
            candles1h = candles1h,
            candles15m = candles15m,
            candles5m = candles5m,
            fundingRatePercent = 9.2,
            referenceTimeMs = nowMs
        )
        assertTrue(extremeRes.qualifiedOpportunities.isEmpty())
        assertEquals(OpportunityPriorityTier.WAIT_FUNDING_UNAVAILABLE, extremeRes.bestDiagnostic.tier)

        // 3. Stale funding timestamp (>10m old) or explicit stale flag MUST fail safely
        val staleTsRes = HighRewardOpportunityDetector.scanPair(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            currentPrice = 100_050.0,
            candles4h = candles4h,
            candles1h = candles1h,
            candles15m = candles15m,
            candles5m = candles5m,
            fundingRatePercent = 0.01,
            fundingDataTimestampMs = nowMs - (15 * 60_000L),
            referenceTimeMs = nowMs
        )
        assertTrue(staleTsRes.qualifiedOpportunities.isEmpty())
        assertEquals(OpportunityPriorityTier.WAIT_FUNDING_UNAVAILABLE, staleTsRes.bestDiagnostic.tier)

        val staleFlagRes = HighRewardOpportunityDetector.scanPair(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            currentPrice = 100_050.0,
            candles4h = candles4h,
            candles1h = candles1h,
            candles15m = candles15m,
            candles5m = candles5m,
            fundingRatePercent = 0.01,
            isFundingDataStale = true,
            referenceTimeMs = nowMs
        )
        assertTrue(staleFlagRes.qualifiedOpportunities.isEmpty())
        assertEquals(OpportunityPriorityTier.WAIT_FUNDING_UNAVAILABLE, staleFlagRes.bestDiagnostic.tier)
    }

    @Test
    fun feesSlippageAndAdverseFundingAccuratelySeparateGrossFromNetAndQualifyOneToFourVsOneToFive() = runBlocking {
        val nowMs = 1795521600000L
        val candles15m = buildLongBreakout15m(50, nowMs, supportFloor = 99_600.0, breakoutClose = 100_050.0, atrStep = 140.0)
        val candles5m = buildLongBreakout15m(30, nowMs, barMs = 300_000L, supportFloor = 99_650.0, breakoutClose = 100_050.0, atrStep = 80.0)

        // Borderline setup where Gross R:R is ~5.6, so normal costs yield 1:4..1:4.99 (QUALIFIED_1_TO_4),
        // and higher slippage/adverse funding pushes Net R:R < 4.0 -> rejected WITHOUT moving SL or TP!
        val peak14Level = 103_600.0
        val candles4h14 = buildBullishHtfPullback(30, 4 * 3_600_000L, nowMs, peak14Level, 99_800.0, 100_000.0)
        val candles1h14 = buildBullishHtfPullback(50, 3_600_000L, nowMs, peak14Level - 50.0, 99_700.0, 100_000.0)

        val qual14Res = HighRewardOpportunityDetector.scanPair(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            currentPrice = 100_050.0,
            candles4h = candles4h14,
            candles1h = candles1h14,
            candles15m = candles15m,
            candles5m = candles5m,
            fundingRatePercent = 0.01,
            fundingDataTimestampMs = nowMs - 30_000L,
            budgetConfig = BudgetConfig(feeRatePercentPerSide = 0.05, slippagePercentPerSide = 0.03),
            referenceTimeMs = nowMs
        )
        assertTrue(
            "Expected 1:4 qualified LONG setup (diag=${qual14Res.bestDiagnostic.reason})",
            qual14Res.qualifiedOpportunities.isNotEmpty()
        )
        val opp14 = qual14Res.qualifiedOpportunities.first()
        assertEquals(OpportunityPriorityTier.QUALIFIED_1_TO_4, opp14.tier)
        assertTrue(opp14.netRiskRewardRatio in 4.0..4.9999)
        assertTrue("Gross R:R must exceed Net R:R after costs", opp14.grossRiskRewardRatio > opp14.netRiskRewardRatio)
        assertEquals(0.01, opp14.fundingRatePercent, 1e-6)
        assertTrue(opp14.alertBannerTitle.contains("NEXUS QUALIFIED 1:4 OPPORTUNITY"))

        // Same structural candles under heavy adverse funding + slippage: Net R:R drops < 4.0 -> REJECTED, SL/TP untouched
        val heavyCostRes = HighRewardOpportunityDetector.scanPair(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            currentPrice = 100_050.0,
            candles4h = candles4h14,
            candles1h = candles1h14,
            candles15m = candles15m,
            candles5m = candles5m,
            fundingRatePercent = 0.45, // heavy positive funding against LONG
            fundingDataTimestampMs = nowMs - 30_000L,
            budgetConfig = BudgetConfig(feeRatePercentPerSide = 0.08, slippagePercentPerSide = 0.06),
            referenceTimeMs = nowMs
        )
        assertTrue("Heavy fees/slippage/adverse funding must disqualify borderline <1:4 net R:R", heavyCostRes.qualifiedOpportunities.isEmpty())
        assertEquals(OpportunityPriorityTier.WAIT_BELOW_1_TO_4, heavyCostRes.bestDiagnostic.tier)
        assertEquals(opp14.grossRiskRewardRatio, heavyCostRes.bestDiagnostic.grossRiskRewardRatio, 1e-4)

        // Higher genuine structural target yields >= 1:5 Net R:R (HIGHEST_1_TO_5_PLUS)
        val qual15Res = HighRewardOpportunityDetector.scanPair(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            currentPrice = 100_050.0,
            candles4h = buildBullishHtfPullback(30, 4 * 3_600_000L, nowMs, 106_000.0, 99_800.0, 100_000.0),
            candles1h = buildBullishHtfPullback(50, 3_600_000L, nowMs, 105_500.0, 99_700.0, 100_000.0),
            candles15m = candles15m,
            candles5m = candles5m,
            fundingRatePercent = 0.05,
            fundingDataTimestampMs = nowMs - 15_000L,
            referenceTimeMs = nowMs
        )
        assertTrue(qual15Res.qualifiedOpportunities.isNotEmpty())
        val opp15 = qual15Res.qualifiedOpportunities.first()
        assertEquals(OpportunityPriorityTier.HIGHEST_1_TO_5_PLUS, opp15.tier)
        assertTrue(opp15.netRiskRewardRatio >= 5.0)
        assertTrue(opp15.alertBannerTitle.contains("NEXUS 1:5 HIGH REWARD OPPORTUNITY"))

        // SHORT with positive funding does NOT credit unearned funding rebates (reserve = 0.0),
        // while SHORT with negative funding reserves adverse funding cost
        val candles4hShort = buildBearishHtfBounce(30, 4 * 3_600_000L, nowMs, 2_820.0, 3_018.0, 3_000.0)
        val candles1hShort = buildBearishHtfBounce(50, 3_600_000L, nowMs, 2_840.0, 3_016.0, 3_000.0)
        val candles15mShort = buildShortBreakdown15m(50, nowMs, resistanceCeiling = 3_012.0, breakdownClose = 2_996.0)

        val shortPosFunding = HighRewardOpportunityDetector.scanPair(
            symbol = "ETHUSDT",
            baseAsset = "ETH",
            currentPrice = 2_996.0,
            candles4h = candles4hShort,
            candles1h = candles1hShort,
            candles15m = candles15mShort,
            candles5m = candles15mShort.takeLast(30),
            fundingRatePercent = 0.05,
            referenceTimeMs = nowMs
        )
        assertTrue(shortPosFunding.qualifiedOpportunities.isNotEmpty())
        assertEquals(0.0, shortPosFunding.qualifiedOpportunities.first().estimatedFundingReserveUsdt, 1e-6)

        val shortNegFunding = HighRewardOpportunityDetector.scanPair(
            symbol = "ETHUSDT",
            baseAsset = "ETH",
            currentPrice = 2_996.0,
            candles4h = candles4hShort,
            candles1h = candles1hShort,
            candles15m = candles15mShort,
            candles5m = candles15mShort.takeLast(30),
            fundingRatePercent = -0.05,
            referenceTimeMs = nowMs
        )
        assertTrue(shortNegFunding.qualifiedOpportunities.isNotEmpty())
        val shortNegOpp = shortNegFunding.qualifiedOpportunities.first()
        assertTrue(shortNegOpp.estimatedFundingReserveUsdt > 0.0)
        assertTrue(
            "Adverse negative funding on SHORT must reduce Net R:R",
            shortNegOpp.netRiskRewardRatio < shortPosFunding.qualifiedOpportunities.first().netRiskRewardRatio
        )
    }

    @Test
    fun notificationSettingsExpiryGatingAndSeparatePushTypesAreEnforced() {
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            val mgr = NexusNotificationManager(ctx)

            val triggerMs = 1795521600000L
            val opp15 = sampleOpp("sig_15_$triggerMs", 5.30, OpportunityPriorityTier.HIGHEST_1_TO_5_PLUS, triggerMs)
            val opp14 = sampleOpp("sig_14_$triggerMs", 4.25, OpportunityPriorityTier.QUALIFIED_1_TO_4, triggerMs)

            // 1. User disables master alerts -> suppressed cleanly
            mgr.setMasterAlertsEnabled(false)
            assertFalse(mgr.notifyHighRewardOpportunity(opp15))

            // 2. Re-enable alerts & disable sound -> alert dispatches, history includes Gross + Net R:R + Funding + PKT
            mgr.setMasterAlertsEnabled(true)
            mgr.setSoundAlertsEnabled(false)
            assertTrue(mgr.notifyHighRewardOpportunity(opp15))
            assertTrue(mgr.notifyHighRewardOpportunity(opp14))

            val hist15 = mgr.alertHistory.value.first { it.eventId == opp15.signatureKey }
            assertTrue(hist15.title.startsWith("NEXUS 1:5 HIGH REWARD OPPORTUNITY"))
            assertTrue(hist15.message.contains("Gross 1:"))
            assertTrue(hist15.message.contains("Fund +0.0300%"))
            assertTrue(hist15.message.contains("PKT"))

            val hist14 = mgr.alertHistory.value.first { it.eventId == opp14.signatureKey }
            assertTrue(hist14.title.startsWith("NEXUS QUALIFIED 1:4 OPPORTUNITY"))
            assertEquals(AlertType.OPPORTUNITY_1_TO_4, hist14.alertType)

            // 3. Remote FCM expired payload is rejected and never notifies
            val expiredPayload = NexusFirebaseMessagingService.parsePayload(
                data = mapOf(
                    "symbol" to "SOLUSDT",
                    "direction" to "LONG",
                    "alert_type" to "OPPORTUNITY_1_TO_5",
                    "entry_price" to "$180.00",
                    "stop_loss" to "$178.00",
                    "take_profit" to "$192.00",
                    "net_rr" to "5.40",
                    "gross_rr" to "6.00",
                    "expires_at_ms" to "${triggerMs - 10_000L}",
                    "event_id" to "fcm_expired_1"
                ),
                sentTimeMs = triggerMs
            )
            assertNotNull(expiredPayload)
            assertTrue(expiredPayload!!.title.contains("NEXUS 1:5 HIGH REWARD OPPORTUNITY"))
            val dispatchedExpired = mgr.notifyTradeLifecycleAlert(
                eventId = expiredPayload.eventId,
                symbol = expiredPayload.symbol,
                alertType = expiredPayload.alertType,
                title = expiredPayload.title,
                body = expiredPayload.body,
                timestampMs = expiredPayload.timestampMs,
                expiresAtMs = expiredPayload.expiresAtMs,
                nowMs = triggerMs
            )
            assertFalse("Expired FCM opportunity must never notify", dispatchedExpired)

            // 4. Separate notification channels exist on API 26+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val sysNm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                assertNotNull(sysNm.getNotificationChannel(NexusNotificationManager.CHANNEL_ID_HIGH_REWARD))
                assertNotNull(sysNm.getNotificationChannel(NexusNotificationManager.CHANNEL_ID_QUALIFIED_1_4))
            }
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    private fun sampleOpp(
        key: String,
        netRr: Double,
        tier: OpportunityPriorityTier,
        triggerMs: Long
    ): HighRewardOpportunity {
        val pkt = TimeZoneUtils.formatKarachiTimestamp(triggerMs)
        return HighRewardOpportunity(
            signatureKey = key,
            symbol = "BTCUSDT",
            baseAsset = "BTC",
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
            netRiskRewardRatio = netRr,
            normalizedRiskUsd = 1.0,
            normalizedNetRewardUsd = netRr,
            budgetMarginUsdt = 100.0,
            leverageAssumed = 5,
            notionalUsdt = 500.0,
            positionSizeUnits = 0.005,
            estimatedGrossProfitUsdt = 15.0,
            estimatedFeesUsdt = 0.5,
            estimatedSlippageUsdt = 0.3,
            estimatedFundingReserveUsdt = 0.15,
            estimatedNetProfitUsdt = 14.05,
            estimatedNetLossUsdt = 2.65,
            estimatedNetRoiPercent = 14.05,
            estimatedRiskMarginPercent = 2.65,
            feeRatePercentPerSide = 0.05,
            slippagePercentPerSide = 0.03,
            fundingRatePercent = 0.03,
            modelConfidencePercent = 84,
            historicalTargetHitProbabilityPercent = null,
            historicalSampleSize = 2,
            historicalProbabilityNote = "Insufficient sample",
            qualificationReasons = listOf("Net R:R >= 1:4 after fees, slippage & funding"),
            supportingIndicatorsAndSmc = listOf("1H Bullish EMA", "15M BOS"),
            risksAndInvalidations = listOf("SL breach cancels setup"),
            romanUrduExplanation = HighRewardUrduExplanation("Why", "Risk", "Reward", "Signals", "Cancel"),
            urduScriptExplanation = HighRewardUrduExplanation("وجہ", "خطرہ", "منافع", "انڈیکیٹرز", "منسوخی")
        )
    }

    private fun buildBullishHtfPullback(
        count: Int,
        barMs: Long,
        nowMs: Long,
        priorPeak: Double,
        pullbackBottom: Double,
        currentPrice: Double
    ): List<Candle> {
        val list = mutableListOf<Candle>()
        val startBase = currentPrice * 0.945
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
            list.add(Candle(openT, price - 35.0, barHigh, barLow, price, 1200.0 + (i % 5) * 150.0, closeT))
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
            list.add(
                Candle(
                    openTime = openT,
                    open = if (isLast) breakoutClose - 180.0 else baseClose - 30.0,
                    high = if (isLast) breakoutClose + 40.0 else baseClose + atrStep * 0.45,
                    low = if (isDip) supportFloor else baseClose - atrStep * 0.45,
                    close = baseClose,
                    volume = if (isLast) 3200.0 else 1100.0,
                    closeTime = closeT
                )
            )
        }
        return list
    }

    private fun buildBearishHtfBounce(
        count: Int,
        barMs: Long,
        nowMs: Long,
        priorBottom: Double,
        bounceTop: Double,
        currentPrice: Double
    ): List<Candle> {
        val list = mutableListOf<Candle>()
        val startBase = currentPrice * 1.055
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
            list.add(
                Candle(
                    openTime = openT,
                    open = price + 2.0,
                    high = price + (currentPrice * 0.004),
                    low = if (i == flushIdx) priorBottom else price - (currentPrice * 0.004),
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
