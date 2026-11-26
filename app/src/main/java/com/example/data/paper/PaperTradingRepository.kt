package com.example.data.paper

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thread-safe persistent repository for NEXUS Paper Trading Simulator.
 * Persists virtual wallet balance, config, pending limit orders, open positions,
 * and closed trade history to SharedPreferences via org.json.
 */
class PaperTradingRepository(context: Context? = null) {

    private val prefs: SharedPreferences? =
        context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    private val _portfolio = MutableStateFlow(loadInitialSnapshot())
    val portfolio: StateFlow<PaperPortfolioSnapshot> = _portfolio.asStateFlow()

    companion object {
        private const val PREFS_NAME = "nexus_paper_trading_prefs"
        private const val KEY_PORTFOLIO_JSON = "paper_portfolio_v1"
        private const val MAX_CLOSED_HISTORY = 250
    }

    private fun loadInitialSnapshot(): PaperPortfolioSnapshot {
        val raw = prefs?.getString(KEY_PORTFOLIO_JSON, null)
        if (raw.isNullOrBlank()) {
            return PaperTradingEngine.resetWallet(10_000.0)
        }
        return decodeSnapshot(raw) ?: PaperTradingEngine.resetWallet(10_000.0)
    }

    fun resetWallet(
        initialBalanceUsdt: Double = 10_000.0,
        configOverride: PaperWalletConfig? = null
    ): PaperPortfolioSnapshot = synchronized(lock) {
        val fresh = PaperTradingEngine.resetWallet(initialBalanceUsdt, configOverride)
        commitLocked(fresh)
        fresh
    }

    fun placeMarketOrder(
        symbol: String,
        side: PaperOrderSide,
        currentMarketPrice: Double,
        quantity: Double,
        leverage: Int,
        stopLoss: Double? = null,
        takeProfit: Double? = null,
        sourceSignalId: String? = null,
        rrLabel: String? = null
    ): PaperOrderActionResult = synchronized(lock) {
        val result = PaperTradingEngine.placeMarketOrder(
            snapshot = _portfolio.value,
            symbol = symbol,
            side = side,
            currentMarketPrice = currentMarketPrice,
            quantity = quantity,
            leverage = leverage,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            sourceSignalId = sourceSignalId,
            rrLabel = rrLabel
        )
        if (result is PaperOrderActionResult.Success) {
            commitLocked(result.snapshot)
        }
        result
    }

    fun placeLimitOrder(
        symbol: String,
        side: PaperOrderSide,
        limitPrice: Double,
        quantity: Double,
        leverage: Int,
        stopLoss: Double? = null,
        takeProfit: Double? = null,
        sourceSignalId: String? = null,
        rrLabel: String? = null
    ): PaperOrderActionResult = synchronized(lock) {
        val result = PaperTradingEngine.placeLimitOrder(
            snapshot = _portfolio.value,
            symbol = symbol,
            side = side,
            limitPrice = limitPrice,
            quantity = quantity,
            leverage = leverage,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            sourceSignalId = sourceSignalId,
            rrLabel = rrLabel
        )
        if (result is PaperOrderActionResult.Success) {
            commitLocked(result.snapshot)
        }
        result
    }

    fun editPendingOrder(
        orderId: String,
        newLimitPrice: Double,
        newQuantity: Double,
        newStopLoss: Double?,
        newTakeProfit: Double?
    ): PaperOrderActionResult = synchronized(lock) {
        val result = PaperTradingEngine.editPendingOrder(
            snapshot = _portfolio.value,
            orderId = orderId,
            newLimitPrice = newLimitPrice,
            newQuantity = newQuantity,
            newStopLoss = newStopLoss,
            newTakeProfit = newTakeProfit
        )
        if (result is PaperOrderActionResult.Success) {
            commitLocked(result.snapshot)
        }
        result
    }

    fun cancelPendingOrder(orderId: String): PaperOrderActionResult = synchronized(lock) {
        val result = PaperTradingEngine.cancelPendingOrder(_portfolio.value, orderId)
        if (result is PaperOrderActionResult.Success) {
            commitLocked(result.snapshot)
        }
        result
    }

    fun updatePositionSlTp(
        positionId: String,
        newStopLoss: Double?,
        newTakeProfit: Double?
    ): PaperOrderActionResult = synchronized(lock) {
        val result = PaperTradingEngine.updatePositionSlTp(
            snapshot = _portfolio.value,
            positionId = positionId,
            newStopLoss = newStopLoss,
            newTakeProfit = newTakeProfit
        )
        if (result is PaperOrderActionResult.Success) {
            commitLocked(result.snapshot)
        }
        result
    }

