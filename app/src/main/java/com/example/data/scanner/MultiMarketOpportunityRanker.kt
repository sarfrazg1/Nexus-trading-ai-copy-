package com.example.data.scanner

import com.example.data.model.AssetCategory
import com.example.data.model.MarketDataStatus
import com.example.data.signal.StrictSignalQualityReport
import com.example.util.TimeZoneUtils
import java.util.Locale

data class RankedMultiMarketOpportunity(
    val rank: Int,
    val evaluation: StrictMultiMarketEvaluation,
    val setup: SetupCandidateEvaluation?,
    val qualityReport: StrictSignalQualityReport?,
    val dataStatus: MarketDataStatus,
    val rankingScore: Double,
    val isQualified: Boolean,
    val isPreferredFiveToOne: Boolean,
    val sessionNote: String,
    val rankingSummary: String
)

data class RankedMultiMarketBoard(
    val opportunities: List<RankedMultiMarketOpportunity>,
    val qualifiedCount: Int,
    val preferredFiveToOneCount: Int,
    val unavailableCount: Int,
    val emptyHeadline: String?,
    val emptyReason: String?,
    val generatedAtPkt: String
)

/**
 * Part 3 Multi-Market Opportunity Ranker.
 * Ranks Crypto Futures, Gold/Silver/Oil, and NVDA/GOOG/GOOGL by:
 * - Setup confirmation & quality score
 * - Higher-timeframe (1D/4H/1H) alignment
 * - Fibonacci Golden Zone & S/R confluence
 * - Price Action confirmation
 * - Volume & liquidity / data reliability (LIVE > DELAYED > STALE/UNAVAILABLE)
 * - NET R:R (>=1:5 top priority ahead of 1:4..4.99)
 *
 * Strictly treats Crypto as 24/7 — NEVER rejects valid crypto setups for being outside London/NY hours.
 */
object MultiMarketOpportunityRanker {

    const val EMPTY_BOARD_HEADLINE = "NO STRONG OPPORTUNITY FOUND"

