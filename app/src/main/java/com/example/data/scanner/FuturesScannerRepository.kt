package com.example.data.scanner

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.alerts.AlertMonitorEngine
import com.example.data.alerts.AlertRuleCategory
import com.example.data.alerts.TradeAlertRule
import com.example.data.broker.ExecutionMode
import com.example.data.broker.PaperOrderRecord
import com.example.data.broker.PaperOrderStatus
import com.example.data.risk.BudgetConfig
import com.example.data.risk.DailyRiskLedger
import com.example.data.risk.RiskCurrency
import com.example.util.NexusNotificationManager
import com.example.util.TimeZoneUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository managing:
 * 1. User-selected watchlist pairs to scan.
 * 2. Active signals, developing setups, and historical outcomes.
 * 3. Real-time signal lifecycle state machine (Price vs TP1/TP2/TP3/SL).
 * 4. Paper trading execution & statistics.
 * 5. Cooldown rules to prevent repeat duplicate notifications.
 */
class FuturesScannerRepository(
    context: Context? = null,
    private val notificationManager: NexusNotificationManager? = null
) {
    private var appContext: Context? = context?.applicationContext ?: context

    private val prefs: SharedPreferences?
        get() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun bindContext(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext ?: context
            loadWatchlistFromStorage()
            loadSignalHistoryFromStorage()
            loadScannerPreferencesFromStorage()
            loadBudgetConfigFromStorage()
            loadPaperOrdersFromStorage()
            loadDailyLedgerFromStorage()
            loadTradeAlertRulesFromStorage()
        }
    }

    // Default top USDT-M Futures pairs
    private val defaultPairs = listOf(
        FuturesWatchlistPair("BTCUSDT", "BTC", "Bitcoin Futures", isSelectedForScanning = true),
        FuturesWatchlistPair("ETHUSDT", "ETH", "Ethereum Futures", isSelectedForScanning = true),
        FuturesWatchlistPair("SOLUSDT", "SOL", "Solana Futures", isSelectedForScanning = true),
        FuturesWatchlistPair("BNBUSDT", "BNB", "BNB Futures", isSelectedForScanning = true),
        FuturesWatchlistPair("XRPUSDT", "XRP", "XRP Futures", isSelectedForScanning = true),
        FuturesWatchlistPair("DOGEUSDT", "DOGE", "Dogecoin Futures", isSelectedForScanning = true),
        FuturesWatchlistPair("ADAUSDT", "ADA", "Cardano Futures", isSelectedForScanning = true),
        FuturesWatchlistPair("AVAXUSDT", "AVAX", "Avalanche Futures", isSelectedForScanning = true),
        FuturesWatchlistPair("LINKUSDT", "LINK", "Chainlink Futures", isSelectedForScanning = true),
        FuturesWatchlistPair("SUIUSDT", "SUI", "Sui Futures", isSelectedForScanning = true),
        FuturesWatchlistPair("NEARUSDT", "NEAR", "Near Protocol Futures", isSelectedForScanning = false),
        FuturesWatchlistPair("APTUSDT", "APT", "Aptos Futures", isSelectedForScanning = false)
    )

    private val _watchlist = MutableStateFlow<List<FuturesWatchlistPair>>(defaultPairs)
    val watchlist: StateFlow<List<FuturesWatchlistPair>> = _watchlist.asStateFlow()

    private val _scannerState = MutableStateFlow(ScannerEngineState())
    val scannerState: StateFlow<ScannerEngineState> = _scannerState.asStateFlow()

    private val _budgetConfig = MutableStateFlow(BudgetConfig())
    val budgetConfig: StateFlow<BudgetConfig> = _budgetConfig.asStateFlow()

    private val _dailyRiskLedger = MutableStateFlow(DailyRiskLedger())
    val dailyRiskLedger: StateFlow<DailyRiskLedger> = _dailyRiskLedger.asStateFlow()

    private val _paperOrders = MutableStateFlow<List<PaperOrderRecord>>(emptyList())
    val paperOrders: StateFlow<List<PaperOrderRecord>> = _paperOrders.asStateFlow()

    private val _tradeAlertRules = MutableStateFlow<List<TradeAlertRule>>(emptyList())
    val tradeAlertRules: StateFlow<List<TradeAlertRule>> = _tradeAlertRules.asStateFlow()

    private val _highRewardState = MutableStateFlow(HighRewardScanState())
    val highRewardState: StateFlow<HighRewardScanState> = _highRewardState.asStateFlow()

    val signalStateManager = ActiveSignalStateManager()

    private val _managedSignals = MutableStateFlow<List<ManagedSignalEntry>>(emptyList())
    val managedSignals: StateFlow<List<ManagedSignalEntry>> = _managedSignals.asStateFlow()

    private val _signalTransitionLog = MutableStateFlow<List<SignalTransitionRecord>>(emptyList())
    val signalTransitionLog: StateFlow<List<SignalTransitionRecord>> = _signalTransitionLog.asStateFlow()

    // Alert cooldown per symbol (45 minutes = 2,700,000 ms)
    private val alertCooldowns = ConcurrentHashMap<String, Long>()
    private val COOLDOWN_DURATION_MS = 45 * 60 * 1000L

    init {
        loadWatchlistFromStorage()
        loadSignalHistoryFromStorage()
        loadScannerPreferencesFromStorage()
        loadBudgetConfigFromStorage()
        loadPaperOrdersFromStorage()
        loadDailyLedgerFromStorage()
        loadTradeAlertRulesFromStorage()
    }

    // ==========================================
    // WATCHLIST MANAGEMENT
    // ==========================================

    fun togglePairScanning(symbol: String) {
        _watchlist.update { currentList ->
            currentList.map { pair ->
                if (pair.symbol.equals(symbol, ignoreCase = true)) {
                    pair.copy(isSelectedForScanning = !pair.isSelectedForScanning)
                } else pair
            }
        }
        saveWatchlistToStorage()
        updateSelectedWatchlistCount()
    }

    fun addCustomPair(symbolInput: String) {
        val clean = symbolInput.uppercase().trim()
        val symbol = if (clean.endsWith("USDT")) clean else "${clean}USDT"
        val base = symbol.removeSuffix("USDT")

        if (_watchlist.value.none { it.symbol == symbol }) {
            val newPair = FuturesWatchlistPair(
                symbol = symbol,
                baseAsset = base,
                displayName = "$base Futures",
                isSelectedForScanning = true
            )
            _watchlist.update { it + newPair }
            saveWatchlistToStorage()
            updateSelectedWatchlistCount()
        }
    }

    fun removePair(symbol: String) {
        _watchlist.update { current ->
            current.filterNot { it.symbol.equals(symbol, ignoreCase = true) }
        }
        saveWatchlistToStorage()
        updateSelectedWatchlistCount()
    }

    fun getSelectedPairs(): List<FuturesWatchlistPair> {
        return _watchlist.value.filter { it.isSelectedForScanning }
    }

    private fun updateSelectedWatchlistCount() {
        _scannerState.update { state ->
            state.copy(selectedWatchlistCount = _watchlist.value.count { it.isSelectedForScanning })
        }
    }

    // ==========================================
    // LIVE SIGNAL INTAKE & STATE MACHINE
    // ==========================================

    /**
     * Ingests a new evaluation from BinanceFuturesScannerEngine or strict scanner,
     * routed through ActiveSignalStateManager to prevent silent LONG<->SHORT flips,
     * enforce deterministic IDs, log state transitions, and prevent duplicate alerts.
     */
    fun onSignalEvaluated(signal: FuturesScanSignal): SignalIntakeOutcome {
        val now = System.currentTimeMillis()
        val isConfirmed = signal.lifecycle == FuturesSignalLifecycle.ENTRY_CONFIRMED ||
            signal.lifecycle == FuturesSignalLifecycle.ACTIVE

        val intake = signalStateManager.submitCandidate(
            symbol = signal.symbol,
            direction = signal.direction,
            isConfirmed = isConfirmed,
            entryPrice = signal.entryPrice,
            stopLoss = signal.stopLoss,
            takeProfit1 = signal.takeProfit1,
            takeProfit2 = signal.takeProfit2,
            netRiskReward = signal.riskRewardRatio,
            qualityScore = signal.qualityScore,
            dataSourceName = "Binance/Futures Multi-TF",
            setupReason = signal.reasonsForEntry.firstOrNull() ?: signal.detectedPattern.label,
            confirmationCandleCloseMs = signal.entryTimer?.triggerTimestampMs ?: signal.timestamp,
            expiresAtMs = signal.exitPlan?.let {
                signal.timestamp + (it.setupExpiryBars.coerceAtLeast(1) * signal.primaryTimeframe.durationMillis)
            } ?: (now + 4 * 3_600_000L),
            nowMs = now,
            preferredSignalId = signal.id
        )
        syncManagedStateFlows()

        if (!intake.accepted) {
            return intake
        }

        if (signal.lifecycle == FuturesSignalLifecycle.ENTRY_CONFIRMED) {
            // Check if we already have an active signal for this symbol
            val existingActive = _scannerState.value.activeSignals.firstOrNull { it.symbol == signal.symbol }
            if (existingActive != null) {
                return intake
            }

            // Check alert cooldown
            val lastAlertTime = alertCooldowns[signal.symbol] ?: 0L
            val canAlert = (now - lastAlertTime) > COOLDOWN_DURATION_MS

            if (canAlert && signalStateManager.shouldDispatchNotification(signal.id, signal.symbol, signal.direction, now)) {
                alertCooldowns[signal.symbol] = now
                signalStateManager.markNotificationDispatched(signal.id, signal.symbol, signal.direction, now)
                triggerSignalNotification(signal)
            }

            // Add to active signals
            _scannerState.update { state ->
                val updatedActive = listOf(signal) + state.activeSignals.filterNot { it.symbol == signal.symbol }
                val updatedSetups = state.watchlistSetups.filterNot { it.symbol == signal.symbol }
                state.copy(
                    activeSignals = updatedActive,
                    watchlistSetups = updatedSetups,
                    statistics = computeStatistics(updatedActive, state.signalHistory)
                )
            }
        } else if (signal.lifecycle == FuturesSignalLifecycle.WATCHLIST_SETUP) {
            // Update or add developing setup
            _scannerState.update { state ->
                val updatedSetups = listOf(signal) + state.watchlistSetups.filterNot { it.symbol == signal.symbol }
                state.copy(
                    watchlistSetups = updatedSetups,
                    statistics = computeStatistics(state.activeSignals, state.signalHistory)
                )
            }
        }
        return intake
    }

    /**
     * Part 3 Strict Multi-Market Signal State & Notification Gate (>92% quality score + all mandatory filters).
     */
    fun onStrictMultiMarketEvaluated(
        rankedBoard: RankedMultiMarketBoard,
        nowMs: Long = System.currentTimeMillis()
    ) {
        for (item in rankedBoard.opportunities) {
            val eval = item.evaluation
            val setup = item.setup ?: continue
            if (eval.status == SetupValidationStatus.DATA_UNAVAILABLE || eval.status == SetupValidationStatus.INVALIDATED) {
                continue
            }

            val qualityScore = item.qualityReport?.totalScore ?: setup.score
            val allMandatoryPassed = item.qualityReport?.allMandatoryFiltersPassed ?: true
            val isConfirmed = item.isQualified && setup.status == SetupValidationStatus.CONFIRMED

            val intake = signalStateManager.submitCandidate(
                symbol = eval.symbol,
                direction = setup.direction,
                isConfirmed = isConfirmed,
                entryPrice = setup.entryPrice,
                stopLoss = setup.stopLoss,
                takeProfit1 = setup.adjustedTarget1,
                takeProfit2 = setup.adjustedTarget2,
                netRiskReward = setup.estimatedNetRiskReward,
                qualityScore = qualityScore,
                dataSourceName = eval.venueName,
                setupReason = setup.supportingConfirmations.firstOrNull() ?: setup.setupType.label,
                confirmationCandleCloseMs = setup.confirmationCandleCloseTimeMs.takeIf { it > 0L } ?: nowMs,
                expiresAtMs = nowMs + 6 * 3_600_000L,
                nowMs = nowMs
            )

            if (intake.accepted && isConfirmed && !intake.isDuplicateSuppressed && intake.entry != null) {
                val entry = intake.entry
                if (signalStateManager.shouldDispatchNotification(entry.signalId, entry.symbol, entry.direction, nowMs)) {
                    val dispatched = notificationManager?.notifyStrictQualifiedSignal(
                        signalId = entry.signalId,
                        symbol = eval.displaySymbol,
                        direction = setup.direction,
                        entryPriceFormatted = HighRewardOpportunity.fmtPrice(setup.entryPrice),
                        stopLossFormatted = HighRewardOpportunity.fmtPrice(setup.stopLoss),
                        tp1Formatted = HighRewardOpportunity.fmtPrice(setup.adjustedTarget1),
                        tpFinalFormatted = HighRewardOpportunity.fmtPrice(setup.adjustedTarget2),
                        netRiskRewardRatio = setup.estimatedNetRiskReward,
                        qualityScore = qualityScore,
                        allMandatoryFiltersPassed = allMandatoryPassed,
                        setupReason = setup.supportingConfirmations.firstOrNull() ?: setup.setupType.label,
                        dataSourceName = eval.venueName,
                        timestampMs = nowMs,
                        playSound = _scannerState.value.soundAlertsEnabled
                    ) ?: false
                    if (dispatched) {
                        signalStateManager.markNotificationDispatched(entry.signalId, entry.symbol, entry.direction, nowMs)
                    }
                }
            }
        }
        syncManagedStateFlows()
    }

    private fun syncManagedStateFlows() {
        _managedSignals.value = signalStateManager.getAllEntries()
        _signalTransitionLog.value = signalStateManager.getTransitionHistory()
    }

    /**
     * Part 4: Ingests HighRewardScanResult for a symbol, notifies via NexusNotificationManager
     * without duplicate alerts, and prioritizes >= 1:5.0 setups ahead of 1:4.0–4.99.
     */
    fun onHighRewardScanEvaluated(
        symbol: String,
        result: HighRewardScanResult,
        playSound: Boolean = _scannerState.value.soundAlertsEnabled,
        source: String = "NEXUS 1:5 Opportunity Detector"
    ) {
        val nowMs = System.currentTimeMillis()
        result.qualifiedOpportunities.forEach { opp ->
            if (opp.isQualified && opp.status == OpportunityStatus.ACTIVE) {
                notificationManager?.notifyHighRewardOpportunity(
                    opportunity = opp,
                    playSound = playSound,
                    source = source
                )
            }
        }

        _highRewardState.update { prev ->
            val archivedNewly = mutableListOf<HighRewardOpportunity>()
            val existingRetained = prev.activeOpportunities
                .filterNot { it.symbol.equals(symbol, ignoreCase = true) }
                .mapNotNull { existing ->
                    val evaluated = HighRewardOpportunityDetector.evaluateLifecycle(existing, null, nowMs)
                    if (evaluated.status == OpportunityStatus.ACTIVE) evaluated
                    else {
                        archivedNewly.add(evaluated)
                        null
                    }
                }

            val mergedActive = (result.qualifiedOpportunities + existingRetained)
                .distinctBy { it.signatureKey }
                .sortedWith(
                    compareByDescending<HighRewardOpportunity> { it.tier == OpportunityPriorityTier.HIGHEST_1_TO_5_PLUS }
                        .thenByDescending { it.netRiskRewardRatio }
                        .thenByDescending { it.entryTriggerTimestampMs }
                )

            val updatedDiagnostics = (listOf(result.bestDiagnostic) +
                prev.diagnostics.filterNot { it.symbol.equals(symbol, ignoreCase = true) }).take(20)

            val bestDiagOverall = updatedDiagnostics.maxByOrNull { it.netRiskRewardRatio }
            val waitMsg = if (mergedActive.isEmpty()) {
                if (bestDiagOverall != null && bestDiagOverall.netRiskRewardRatio > 0.0) {
                    "WAIT — No confirmed >=1:4 setup right now (best observed: ${bestDiagOverall.symbol} net 1:${String.format(Locale.US, "%.2f", bestDiagOverall.netRiskRewardRatio)} — ${bestDiagOverall.reason}). Never widens TP or tightens SL artificially."
                } else {
                    "WAIT — Monitoring selected pairs for confirmed entry triggers with >=1:4 net R:R (>=1:5 top priority). Never forces signals."
                }
            } else {
                val top = mergedActive.first()
                "${mergedActive.size} active high-reward opportunity(s) — Top: ${top.alertBannerTitle}"
            }

            val updatedArchived = (archivedNewly + prev.archivedOpportunities)
                .distinctBy { it.signatureKey }
                .take(25)

            prev.copy(
                activeOpportunities = mergedActive,
                archivedOpportunities = updatedArchived,
                diagnostics = updatedDiagnostics,
                waitBannerMessage = waitMsg,
                lastScannedTimestampMs = nowMs,
                lastScannedPkt = TimeZoneUtils.formatKarachiTimestamp(nowMs)
            )
        }
    }

    /**
     * Evaluates live prices from WebSocket stream against active signals to update lifecycles.
     */
    fun onLivePricesUpdated(priceMap: Map<String, Double>) {
        if (priceMap.isEmpty()) return

        // Update watchlist prices
        _watchlist.update { current ->
            current.map { pair ->
                val livePrice = priceMap[pair.symbol]
                if (livePrice != null) pair.copy(currentPrice = livePrice) else pair
            }
        }

        // Check active signals against live price
        _scannerState.update { state ->
            val stillActive = mutableListOf<FuturesScanSignal>()
            val newlyCompleted = mutableListOf<FuturesScanSignal>()

            for (signal in state.activeSignals) {
                val currentPrice = priceMap[signal.symbol] ?: signal.currentPrice
                var updatedLifecycle = signal.lifecycle
                var outcomeSummary: String? = null
                var paperExitPrice: Double? = null
                var paperPnl: Double? = null

                if (signal.direction == FuturesDirection.LONG) {
                    when {
                        currentPrice >= signal.takeProfit3 -> {
                            updatedLifecycle = FuturesSignalLifecycle.TARGET_3_HIT
                            outcomeSummary = "TP3 Hit: +${String.format(Locale.US, "%.2f", signal.tp3DistancePercent)}%"
                            paperExitPrice = signal.takeProfit3
                            paperPnl = signal.tp3DistancePercent - 0.10 // Less 0.05% entry + 0.05% exit fee
                        }
                        currentPrice >= signal.takeProfit2 && signal.lifecycle != FuturesSignalLifecycle.TARGET_2_HIT -> {
                            updatedLifecycle = FuturesSignalLifecycle.TARGET_2_HIT
                            outcomeSummary = "TP2 Hit: +${String.format(Locale.US, "%.2f", signal.tp2DistancePercent)}%"
                            paperExitPrice = signal.takeProfit2
                            paperPnl = signal.tp2DistancePercent - 0.10
                        }
                        currentPrice >= signal.takeProfit1 && signal.lifecycle == FuturesSignalLifecycle.ACTIVE -> {
                            updatedLifecycle = FuturesSignalLifecycle.TARGET_1_HIT
                        }
                        currentPrice <= signal.stopLoss -> {
                            updatedLifecycle = FuturesSignalLifecycle.STOP_LOSS_HIT
                            outcomeSummary = "Stop Loss Hit: -${String.format(Locale.US, "%.2f", signal.slDistancePercent)}%"
                            paperExitPrice = signal.stopLoss
                            paperPnl = -signal.slDistancePercent - 0.10
                        }
                    }
                } else {
                    // SHORT
                    when {
                        currentPrice <= signal.takeProfit3 -> {
                            updatedLifecycle = FuturesSignalLifecycle.TARGET_3_HIT
                            outcomeSummary = "TP3 Hit: +${String.format(Locale.US, "%.2f", signal.tp3DistancePercent)}%"
                            paperExitPrice = signal.takeProfit3
                            paperPnl = signal.tp3DistancePercent - 0.10
                        }
                        currentPrice <= signal.takeProfit2 && signal.lifecycle != FuturesSignalLifecycle.TARGET_2_HIT -> {
                            updatedLifecycle = FuturesSignalLifecycle.TARGET_2_HIT
                            outcomeSummary = "TP2 Hit: +${String.format(Locale.US, "%.2f", signal.tp2DistancePercent)}%"
                            paperExitPrice = signal.takeProfit2
                            paperPnl = signal.tp2DistancePercent - 0.10
                        }
                        currentPrice <= signal.takeProfit1 && signal.lifecycle == FuturesSignalLifecycle.ACTIVE -> {
                            updatedLifecycle = FuturesSignalLifecycle.TARGET_1_HIT
                        }
                        currentPrice >= signal.stopLoss -> {
                            updatedLifecycle = FuturesSignalLifecycle.STOP_LOSS_HIT
                            outcomeSummary = "Stop Loss Hit: -${String.format(Locale.US, "%.2f", signal.slDistancePercent)}%"
                            paperExitPrice = signal.stopLoss
                            paperPnl = -signal.slDistancePercent - 0.10
                        }
                    }
                }

                val updatedSignal = signal.copy(
                    currentPrice = currentPrice,
                    lifecycle = updatedLifecycle,
                    outcomeSummary = outcomeSummary ?: signal.outcomeSummary,
                    paperTradeExitPrice = paperExitPrice ?: signal.paperTradeExitPrice,
                    paperTradePnlPercent = paperPnl ?: signal.paperTradePnlPercent
                )

                // If resolved, move to history
                if (updatedLifecycle in listOf(
                        FuturesSignalLifecycle.TARGET_2_HIT,
                        FuturesSignalLifecycle.TARGET_3_HIT,
                        FuturesSignalLifecycle.STOP_LOSS_HIT,
                        FuturesSignalLifecycle.EXPIRED,
                        FuturesSignalLifecycle.INVALIDATED
                    )
                ) {
                    newlyCompleted.add(updatedSignal)
                } else {
                    stillActive.add(updatedSignal)
                }
            }

            val updatedHistory = newlyCompleted + state.signalHistory
            if (newlyCompleted.isNotEmpty()) {
                saveSignalHistoryToStorage(updatedHistory)
            }

            state.copy(
                activeSignals = stillActive,
                signalHistory = updatedHistory,
                statistics = computeStatistics(stillActive, updatedHistory)
            )
        }

        evaluatePaperOrdersAndTradeAlertsOnLivePrices(priceMap)
        invalidateExpiredHighRewardOpportunities(priceMap)
        val nowMs = System.currentTimeMillis()
        priceMap.forEach { (sym, price) ->
            signalStateManager.evaluateMarketUpdate(sym, price, nowMs)
        }
        syncManagedStateFlows()
    }

    fun invalidateExpiredHighRewardOpportunities(
        priceMap: Map<String, Double>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val current = _highRewardState.value
        if (current.activeOpportunities.isEmpty()) return

        val stillActive = mutableListOf<HighRewardOpportunity>()
        val newlyArchived = mutableListOf<HighRewardOpportunity>()

        for (opp in current.activeOpportunities) {
            val evaluated = HighRewardOpportunityDetector.evaluateLifecycle(opp, priceMap[opp.symbol], nowMs)
            if (evaluated.status == OpportunityStatus.ACTIVE) {
                stillActive.add(evaluated)
            } else {
                newlyArchived.add(evaluated)
            }
        }

        if (newlyArchived.isNotEmpty() || stillActive != current.activeOpportunities) {
            _highRewardState.update { prev ->
                val combinedArchive = (newlyArchived + prev.archivedOpportunities)
                    .distinctBy { it.signatureKey }
                    .take(25)
                val waitMsg = if (stillActive.isEmpty()) {
                    "WAIT — Previous opportunity expired/invalidated. Scanning for confirmed >=1:4 / >=1:5 net R:R triggers."
                } else {
                    prev.waitBannerMessage
                }
                prev.copy(
                    activeOpportunities = stillActive,
                    archivedOpportunities = combinedArchive,
                    waitBannerMessage = waitMsg
                )
            }
        }
    }

    private fun evaluatePaperOrdersAndTradeAlertsOnLivePrices(priceMap: Map<String, Double>) {
        // 1. Update open paper order statuses & daily ledger PnL
        var ledgerChanged = false
        val updatedOrders = _paperOrders.value.map { order ->
            if (order.status != PaperOrderStatus.OPEN_PAPER && order.status != PaperOrderStatus.TP1_HIT_PAPER) {
                order
            } else {
                val p = priceMap[order.symbol] ?: return@map order
                val isLong = order.direction == FuturesDirection.LONG
                when {
                    (isLong && p >= order.takeProfit2) || (!isLong && p <= order.takeProfit2) -> {
                        val grossPnl = if (isLong) (order.takeProfit2 - order.entryPrice) * order.quantity else (order.entryPrice - order.takeProfit2) * order.quantity
                        val realized = grossPnl - order.estimatedFeesUsdt
                        ledgerChanged = true
                        order.copy(
                            status = PaperOrderStatus.CLOSED_TP_PAPER,
                            closedPrice = order.takeProfit2,
                            realizedPnlUsdt = realized
                        )
                    }
                    (isLong && p <= order.stopLoss) || (!isLong && p >= order.stopLoss) -> {
                        val grossPnl = if (isLong) (order.stopLoss - order.entryPrice) * order.quantity else (order.entryPrice - order.stopLoss) * order.quantity
                        val realized = grossPnl - order.estimatedFeesUsdt
                        ledgerChanged = true
                        order.copy(
                            status = PaperOrderStatus.CLOSED_SL_PAPER,
                            closedPrice = order.stopLoss,
                            realizedPnlUsdt = realized
                        )
                    }
                    order.status == PaperOrderStatus.OPEN_PAPER &&
                        ((isLong && p >= order.takeProfit1) || (!isLong && p <= order.takeProfit1)) -> {
                        ledgerChanged = true
                        order.copy(status = PaperOrderStatus.TP1_HIT_PAPER)
                    }
                    else -> order
                }
            }
        }
        if (ledgerChanged) {
            _paperOrders.value = updatedOrders
            savePaperOrdersToStorage(updatedOrders)
            reconcileDailyLedgerWithOrders()
        }

        // 2. Evaluate persisted lifecycle alert rules (Entry Trigger, SL, TP, Invalidation, Expiry, Market Change)
        val rules = _tradeAlertRules.value
        if (rules.isNotEmpty()) {
            val state = _scannerState.value
            val signalsById = (state.activeSignals + state.watchlistSetups + state.signalHistory).associateBy { it.id }
            val triggered = AlertMonitorEngine.evaluateRules(
                rules = rules,
                livePrices = priceMap,
                signalsById = signalsById,
                alreadyTriggeredEventIds = notificationManager?.getDeliveredEventIds() ?: emptySet()
            )
            triggered.forEach { ev ->
                val sent = notificationManager?.notifyTradeLifecycleAlert(
                    eventId = ev.eventId,
                    symbol = ev.symbol,
                    signalId = ev.signalId.takeIf { it.isNotBlank() },
                    alertType = ev.alertType,
                    title = ev.title,
                    body = ev.body,
                    timestampMs = ev.timestampMs,
                    source = "Live Futures Monitor",
                    openWorkflow = ev.openWorkflowOnTap
                ) ?: false
                if (sent || notificationManager == null) {
                    markTradeAlertRuleTriggered(ev.ruleId)
                }
            }
        }
    }

    fun updateScannerStatus(
        status: ScannerStatus,
        currentlyScanning: String? = null,
        errorMessage: String? = null
    ) {
        _scannerState.update { state ->
            state.copy(
                status = status,
                currentlyScanningSymbol = currentlyScanning,
                errorMessage = errorMessage,
                lastScanCompletedTimestamp = if (status == ScannerStatus.IDLE) System.currentTimeMillis() else state.lastScanCompletedTimestamp,
                lastScanFormatted = com.example.util.TimeZoneUtils.formatKarachiTimeOnly(System.currentTimeMillis())
            )
        }
    }

    fun markPaperTradeExecuted(signalId: String) {
        _scannerState.update { state ->
            state.copy(
                activeSignals = state.activeSignals.map {
                    if (it.id == signalId) it.copy(paperTradeExecuted = true) else it
                },
                watchlistSetups = state.watchlistSetups.map {
                    if (it.id == signalId) it.copy(paperTradeExecuted = true) else it
                }
            )
        }
    }

    fun setPaperTradingEnabled(enabled: Boolean) {
        _scannerState.update { it.copy(paperTradingEnabled = enabled) }
        prefs?.edit()?.putBoolean("paper_trading_enabled", enabled)?.apply()
    }

    fun setScanInterval(seconds: Int) {
        _scannerState.update { it.copy(scanIntervalSeconds = seconds) }
        prefs?.edit()?.putInt("scan_interval_seconds", seconds)?.apply()
    }

    fun setMinQualityScore(score: Int) {
        _scannerState.update { it.copy(minQualityScoreFilter = score) }
        prefs?.edit()?.putInt("min_quality_score", score)?.apply()
    }

    fun setStrict92ConfidenceMode(enabled: Boolean) {
        val threshold = if (enabled) 92 else 60
        _scannerState.update {
            it.copy(
                strict92ConfidenceMode = enabled,
                minQualifyingConfidence = threshold
            )
        }
        prefs?.edit()?.putBoolean(KEY_STRICT_92, enabled)?.apply()
    }

    fun filterQualifyingSignals(signals: List<FuturesScanSignal>): List<FuturesScanSignal> {
        val state = _scannerState.value
        return if (state.strict92ConfidenceMode) {
            signals.filter { it.qualityScore >= 92 }
        } else {
            signals
        }
    }

    fun setSoundAlerts(enabled: Boolean) {
        _scannerState.update { it.copy(soundAlertsEnabled = enabled) }
        prefs?.edit()?.putBoolean("sound_alerts_enabled", enabled)?.apply()
    }

    fun setVibrationAlerts(enabled: Boolean) {
        _scannerState.update { it.copy(vibrationEnabled = enabled) }
        prefs?.edit()?.putBoolean("vibration_alerts_enabled", enabled)?.apply()
    }

    // ==========================================
    // BUDGET, DAILY RISK LEDGER, PAPER ORDERS & ALERT RULES
    // ==========================================

    fun updateBudgetConfig(config: BudgetConfig) {
        _budgetConfig.value = config
        saveBudgetConfigToStorage(config)
    }

    fun recordSubmittedPaperOrder(order: PaperOrderRecord) {
        val updatedOrders = listOf(order) + _paperOrders.value.filterNot { it.orderId == order.orderId }
        _paperOrders.value = updatedOrders
        savePaperOrdersToStorage(updatedOrders)

        val todayKey = currentPktDateKey()
        val base = _dailyRiskLedger.value.let { if (it.dateKeyPkt == todayKey) it else DailyRiskLedger(dateKeyPkt = todayKey) }
        val openOrders = updatedOrders.filter { it.status == PaperOrderStatus.OPEN_PAPER || it.status == PaperOrderStatus.TP1_HIT_PAPER }
        val updatedLedger = base.copy(
            dateKeyPkt = todayKey,
            tradesTakenToday = base.tradesTakenToday + 1,
            existingOpenMarginUsdt = openOrders.sumOf { it.estimatedMarginUsdt },
            openOrderSymbols = openOrders.map { it.symbol }.distinct()
        )
        _dailyRiskLedger.value = updatedLedger
        saveDailyLedgerToStorage(updatedLedger)
    }

    private fun reconcileDailyLedgerWithOrders() {
        val todayKey = currentPktDateKey()
        val openOrders = _paperOrders.value.filter { it.status == PaperOrderStatus.OPEN_PAPER || it.status == PaperOrderStatus.TP1_HIT_PAPER }
        val realizedToday = _paperOrders.value
            .filter { TimeZoneUtils.formatKarachiShort(it.createdTimestampMs).substringBefore(",") == todayKey }
            .sumOf { it.realizedPnlUsdt ?: 0.0 }
        val base = _dailyRiskLedger.value.let { if (it.dateKeyPkt == todayKey) it else DailyRiskLedger(dateKeyPkt = todayKey) }
        val updatedLedger = base.copy(
            dateKeyPkt = todayKey,
            realizedPnlUsdtToday = realizedToday,
            existingOpenMarginUsdt = openOrders.sumOf { it.estimatedMarginUsdt },
            openOrderSymbols = openOrders.map { it.symbol }.distinct()
        )
        _dailyRiskLedger.value = updatedLedger
        saveDailyLedgerToStorage(updatedLedger)
    }

    fun addTradeAlertRules(newRules: List<TradeAlertRule>) {
        if (newRules.isEmpty()) return
        val existingIds = _tradeAlertRules.value.map { it.ruleId }.toSet()
        val merged = newRules.filter { it.ruleId !in existingIds } + _tradeAlertRules.value
        val trimmed = merged.take(120)
        _tradeAlertRules.value = trimmed
        saveTradeAlertRulesToStorage(trimmed)
    }

    fun getActiveTradeAlertRules(): List<TradeAlertRule> {
        return _tradeAlertRules.value.filter { it.isActive && !it.isTriggered }
    }

    fun markTradeAlertRuleTriggered(ruleId: String) {
        val updated = _tradeAlertRules.value.map { r ->
            if (r.ruleId == ruleId) r.copy(isTriggered = true, isActive = false) else r
        }
        _tradeAlertRules.value = updated
        saveTradeAlertRulesToStorage(updated)
    }

    private fun currentPktDateKey(): String =
        TimeZoneUtils.formatKarachiShort(System.currentTimeMillis()).substringBefore(",")

    // ==========================================
    // NOTIFICATION TRIGGER
    // ==========================================

    private fun triggerSignalNotification(signal: FuturesScanSignal) {
        notificationManager?.notifyFuturesScanSignal(
            symbol = signal.symbol,
            isLong = signal.direction == FuturesDirection.LONG,
            entryZone = signal.entryZoneFormatted,
            sl = signal.stopLossFormatted,
            tp1 = signal.tp1Formatted,
            qualityScore = signal.qualityScore,
            signalId = signal.id
        )
    }

    // ==========================================
    // STATISTICS CALCULATION
    // ==========================================

    private fun computeStatistics(
        active: List<FuturesScanSignal>,
        history: List<FuturesScanSignal>
    ): ScannerStatistics {
        val total = active.size + history.size
        val tp1 = history.count { it.lifecycle == FuturesSignalLifecycle.TARGET_1_HIT }
        val tp2 = history.count { it.lifecycle == FuturesSignalLifecycle.TARGET_2_HIT }
        val tp3 = history.count { it.lifecycle == FuturesSignalLifecycle.TARGET_3_HIT }
        val sl = history.count { it.lifecycle == FuturesSignalLifecycle.STOP_LOSS_HIT }
        val expired = history.count { it.lifecycle == FuturesSignalLifecycle.EXPIRED }
        val invalidated = history.count { it.lifecycle == FuturesSignalLifecycle.INVALIDATED }

        val wins = tp1 + tp2 + tp3
        val completedTrades = wins + sl
        val winRate = if (completedTrades > 0) (wins.toDouble() / completedTrades) * 100.0 else 0.0

        val avgRr = if (history.isNotEmpty()) {
            history.map { it.riskRewardRatio }.average()
        } else 2.5

        val paperTrades = history.filter { it.paperTradePnlPercent != null }
        val netPaperPnl = paperTrades.sumOf { it.paperTradePnlPercent ?: 0.0 }

        return ScannerStatistics(
            totalSignalsScanned = total,
            activeSignalsCount = active.size,
            confirmedSignalsCount = active.count { it.lifecycle == FuturesSignalLifecycle.ENTRY_CONFIRMED },
            watchlistSetupsCount = _scannerState.value.watchlistSetups.size,
            tp1HitsCount = tp1,
            tp2HitsCount = tp2,
            tp3HitsCount = tp3,
            stopLossHitsCount = sl,
            expiredCount = expired,
            invalidatedCount = invalidated,
            winRatePercent = winRate,
            averageRiskReward = avgRr,
            totalPaperTrades = paperTrades.size,
            paperTradingNetPnlPercent = netPaperPnl
        )
    }

    // ==========================================
    // PERSISTENCE STORAGE
    // ==========================================

    private fun saveWatchlistToStorage() {
        try {
            val array = JSONArray()
            _watchlist.value.forEach { pair ->
                val obj = JSONObject().apply {
                    put("symbol", pair.symbol)
                    put("base", pair.baseAsset)
                    put("name", pair.displayName)
                    put("selected", pair.isSelectedForScanning)
                }
                array.put(obj)
            }
            prefs?.edit()?.putString(KEY_WATCHLIST, array.toString())?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving watchlist: ${e.message}")
        }
    }

    private fun loadWatchlistFromStorage() {
        val jsonStr = prefs?.getString(KEY_WATCHLIST, null) ?: return
        try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<FuturesWatchlistPair>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    FuturesWatchlistPair(
                        symbol = obj.getString("symbol"),
                        baseAsset = obj.getString("base"),
                        displayName = obj.getString("name"),
                        isSelectedForScanning = obj.optBoolean("selected", true)
                    )
                )
            }
            if (list.isNotEmpty()) {
                _watchlist.value = list
                updateSelectedWatchlistCount()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed loading watchlist: ${e.message}")
        }
    }

    private fun saveSignalHistoryToStorage(history: List<FuturesScanSignal>) {
        try {
            val array = JSONArray()
            history.take(50).forEach { sig ->
                val obj = JSONObject().apply {
                    put("id", sig.id)
                    put("symbol", sig.symbol)
                    put("base", sig.baseAsset)
                    put("direction", sig.direction.name)
                    put("currentPrice", sig.currentPrice)
                    put("entryZoneMin", sig.entryZoneMin)
                    put("entryZoneMax", sig.entryZoneMax)
                    put("entryPrice", sig.entryPrice)
                    put("stopLoss", sig.stopLoss)
                    put("tp1", sig.takeProfit1)
                    put("tp2", sig.takeProfit2)
                    put("tp3", sig.takeProfit3)
                    put("rr", sig.riskRewardRatio)
                    put("score", sig.qualityScore)
                    put("lifecycle", sig.lifecycle.name)
                    put("timestamp", sig.timestamp)
                    put("outcome", sig.outcomeSummary ?: "")
                    put("paperPnl", sig.paperTradePnlPercent ?: 0.0)
                }
                array.put(obj)
            }
            prefs?.edit()?.putString(KEY_HISTORY, array.toString())?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving history: ${e.message}")
        }
    }

    private fun loadSignalHistoryFromStorage() {
        val jsonStr = prefs?.getString(KEY_HISTORY, null) ?: return
        try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<FuturesScanSignal>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    FuturesScanSignal(
                        id = obj.getString("id"),
                        symbol = obj.getString("symbol"),
                        baseAsset = obj.getString("base"),
                        direction = FuturesDirection.valueOf(obj.getString("direction")),
                        currentPrice = obj.getDouble("currentPrice"),
                        entryZoneMin = obj.getDouble("entryZoneMin"),
                        entryZoneMax = obj.getDouble("entryZoneMax"),
                        entryPrice = obj.getDouble("entryPrice"),
                        stopLoss = obj.getDouble("stopLoss"),
                        takeProfit1 = obj.getDouble("tp1"),
                        takeProfit2 = obj.getDouble("tp2"),
                        takeProfit3 = obj.getDouble("tp3"),
                        riskRewardRatio = obj.getDouble("rr"),
                        qualityScore = obj.getInt("score"),
                        qualityRating = "Scanned Setup",
                        timeframeConfirmations = emptyList(),
                        reasonsForEntry = listOf("Historical verified signal"),
                        invalidationCondition = "Closed",
                        lifecycle = FuturesSignalLifecycle.valueOf(obj.getString("lifecycle")),
                        timestamp = obj.getLong("timestamp"),
                        outcomeSummary = obj.optString("outcome").takeIf { it.isNotEmpty() },
                        paperTradePnlPercent = obj.optDouble("paperPnl").takeIf { !it.isNaN() }
                    )
                )
            }
            _scannerState.update { state ->
                state.copy(
                    signalHistory = list,
                    statistics = computeStatistics(state.activeSignals, list)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed loading signal history: ${e.message}")
        }
    }

    private fun loadScannerPreferencesFromStorage() {
        val strict92 = prefs?.getBoolean(KEY_STRICT_92, false) ?: false
        val paperEnabled = prefs?.getBoolean("paper_trading_enabled", false) ?: false
        val interval = prefs?.getInt("scan_interval_seconds", 30) ?: 30
        _scannerState.update {
            it.copy(
                strict92ConfidenceMode = strict92,
                minQualifyingConfidence = if (strict92) 92 else 60,
                paperTradingEnabled = paperEnabled,
                scanIntervalSeconds = interval
            )
        }
    }

    private fun saveBudgetConfigToStorage(config: BudgetConfig) {
        try {
            val obj = JSONObject().apply {
                put("balance", config.accountBalanceUsdt)
                put("riskAmt", config.maxRiskPerTradeAmount)
                put("currency", config.riskCurrency.name)
                put("pkrRate", config.pkrPerUsdtRate)
                put("maxRiskPct", config.maxRiskPercent)
                put("levLimit", config.leverageLimit)
                put("feePct", config.feeRatePercentPerSide)
                put("slipPct", config.slippagePercentPerSide)
                put("dailyWarnPct", config.dailyLossWarningPercent)
                put("dailyLossPct", config.dailyLossLimitPercent)
                put("maxTrades", config.maxTradesPerDay)
                put("minRr", config.minRiskRewardRatio)
                put("maxExposurePct", config.maxOpenExposurePercentOfBalance)
            }
            prefs?.edit()?.putString(KEY_BUDGET_CONFIG, obj.toString())?.apply()
        } catch (_: Exception) {
        }
    }

    private fun loadBudgetConfigFromStorage() {
        val raw = prefs?.getString(KEY_BUDGET_CONFIG, null) ?: return
        try {
            val obj = JSONObject(raw)
            val curr = RiskCurrency.entries.firstOrNull { it.name == obj.optString("currency") } ?: RiskCurrency.USDT
            _budgetConfig.value = BudgetConfig(
                accountBalanceUsdt = obj.optDouble("balance", 1000.0),
                maxRiskPerTradeAmount = obj.optDouble("riskAmt", 10.0),
                riskCurrency = curr,
                pkrPerUsdtRate = obj.optDouble("pkrRate", 278.5),
                maxRiskPercent = obj.optDouble("maxRiskPct", 1.0),
                leverageLimit = obj.optInt("levLimit", 5),
                feeRatePercentPerSide = obj.optDouble("feePct", 0.05),
                slippagePercentPerSide = obj.optDouble("slipPct", 0.03),
                dailyLossWarningPercent = obj.optDouble("dailyWarnPct", 3.0),
                dailyLossLimitPercent = obj.optDouble("dailyLossPct", 5.0),
                maxTradesPerDay = obj.optInt("maxTrades", 2),
                minRiskRewardRatio = obj.optDouble("minRr", 1.5),
                maxOpenExposurePercentOfBalance = obj.optDouble("maxExposurePct", 80.0)
            )
        } catch (_: Exception) {
        }
    }

    private fun savePaperOrdersToStorage(orders: List<PaperOrderRecord>) {
        try {
            val arr = JSONArray()
            orders.take(50).forEach { o ->
                arr.put(
                    JSONObject().apply {
                        put("orderId", o.orderId)
                        put("signalId", o.signalId)
                        put("symbol", o.symbol)
                        put("direction", o.direction.name)
                        put("qty", o.quantity)
                        put("qtyFmt", o.quantityFormatted)
                        put("entry", o.entryPrice)
                        put("sl", o.stopLoss)
                        put("tp1", o.takeProfit1)
                        put("tp2", o.takeProfit2)
                        put("tp3", o.takeProfit3)
                        put("lev", o.leverage)
                        put("margin", o.estimatedMarginUsdt)
                        put("fees", o.estimatedFeesUsdt)
                        put("maxLoss", o.maxPlannedLossUsdt)
                        put("status", o.status.name)
                        put("confirmed", o.userConfirmedExplicitly)
                        put("mode", o.executionMode.name)
                        put("ts", o.createdTimestampMs)
                        put("tsPkt", o.createdTimestampPkt)
                        o.closedPrice?.let { put("closedPrice", it) }
                        o.realizedPnlUsdt?.let { put("realizedPnl", it) }
                    }
                )
            }
            prefs?.edit()?.putString(KEY_PAPER_ORDERS, arr.toString())?.apply()
        } catch (_: Exception) {
        }
    }

    private fun loadPaperOrdersFromStorage() {
        val raw = prefs?.getString(KEY_PAPER_ORDERS, null) ?: return
        try {
            val arr = JSONArray(raw)
            val list = mutableListOf<PaperOrderRecord>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val dir = FuturesDirection.entries.firstOrNull { it.name == o.optString("direction") } ?: FuturesDirection.LONG
                val st = PaperOrderStatus.entries.firstOrNull { it.name == o.optString("status") } ?: PaperOrderStatus.OPEN_PAPER
                val mode = ExecutionMode.entries.firstOrNull { it.name == o.optString("mode") } ?: ExecutionMode.PAPER_TRADING
                list.add(
                    PaperOrderRecord(
                        orderId = o.optString("orderId"),
                        signalId = o.optString("signalId"),
                        symbol = o.optString("symbol"),
                        direction = dir,
                        quantity = o.optDouble("qty", 0.0),
                        quantityFormatted = o.optString("qtyFmt", "0"),
                        entryPrice = o.optDouble("entry", 0.0),
                        stopLoss = o.optDouble("sl", 0.0),
                        takeProfit1 = o.optDouble("tp1", 0.0),
                        takeProfit2 = o.optDouble("tp2", 0.0),
                        takeProfit3 = o.optDouble("tp3", 0.0),
                        leverage = o.optInt("lev", 3),
                        estimatedMarginUsdt = o.optDouble("margin", 0.0),
                        estimatedFeesUsdt = o.optDouble("fees", 0.0),
                        maxPlannedLossUsdt = o.optDouble("maxLoss", 0.0),
                        status = st,
                        userConfirmedExplicitly = o.optBoolean("confirmed", true),
                        executionMode = mode,
                        createdTimestampMs = o.optLong("ts", 0L),
                        createdTimestampPkt = o.optString("tsPkt"),
                        closedPrice = if (o.has("closedPrice")) o.optDouble("closedPrice") else null,
                        realizedPnlUsdt = if (o.has("realizedPnl")) o.optDouble("realizedPnl") else null
                    )
                )
            }
            _paperOrders.value = list
        } catch (_: Exception) {
        }
    }

    private fun saveDailyLedgerToStorage(ledger: DailyRiskLedger) {
        try {
            val obj = JSONObject().apply {
                put("date", ledger.dateKeyPkt)
                put("trades", ledger.tradesTakenToday)
                put("pnl", ledger.realizedPnlUsdtToday)
                put("margin", ledger.existingOpenMarginUsdt)
                put("symbols", JSONArray(ledger.openOrderSymbols))
            }
            prefs?.edit()?.putString(KEY_DAILY_LEDGER, obj.toString())?.apply()
        } catch (_: Exception) {
        }
    }

    private fun loadDailyLedgerFromStorage() {
        val raw = prefs?.getString(KEY_DAILY_LEDGER, null)
        val todayKey = currentPktDateKey()
        if (raw == null) {
            _dailyRiskLedger.value = DailyRiskLedger(dateKeyPkt = todayKey)
            return
        }
        try {
            val obj = JSONObject(raw)
            val savedDate = obj.optString("date", todayKey)
            val openOrders = _paperOrders.value.filter { it.status == PaperOrderStatus.OPEN_PAPER || it.status == PaperOrderStatus.TP1_HIT_PAPER }
            if (savedDate != todayKey) {
                _dailyRiskLedger.value = DailyRiskLedger(
                    dateKeyPkt = todayKey,
                    tradesTakenToday = 0,
                    realizedPnlUsdtToday = 0.0,
                    existingOpenMarginUsdt = openOrders.sumOf { it.estimatedMarginUsdt },
                    openOrderSymbols = openOrders.map { it.symbol }.distinct()
                )
            } else {
                _dailyRiskLedger.value = DailyRiskLedger(
                    dateKeyPkt = savedDate,
                    tradesTakenToday = obj.optInt("trades", 0),
                    realizedPnlUsdtToday = obj.optDouble("pnl", 0.0),
                    existingOpenMarginUsdt = openOrders.sumOf { it.estimatedMarginUsdt }.takeIf { it > 0.0 }
                        ?: obj.optDouble("margin", 0.0),
                    openOrderSymbols = openOrders.map { it.symbol }.distinct()
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun saveTradeAlertRulesToStorage(rules: List<TradeAlertRule>) {
        try {
            val arr = JSONArray()
            rules.take(120).forEach { r ->
                arr.put(
                    JSONObject().apply {
                        put("ruleId", r.ruleId)
                        put("signalId", r.signalId)
                        put("symbol", r.symbol)
                        put("direction", r.direction.name)
                        put("category", r.category.name)
                        r.triggerPrice?.let { put("triggerPrice", it) }
                        r.expiryTimestampMs?.let { put("expiryMs", it) }
                        put("desc", r.conditionDescription)
                        put("active", r.isActive)
                        put("triggered", r.isTriggered)
                        put("createdMs", r.createdAtMs)
                        put("createdPkt", r.createdAtPkt)
                    }
                )
            }
            prefs?.edit()?.putString(KEY_TRADE_ALERT_RULES, arr.toString())?.apply()
        } catch (_: Exception) {
        }
    }

    private fun loadTradeAlertRulesFromStorage() {
        val raw = prefs?.getString(KEY_TRADE_ALERT_RULES, null) ?: return
        try {
            val arr = JSONArray(raw)
            val list = mutableListOf<TradeAlertRule>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val dir = FuturesDirection.entries.firstOrNull { it.name == o.optString("direction") } ?: FuturesDirection.LONG
                val cat = AlertRuleCategory.entries.firstOrNull { it.name == o.optString("category") } ?: AlertRuleCategory.PRICE_TARGET
                list.add(
                    TradeAlertRule(
                        ruleId = o.optString("ruleId"),
                        signalId = o.optString("signalId"),
                        symbol = o.optString("symbol"),
                        direction = dir,
                        category = cat,
                        triggerPrice = if (o.has("triggerPrice")) o.optDouble("triggerPrice") else null,
                        expiryTimestampMs = if (o.has("expiryMs")) o.optLong("expiryMs") else null,
                        conditionDescription = o.optString("desc"),
                        isActive = o.optBoolean("active", true),
                        isTriggered = o.optBoolean("triggered", false),
                        createdAtMs = o.optLong("createdMs", 0L),
                        createdAtPkt = o.optString("createdPkt")
                    )
                )
            }
            _tradeAlertRules.value = list
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "FuturesScannerRepo"
        private const val PREFS_NAME = "nexus_futures_scanner_prefs"
        private const val KEY_WATCHLIST = "futures_watchlist_json"
        private const val KEY_HISTORY = "futures_history_json"
        private const val KEY_STRICT_92 = "strict_92_confidence_mode"
        private const val KEY_BUDGET_CONFIG = "budget_config_json"
        private const val KEY_PAPER_ORDERS = "paper_orders_json"
        private const val KEY_DAILY_LEDGER = "daily_risk_ledger_json"
        private const val KEY_TRADE_ALERT_RULES = "trade_alert_rules_json"

        // Singleton instance holder for service and UI coordination
        @Volatile
        private var instance: FuturesScannerRepository? = null

        fun getInstance(context: Context? = null, notificationManager: NexusNotificationManager? = null): FuturesScannerRepository {
            return instance ?: synchronized(this) {
                instance ?: FuturesScannerRepository(context?.applicationContext ?: context, notificationManager).also { instance = it }
            }
        }
    }
}
