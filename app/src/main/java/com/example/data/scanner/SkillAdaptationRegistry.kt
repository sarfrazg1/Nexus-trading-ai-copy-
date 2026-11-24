package com.example.data.scanner

enum class SkillAdaptationStatus(val badgeLabel: String) {
    ACTIVE_ADAPTED("Active (Crypto Adapted)"),
    REQUIRES_EXTERNAL_CONFIG("Requires External Config"),
    NOT_APPLICABLE("Not Applicable (Stocks Only)")
}

enum class DayTraderSkillId {
    CHART_SCANNER,
    NEWS_MONITOR,
    ENTRY_TIMER,
    EXIT_PLANNER,
    TIME_MACHINE,
    RISK_MANAGER,
    BUDGET_MANAGER,
    ORDER_EXECUTOR,
    PRICE_ALERTS,
    EARNINGS_ANALYST,
    PELOSI_TRACKER
}

data class AdaptedSkillInfo(
    val id: DayTraderSkillId,
    val originalName: String,
    val nexusModuleName: String,
    val status: SkillAdaptationStatus,
    val adaptationSummary: String,
    val honestDisclosure: String
)

/**
 * Documents how all 11 methodologies from Jakeschincariol/day-trader-skill
 * are adapted to 24/7 Cryptocurrency & Binance USDT-M Perpetual Futures,
 * and explicitly marks US equity-only features (Earnings Analyst & Pelosi Tracker)
 * without fabricating data.
 */
object SkillAdaptationRegistry {