    fun closePositionManually(
        positionId: String,
        currentMarketPrice: Double
    ): PaperOrderActionResult = synchronized(lock) {
        val result = PaperTradingEngine.closePositionManually(
            snapshot = _portfolio.value,
            positionId = positionId,
            currentMarketPrice = currentMarketPrice
        )
        if (result is PaperOrderActionResult.Success) {
            commitLocked(result.snapshot)
        }
        result
    }

    fun onLivePricesUpdated(pricesBySymbol: Map<String, Double>) {
        if (pricesBySymbol.isEmpty()) return
        synchronized(lock) {
            val current = _portfolio.value
            if (current.openPositions.isEmpty() && current.pendingOrders.isEmpty()) {
                return
            }
            val next = PaperTradingEngine.onMarketPricesUpdated(current, pricesBySymbol)
            commitLocked(next)
        }
    }

    private fun commitLocked(snapshot: PaperPortfolioSnapshot) {
        val trimmed = if (snapshot.closedTrades.size > MAX_CLOSED_HISTORY) {
            snapshot.copy(closedTrades = snapshot.closedTrades.take(MAX_CLOSED_HISTORY))
        } else {
            snapshot
        }
        _portfolio.value = trimmed
        prefs?.edit()?.putString(KEY_PORTFOLIO_JSON, encodeSnapshot(trimmed))?.apply()
    }

    private fun encodeSnapshot(s: PaperPortfolioSnapshot): String {
        val root = JSONObject().apply {
            put("availableBalanceUsdt", s.availableBalanceUsdt)
            put("lastPriceTickMs", s.lastPriceTickMs)
            put("lastPriceTickPkt", s.lastPriceTickPkt)
            put("lastActionMessage", s.lastActionMessage ?: JSONObject.NULL)
            put("config", JSONObject().apply {
                put("initialBalanceUsdt", s.config.initialBalanceUsdt)
                put("takerFeePercentPerSide", s.config.takerFeePercentPerSide)
                put("makerFeePercentPerSide", s.config.makerFeePercentPerSide)
                put("slippagePercentPerSide", s.config.slippagePercentPerSide)
                put("fundingRatePer8hPercent", s.config.fundingRatePer8hPercent)
            })
        }

        val pendingArr = JSONArray()
        s.pendingOrders.forEach { o ->
            pendingArr.put(JSONObject().apply {
                put("id", o.id)
                put("symbol", o.symbol)
                put("baseAsset", o.baseAsset)
                put("side", o.side.name)
                put("limitPrice", o.limitPrice)
                put("quantity", o.quantity)
                put("leverage", o.leverage)
                put("reservedMarginUsdt", o.reservedMarginUsdt)
                put("reservedOpeningFeeUsdt", o.reservedOpeningFeeUsdt)
                put("stopLoss", o.stopLoss ?: JSONObject.NULL)
                put("takeProfit", o.takeProfit ?: JSONObject.NULL)
                put("status", o.status.name)
                put("createdAtMs", o.createdAtMs)
                put("createdAtPkt", o.createdAtPkt)
                put("updatedAtMs", o.updatedAtMs)
                put("sourceSignalId", o.sourceSignalId ?: JSONObject.NULL)
                put("rrLabel", o.rrLabel ?: JSONObject.NULL)
                put("statusNote", o.statusNote)
            })
        }
        root.put("pendingOrders", pendingArr)

        val openArr = JSONArray()
        s.openPositions.forEach { p ->
            openArr.put(JSONObject().apply {
                put("id", p.id)
                put("symbol", p.symbol)
                put("baseAsset", p.baseAsset)
                put("side", p.side.name)
                put("orderType", p.orderType.name)
                put("quantity", p.quantity)
                put("entryPrice", p.entryPrice)
                put("marketEntryReferencePrice", p.marketEntryReferencePrice)
                put("leverage", p.leverage)
                put("marginUsedUsdt", p.marginUsedUsdt)
                put("notionalUsdt", p.notionalUsdt)
                put("stopLoss", p.stopLoss ?: JSONObject.NULL)
                put("takeProfit", p.takeProfit ?: JSONObject.NULL)
                put("liquidationPrice", p.liquidationPrice)
                put("openingFeeUsdt", p.openingFeeUsdt)
                put("entrySlippageCostUsdt", p.entrySlippageCostUsdt)
                put("accumulatedFundingUsdt", p.accumulatedFundingUsdt)
                put("lastFundingAccrualMs", p.lastFundingAccrualMs)
                put("currentMarkPrice", p.currentMarkPrice)
                put("grossUnrealizedPnlUsdt", p.grossUnrealizedPnlUsdt)
                put("netUnrealizedPnlUsdt", p.netUnrealizedPnlUsdt)
                put("openedAtMs", p.openedAtMs)
                put("openedAtPkt", p.openedAtPkt)
                put("sourceSignalId", p.sourceSignalId ?: JSONObject.NULL)
                put("rrLabel", p.rrLabel ?: JSONObject.NULL)
            })
        }
        root.put("openPositions", openArr)

        val closedArr = JSONArray()
        s.closedTrades.forEach { t ->
            closedArr.put(JSONObject().apply {
                put("id", t.id)
                put("symbol", t.symbol)
                put("baseAsset", t.baseAsset)
                put("side", t.side.name)
                put("orderType", t.orderType.name)
                put("quantity", t.quantity)
                put("entryPrice", t.entryPrice)
                put("exitPrice", t.exitPrice)
                put("leverage", t.leverage)
                put("marginUsedUsdt", t.marginUsedUsdt)
                put("openingFeeUsdt", t.openingFeeUsdt)
                put("closingFeeUsdt", t.closingFeeUsdt)
                put("entrySlippageCostUsdt", t.entrySlippageCostUsdt)
                put("exitSlippageCostUsdt", t.exitSlippageCostUsdt)
                put("fundingCostUsdt", t.fundingCostUsdt)
                put("grossPnlUsdt", t.grossPnlUsdt)
                put("netPnlUsdt", t.netPnlUsdt)
                put("netRoiPercent", t.netRoiPercent)
                put("netRMultiple", t.netRMultiple ?: JSONObject.NULL)
                put("exitReason", t.exitReason.name)
                put("openedAtMs", t.openedAtMs)
                put("openedAtPkt", t.openedAtPkt)
                put("closedAtMs", t.closedAtMs)
                put("closedAtPkt", t.closedAtPkt)
                put("closedDatePkt", t.closedDatePkt)
                put("sourceSignalId", t.sourceSignalId ?: JSONObject.NULL)
            })
        }
        root.put("closedTrades", closedArr)

        return root.toString()
    }