    fun rank(
        evaluations: List<StrictMultiMarketEvaluation>,
        dataStatusesBySymbol: Map<String, MarketDataStatus> = emptyMap(),
        qualityReportsBySymbol: Map<String, StrictSignalQualityReport> = emptyMap(),
        referenceTimeMs: Long = System.currentTimeMillis()
    ): RankedMultiMarketBoard {
        val prelim = evaluations.map { eval ->
            val setup = eval.bestSetup
            val normSym = eval.symbol.uppercase(Locale.US)
            val dataStatus = dataStatusesBySymbol[normSym] ?: when (eval.status) {
                SetupValidationStatus.DATA_UNAVAILABLE -> MarketDataStatus.UNAVAILABLE
                else -> MarketDataStatus.LIVE
            }
            val qualityReport = qualityReportsBySymbol[normSym]

            val netRr = setup?.estimatedNetRiskReward ?: 0.0
            val meetsMinRr = netRr >= StrictMultiMarketSetupEngine.MIN_NET_RR_FOR_CONFIRMATION
            val isFiveToOne = netRr >= StrictMultiMarketSetupEngine.PREFERRED_NET_RR
            val mandatoryFiltersOk = qualityReport?.allMandatoryFiltersPassed ?: true

            val isQualified = eval.status == SetupValidationStatus.CONFIRMED &&
                setup != null &&
                setup.status == SetupValidationStatus.CONFIRMED &&
                meetsMinRr &&
                mandatoryFiltersOk &&
                dataStatus != MarketDataStatus.UNAVAILABLE &&
                dataStatus != MarketDataStatus.STALE

            val reliabilityBonus = when (dataStatus) {
                MarketDataStatus.LIVE -> 15.0
                MarketDataStatus.DELAYED -> 7.0
                MarketDataStatus.STALE -> -25.0
                MarketDataStatus.UNAVAILABLE -> -60.0
            }

            val htfAligned = setup != null &&
                (eval.mtfHierarchy?.htfMacroDirection == null || eval.mtfHierarchy.htfMacroDirection == setup.direction)
            val htfBonus = if (htfAligned) 10.0 else 0.0

            val rrBonus = when {
                isFiveToOne -> 20.0 + ((netRr - 5.0).coerceIn(0.0, 5.0) * 1.5)
                meetsMinRr -> 10.0 + ((netRr - 4.0) * 5.0)
                else -> 0.0
            }

            val effectiveScore = (qualityReport?.totalScore ?: setup?.score ?: 0).toDouble()
            val qualifiedBonus = if (isQualified) 100.0 else 0.0

            val totalRankingScore = when (eval.status) {
                SetupValidationStatus.DATA_UNAVAILABLE -> -100.0
                SetupValidationStatus.INVALIDATED -> -10.0
                else -> qualifiedBonus + effectiveScore + reliabilityBonus + htfBonus + rrBonus
            }

            val sessionNote = when (eval.category) {
                AssetCategory.CRYPTO -> "Crypto 24/7 Market — Active all hours (never session-blocked)"
                AssetCategory.COMMODITIES -> "Commodities Spot/Futures — Session liquidity & spread checked"
                AssetCategory.STOCKS -> "US Equities (NASDAQ) — Regular/extended market data check"
                else -> "${eval.category.label} Feed"
            }

            val summary = when {
                eval.status == SetupValidationStatus.DATA_UNAVAILABLE ->
                    eval.unavailableReason ?: "DATA UNAVAILABLE on ${eval.venueName}"
                isQualified && setup != null ->
                    "${setup.setupType.badgeCode} ${setup.direction.name} • Net R:R 1:${String.format(Locale.US, "%.2f", netRr)} • Score ${effectiveScore.toInt()}/100 • ${eval.venueName}"
                setup != null ->
                    "WATCHING ${setup.direction.name} • Net R:R 1:${String.format(Locale.US, "%.2f", netRr)} • ${setup.rulesFailed.firstOrNull() ?: "Awaiting trigger/1:4 Net R:R"}"
                else ->
                    "No active Type A/B trigger on ${eval.venueName}"
            }

            RankedMultiMarketOpportunity(
                rank = 0,
                evaluation = eval,
                setup = setup,
                qualityReport = qualityReport,
                dataStatus = dataStatus,
                rankingScore = totalRankingScore,
                isQualified = isQualified,
                isPreferredFiveToOne = isQualified && isFiveToOne,
                sessionNote = sessionNote,
                rankingSummary = summary
            )
        }

        val sorted = prelim.sortedWith(
            compareByDescending<RankedMultiMarketOpportunity> { it.isQualified }
                .thenByDescending { it.isPreferredFiveToOne }
                .thenByDescending { it.rankingScore }
                .thenByDescending { it.setup?.estimatedNetRiskReward ?: 0.0 }
        ).mapIndexed { index, item -> item.copy(rank = index + 1) }

        val qualifiedCount = sorted.count { it.isQualified }
        val preferredCount = sorted.count { it.isPreferredFiveToOne }
        val unavailableCount = sorted.count { it.evaluation.status == SetupValidationStatus.DATA_UNAVAILABLE }

        val emptyHeadline = if (qualifiedCount == 0) EMPTY_BOARD_HEADLINE else null
        val emptyReason = if (qualifiedCount == 0) {
            val topWatching = sorted.firstOrNull { it.setup != null }
            if (topWatching?.setup != null) {
                "Top candidate ${topWatching.evaluation.displaySymbol} (${topWatching.setup.direction.name}) scored ${topWatching.setup.score}/100 with Net R:R 1:${String.format(Locale.US, "%.2f", topWatching.setup.estimatedNetRiskReward)} — ${topWatching.setup.rulesFailed.firstOrNull() ?: "Minimum Net 1:4.0 + 15M close confirmation required"}."
            } else {
                "None of the ${sorted.size} scanned instruments currently meet strict MTF + Golden Zone + Price Action + Net 1:4.0 criteria."
            }
        } else null

        return RankedMultiMarketBoard(
            opportunities = sorted,
            qualifiedCount = qualifiedCount,
            preferredFiveToOneCount = preferredCount,
            unavailableCount = unavailableCount,
            emptyHeadline = emptyHeadline,
            emptyReason = emptyReason,
            generatedAtPkt = TimeZoneUtils.formatKarachiTimestamp(referenceTimeMs)
        )
    }
}