    val allAdaptedSkills: List<AdaptedSkillInfo> = listOf(
        AdaptedSkillInfo(
            id = DayTraderSkillId.CHART_SCANNER,
            originalName = "1. Chart Scanner",
            nexusModuleName = "BinanceFuturesScannerEngine + MarketScannerEngine",
            status = SkillAdaptationStatus.ACTIVE_ADAPTED,
            adaptationSummary = "Scans completed 15m primary candles with 1h/4h confluence across Binance USDT-M perpetuals for breakouts, 4h UTC session range breakouts, bull/bear flags, oversold bounces, and volume-confirmed S/R.",
            honestDisclosure = "Uses completed OHLCV bars only; never treats live wicks as confirmed breakouts."
        ),
        AdaptedSkillInfo(
            id = DayTraderSkillId.NEWS_MONITOR,
            originalName = "2. News Monitor",
            nexusModuleName = "FinancialNewsService + NewsImpactAnalyzer + NewsTranslationService",
            status = SkillAdaptationStatus.ACTIVE_ADAPTED,
            adaptationSummary = "Monitors crypto, BTC/ETH, Fed/FOMC, CPI/PPI, ETF flows, and regulatory dispatches via GDELT, separates Confirmed Facts vs AI Interpretation, fetches complete text where permitted, and translates into English / Roman Urdu / Urdu Script.",
            honestDisclosure = "Partial/summary-only articles are explicitly labeled; headlines are never claimed as proven price causality."
        ),
        AdaptedSkillInfo(
            id = DayTraderSkillId.ENTRY_TIMER,
            originalName = "3. Entry Timer",
            nexusModuleName = "EntryTimerEngine",
            status = SkillAdaptationStatus.ACTIVE_ADAPTED,
            adaptationSummary = "Evaluates bar-by-bar candle timing on 15m/5m completed bars, RSI pullback/extension, VWAP alignment, and funding extremes before flagging READY vs EXTENDED_WAIT vs OVEREXTENDED.",
            honestDisclosure = "Outputs PKT (Asia/Karachi) close timestamps and invalidation triggers."
        ),
        AdaptedSkillInfo(
            id = DayTraderSkillId.EXIT_PLANNER,
            originalName = "4. Exit Planner",
            nexusModuleName = "ExitPlannerEngine",
            status = SkillAdaptationStatus.ACTIVE_ADAPTED,
            adaptationSummary = "Pre-calculates Stop-Loss, TP1/TP2/TP3 ladder, breakeven shift threshold, structural invalidation, and ATR trailing rules prior to trade entry.",
            honestDisclosure = "Includes explicit Binance taker fee and slippage sensitivity assumptions."
        ),
        AdaptedSkillInfo(
            id = DayTraderSkillId.TIME_MACHINE,
            originalName = "5. Time Machine",
            nexusModuleName = "TimeMachineEngine (Backtest + 10k Monte Carlo)",
            status = SkillAdaptationStatus.ACTIVE_ADAPTED,
            adaptationSummary = "Walk-forward anti-look-ahead backtest on real Binance historical OHLCV with configurable fees/slippage plus reproducible 10,000-iteration bootstrap Monte Carlo.",
            honestDisclosure = "Same-candle TP+SL hits are marked AMBIGUOUS conservatively; simulated probabilities are never guaranteed win rates."
        ),
        AdaptedSkillInfo(
            id = DayTraderSkillId.RISK_MANAGER,
            originalName = "6. Risk Manager",
            nexusModuleName = "RiskManagerEngine (12-Point Guard)",
            status = SkillAdaptationStatus.ACTIVE_ADAPTED,
            adaptationSummary = "Checks max risk, daily loss limit, max trades/day, step/min qty, leverage limit, margin, R:R, balance, entry validity, SL distance, liquidation buffer, and open exposure.",
            honestDisclosure = "Emits PASS / WARNING / BLOCK with exact reasons; never silently overrides a block."
        ),
        AdaptedSkillInfo(
            id = DayTraderSkillId.BUDGET_MANAGER,
            originalName = "7. Budget Manager",
            nexusModuleName = "BudgetManagerEngine",
            status = SkillAdaptationStatus.ACTIVE_ADAPTED,
            adaptationSummary = "Computes exact Binance Futures contract quantity from USDT or PKR risk budget, stop-loss distance, leverage limit, fees, and slippage.",
            honestDisclosure = "Explicitly warns that leverage lowers posted margin but never lowers dollar loss and magnifies liquidation risk."
        ),
        AdaptedSkillInfo(
            id = DayTraderSkillId.ORDER_EXECUTOR,
            originalName = "8. Order Executor",
            nexusModuleName = "PaperOrderBrokerEngine (Safe Mode Default)",
            status = SkillAdaptationStatus.ACTIVE_ADAPTED,
            adaptationSummary = "Routes approved plans to simulated Paper Trading with explicit user confirmation. Live exchange API execution remains disabled in client safe mode.",
            honestDisclosure = "Requires Risk != BLOCK + manual confirmation; real-money orders cannot auto-fire."
        ),
        AdaptedSkillInfo(
            id = DayTraderSkillId.PRICE_ALERTS,
            originalName = "9. Price Alerts",
            nexusModuleName = "AlertMonitorEngine + BackgroundAlertWorker",
            status = SkillAdaptationStatus.ACTIVE_ADAPTED,
            adaptationSummary = "Monitors price targets, TP/SL lifecycle, setup triggers, and macro/news dispatch alerts with PKT timestamps and FCM support.",
            honestDisclosure = "Background polling depends on Android WorkManager & battery exemption."
        ),
        AdaptedSkillInfo(
            id = DayTraderSkillId.EARNINGS_ANALYST,
            originalName = "10. Earnings Analyst",
            nexusModuleName = "DefiLlama Protocol TVL + Token Unlock Stub",
            status = SkillAdaptationStatus.REQUIRES_EXTERNAL_CONFIG,
            adaptationSummary = "US equity quarterly 10-Q/10-K earnings do not exist on Binance perpetual contracts. Crypto fundamental health relies on DefiLlama TVL, stablecoin liquidity, and exchange Proof-of-Reserves.",
            honestDisclosure = "Dedicated token-unlock / cliff-vesting calendars require an external token-unlock API configuration; no synthetic unlock or EPS data is ever generated."
        ),
        AdaptedSkillInfo(
            id = DayTraderSkillId.PELOSI_TRACKER,
            originalName = "11. Pelosi Tracker",
            nexusModuleName = "US Congressional Disclosure Module (Disabled)",
            status = SkillAdaptationStatus.NOT_APPLICABLE,
            adaptationSummary = "US House/Senate STOCK Act equity disclosures track US public stocks with 30–45 day delays and do not map to 24/7 Binance USDT-M crypto futures.",
            honestDisclosure = "Not applicable to cryptocurrency futures. Disabled transparently — never simulated or substituted with fake insider flows."
        )
    )

    fun getSkill(id: DayTraderSkillId): AdaptedSkillInfo =
        allAdaptedSkills.first { it.id == id }
}