    private fun decodeSnapshot(raw: String): PaperPortfolioSnapshot? {
        return try {
            val root = JSONObject(raw)
            val cfgObj = root.optJSONObject("config")
            val config = PaperWalletConfig(
                initialBalanceUsdt = cfgObj?.optDouble("initialBalanceUsdt", 10_000.0) ?: 10_000.0,
                takerFeePercentPerSide = cfgObj?.optDouble("takerFeePercentPerSide", 0.05) ?: 0.05,
                makerFeePercentPerSide = cfgObj?.optDouble("makerFeePercentPerSide", 0.02) ?: 0.02,
                slippagePercentPerSide = cfgObj?.optDouble("slippagePercentPerSide", 0.03) ?: 0.03,
                fundingRatePer8hPercent = cfgObj?.optDouble("fundingRatePer8hPercent", 0.01) ?: 0.01
            )

            val pending = mutableListOf<PaperPendingOrder>()
            val pendingArr = root.optJSONArray("pendingOrders") ?: JSONArray()
            for (i in 0 until pendingArr.length()) {
                val o = pendingArr.optJSONObject(i) ?: continue
                pending.add(
                    PaperPendingOrder(
                        id = o.optString("id"),
                        symbol = o.optString("symbol"),
                        baseAsset = o.optString("baseAsset"),
                        side = PaperOrderSide.valueOf(o.optString("side", "LONG")),
                        limitPrice = o.optDouble("limitPrice"),
                        quantity = o.optDouble("quantity"),
                        leverage = o.optInt("leverage", 3),
                        reservedMarginUsdt = o.optDouble("reservedMarginUsdt"),
                        reservedOpeningFeeUsdt = o.optDouble("reservedOpeningFeeUsdt"),
                        stopLoss = o.optNullableDouble("stopLoss"),
                        takeProfit = o.optNullableDouble("takeProfit"),
                        status = PendingLimitStatus.valueOf(o.optString("status", "PENDING")),
                        createdAtMs = o.optLong("createdAtMs"),
                        createdAtPkt = o.optString("createdAtPkt"),
                        updatedAtMs = o.optLong("updatedAtMs"),
                        sourceSignalId = o.optNullableString("sourceSignalId"),
                        rrLabel = o.optNullableString("rrLabel"),
                        statusNote = o.optString("statusNote")
                    )
                )
            }

            val openPositions = mutableListOf<PaperPosition>()
            val openArr = root.optJSONArray("openPositions") ?: JSONArray()
            for (i in 0 until openArr.length()) {
                val p = openArr.optJSONObject(i) ?: continue
                openPositions.add(
                    PaperPosition(
                        id = p.optString("id"),
                        symbol = p.optString("symbol"),
                        baseAsset = p.optString("baseAsset"),
                        side = PaperOrderSide.valueOf(p.optString("side", "LONG")),
                        orderType = PaperOrderType.valueOf(p.optString("orderType", "MARKET")),
                        quantity = p.optDouble("quantity"),
                        entryPrice = p.optDouble("entryPrice"),
                        marketEntryReferencePrice = p.optDouble("marketEntryReferencePrice"),
                        leverage = p.optInt("leverage", 3),
                        marginUsedUsdt = p.optDouble("marginUsedUsdt"),
                        notionalUsdt = p.optDouble("notionalUsdt"),
                        stopLoss = p.optNullableDouble("stopLoss"),
                        takeProfit = p.optNullableDouble("takeProfit"),
                        liquidationPrice = p.optDouble("liquidationPrice"),
                        openingFeeUsdt = p.optDouble("openingFeeUsdt"),
                        entrySlippageCostUsdt = p.optDouble("entrySlippageCostUsdt"),
                        accumulatedFundingUsdt = p.optDouble("accumulatedFundingUsdt"),
                        lastFundingAccrualMs = p.optLong("lastFundingAccrualMs"),
                        currentMarkPrice = p.optDouble("currentMarkPrice"),
                        grossUnrealizedPnlUsdt = p.optDouble("grossUnrealizedPnlUsdt"),
                        netUnrealizedPnlUsdt = p.optDouble("netUnrealizedPnlUsdt"),
                        openedAtMs = p.optLong("openedAtMs"),
                        openedAtPkt = p.optString("openedAtPkt"),
                        sourceSignalId = p.optNullableString("sourceSignalId"),
                        rrLabel = p.optNullableString("rrLabel")
                    )
                )
            }

            val closedTrades = mutableListOf<PaperClosedTrade>()
            val closedArr = root.optJSONArray("closedTrades") ?: JSONArray()
            for (i in 0 until closedArr.length()) {
                val t = closedArr.optJSONObject(i) ?: continue
                closedTrades.add(
                    PaperClosedTrade(
                        id = t.optString("id"),
                        symbol = t.optString("symbol"),
                        baseAsset = t.optString("baseAsset"),
                        side = PaperOrderSide.valueOf(t.optString("side", "LONG")),
                        orderType = PaperOrderType.valueOf(t.optString("orderType", "MARKET")),
                        quantity = t.optDouble("quantity"),
                        entryPrice = t.optDouble("entryPrice"),
                        exitPrice = t.optDouble("exitPrice"),
                        leverage = t.optInt("leverage", 3),
                        marginUsedUsdt = t.optDouble("marginUsedUsdt"),
                        openingFeeUsdt = t.optDouble("openingFeeUsdt"),
                        closingFeeUsdt = t.optDouble("closingFeeUsdt"),
                        entrySlippageCostUsdt = t.optDouble("entrySlippageCostUsdt"),
                        exitSlippageCostUsdt = t.optDouble("exitSlippageCostUsdt"),
                        fundingCostUsdt = t.optDouble("fundingCostUsdt"),
                        grossPnlUsdt = t.optDouble("grossPnlUsdt"),
                        netPnlUsdt = t.optDouble("netPnlUsdt"),
                        netRoiPercent = t.optDouble("netRoiPercent"),
                        netRMultiple = t.optNullableDouble("netRMultiple"),
                        exitReason = PaperExitReason.valueOf(t.optString("exitReason", "MANUAL_CLOSE")),
                        openedAtMs = t.optLong("openedAtMs"),
                        openedAtPkt = t.optString("openedAtPkt"),
                        closedAtMs = t.optLong("closedAtMs"),
                        closedAtPkt = t.optString("closedAtPkt"),
                        closedDatePkt = t.optString("closedDatePkt"),
                        sourceSignalId = t.optNullableString("sourceSignalId")
                    )
                )
            }

            val rawSnap = PaperPortfolioSnapshot(
                config = config,
                availableBalanceUsdt = root.optDouble("availableBalanceUsdt", config.initialBalanceUsdt),
                pendingOrders = pending,
                openPositions = openPositions,
                closedTrades = closedTrades,
                lastPriceTickMs = root.optLong("lastPriceTickMs", 0L),
                lastPriceTickPkt = root.optString("lastPriceTickPkt", "Awaiting live tick"),
                lastActionMessage = root.optNullableString("lastActionMessage")
            )
            PaperTradingEngine.recalculateSnapshot(rawSnap)
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (isNull(key) || !has(key)) return null
        val v = optDouble(key, Double.NaN)
        return if (v.isFinite()) v else null
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key) || !has(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }
}
