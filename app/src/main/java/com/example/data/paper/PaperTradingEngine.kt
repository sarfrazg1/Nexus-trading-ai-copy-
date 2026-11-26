package com.example.data.paper

import com.example.util.TimeZoneUtils
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure deterministic Paper Trading Simulator Engine.
 * - Supports configurable Virtual Wallet, Market Orders, and Limit Orders (LONG & SHORT).
 * - Enforces strict Limit Order execution: NEVER fills immediately unless verified market price crosses limit.
 * - Models fees (maker/taker), estimated market slippage, 8-hour funding cost accrual, and liquidation price.
 * - Separates Gross PnL and Net PnL (after opening/closing fees, slippage, and funding).
 * - Computes Win Rate, Profit Factor, Max Drawdown %, Today's PKT PnL, and Long/Short metrics.
 */
object PaperTradingEngine {

    private const val EIGHT_HOURS_MS = 8L * 3_600_000L
    private const val MAINTENANCE_MARGIN_RATE = 0.005 // 0.5% isolated maintenance margin

    fun normalizePairSymbol(rawSymbol: String): String {
        val upper = rawSymbol.uppercase(Locale.US).trim().replace("/", "")
        return when {
            upper.endsWith("USDT") || upper.endsWith("USD") -> upper
            else -> "${upper}USDT"
        }
    }

    fun extractBaseAsset(pairSymbol: String): String {
        val norm = normalizePairSymbol(pairSymbol)
        return norm.removeSuffix("USDT").removeSuffix("USD")
    }

    fun calculateLiquidationPrice(
        side: PaperOrderSide,
        entryPrice: Double,
        leverage: Int
    ): Double {
        val lev = leverage.coerceIn(1, 20).toDouble()
        val marginFraction = 1.0 / lev
        return if (side == PaperOrderSide.LONG) {
            (entryPrice * (1.0 - marginFraction + MAINTENANCE_MARGIN_RATE)).coerceAtLeast(0.0001)
        } else {
            entryPrice * (1.0 + marginFraction - MAINTENANCE_MARGIN_RATE)
        }
    }

    fun resetWallet(
        initialBalanceUsdt: Double,
        configOverride: PaperWalletConfig? = null,
        nowMs: Long = System.currentTimeMillis()
    ): PaperPortfolioSnapshot {
        val safeBalance = initialBalanceUsdt.coerceIn(100.0, 10_000_000.0)
        val cfg = (configOverride ?: PaperWalletConfig()).copy(initialBalanceUsdt = safeBalance)
        return recalculateSnapshot(
            PaperPortfolioSnapshot(
                config = cfg,
                availableBalanceUsdt = safeBalance,
                equityUsdt = safeBalance,
                lastPriceTickMs = nowMs,
                lastPriceTickPkt = TimeZoneUtils.formatKarachiWithSeconds(nowMs),
                lastActionMessage = "Paper wallet initialized at ${formatUsdt(safeBalance)} USDT"
            ),
            nowMs = nowMs
        )
    }

    /**
     * Simulates an immediate Market Order fill at verified live market price + slippage + taker fee.
     */
    fun placeMarketOrder(
        snapshot: PaperPortfolioSnapshot,
        symbol: String,
        side: PaperOrderSide,
        currentMarketPrice: Double,
        quantity: Double,
        leverage: Int,
        stopLoss: Double? = null,
        takeProfit: Double? = null,
        sourceSignalId: String? = null,
        rrLabel: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): PaperOrderActionResult {
        if (!currentMarketPrice.isFinite() || currentMarketPrice <= 0.0) {
            return PaperOrderActionResult.Rejected("Verified live market price is unavailable or invalid.")
        }
        if (!quantity.isFinite() || quantity <= 0.0) {
            return PaperOrderActionResult.Rejected("Order quantity must be greater than zero.")
        }
        val lev = leverage.coerceIn(1, 20)
        val normSymbol = normalizePairSymbol(symbol)
        val base = extractBaseAsset(normSymbol)

        val slipFrac = snapshot.config.slippagePercentPerSide.coerceAtLeast(0.0) / 100.0
        val takerFeeFrac = snapshot.config.takerFeePercentPerSide.coerceAtLeast(0.0) / 100.0

        val fillPrice = if (side == PaperOrderSide.LONG) {
            currentMarketPrice * (1.0 + slipFrac)
        } else {
            currentMarketPrice * (1.0 - slipFrac)
        }

        val slTpValidation = validateSlTp(side, fillPrice, stopLoss, takeProfit)
        if (slTpValidation != null) return PaperOrderActionResult.Rejected(slTpValidation)

        val notional = quantity * fillPrice
        val requiredMargin = notional / lev.toDouble()
        val openingFee = notional * takerFeeFrac
        val entrySlippageCost = abs(fillPrice - currentMarketPrice) * quantity
        val totalRequired = requiredMargin + openingFee

        if (totalRequired > snapshot.availableBalanceUsdt + 1e-6) {
            return PaperOrderActionResult.Rejected(
                "Insufficient available paper balance (${formatUsdt(snapshot.availableBalanceUsdt)}). Required margin + fee: ${formatUsdt(totalRequired)}."
            )
        }

        val liqPrice = calculateLiquidationPrice(side, fillPrice, lev)
        if (side == PaperOrderSide.LONG && stopLoss != null && stopLoss <= liqPrice) {
            return PaperOrderActionResult.Rejected(
                "Stop Loss ($${fmtPrice(stopLoss)}) is below isolated liquidation price ($${fmtPrice(liqPrice)}). Reduce leverage or tighten SL."
            )
        }
        if (side == PaperOrderSide.SHORT && stopLoss != null && stopLoss >= liqPrice) {
            return PaperOrderActionResult.Rejected(
                "Stop Loss ($${fmtPrice(stopLoss)}) is above isolated liquidation price ($${fmtPrice(liqPrice)}). Reduce leverage or tighten SL."
            )
        }

        val rawPos = PaperPosition(
            symbol = normSymbol,
            baseAsset = base,
            side = side,
            orderType = PaperOrderType.MARKET,
            quantity = quantity,
            entryPrice = fillPrice,
            marketEntryReferencePrice = currentMarketPrice,
            leverage = lev,
            marginUsedUsdt = requiredMargin,
            notionalUsdt = notional,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            liquidationPrice = liqPrice,
            openingFeeUsdt = openingFee,
            entrySlippageCostUsdt = entrySlippageCost,
            accumulatedFundingUsdt = 0.0,
            lastFundingAccrualMs = nowMs,
            currentMarkPrice = currentMarketPrice,
            openedAtMs = nowMs,
            sourceSignalId = sourceSignalId,
            rrLabel = rrLabel
        )
        val markedPos = updatePositionMarkToMarket(rawPos, currentMarketPrice, snapshot.config, nowMs)

        val updatedSnapshot = recalculateSnapshot(
            snapshot.copy(
                availableBalanceUsdt = (snapshot.availableBalanceUsdt - totalRequired).coerceAtLeast(0.0),
                openPositions = listOf(markedPos) + snapshot.openPositions,
                lastPriceTickMs = nowMs,
                lastPriceTickPkt = TimeZoneUtils.formatKarachiWithSeconds(nowMs),
                lastActionMessage = "MARKET ${side.name} filled: $normSymbol qty ${fmtQty(quantity)} @ $${fmtPrice(fillPrice)} (Fee ${formatUsdt(openingFee)})"
            ),
            nowMs = nowMs
        )
        return PaperOrderActionResult.Success(updatedSnapshot, updatedSnapshot.lastActionMessage!!)
    }

    /**
     * Places a Pending Limit Order.
     * STRICT RULE: Limit orders remain PENDING_LIMIT until verified live market price reaches the limit price
     * (LONG: market <= limit; SHORT: market >= limit). Never forces an instant fill at unreached limit.
     */
    fun placeLimitOrder(
        snapshot: PaperPortfolioSnapshot,
        symbol: String,
        side: PaperOrderSide,
        limitPrice: Double,
        quantity: Double,
        leverage: Int,
        stopLoss: Double? = null,
        takeProfit: Double? = null,
        sourceSignalId: String? = null,
        rrLabel: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): PaperOrderActionResult {
        if (!limitPrice.isFinite() || limitPrice <= 0.0) {
            return PaperOrderActionResult.Rejected("Limit price must be a positive finite number.")
        }
        if (!quantity.isFinite() || quantity <= 0.0) {
            return PaperOrderActionResult.Rejected("Limit order quantity must be greater than zero.")
        }
        val slTpValidation = validateSlTp(side, limitPrice, stopLoss, takeProfit)
        if (slTpValidation != null) return PaperOrderActionResult.Rejected(slTpValidation)

        val lev = leverage.coerceIn(1, 20)
        val normSymbol = normalizePairSymbol(symbol)
        val base = extractBaseAsset(normSymbol)
        val makerFeeFrac = snapshot.config.makerFeePercentPerSide.coerceAtLeast(0.0) / 100.0
        val notional = quantity * limitPrice
        val reservedMargin = notional / lev.toDouble()
        val reservedFee = notional * makerFeeFrac
        val totalReserve = reservedMargin + reservedFee

        if (totalReserve > snapshot.availableBalanceUsdt + 1e-6) {
            return PaperOrderActionResult.Rejected(
                "Insufficient available paper balance (${formatUsdt(snapshot.availableBalanceUsdt)}) to reserve ${formatUsdt(totalReserve)}."
            )
        }

        val liqPrice = calculateLiquidationPrice(side, limitPrice, lev)
        if (side == PaperOrderSide.LONG && stopLoss != null && stopLoss <= liqPrice) {
            return PaperOrderActionResult.Rejected("Stop Loss below estimated liquidation price ($${fmtPrice(liqPrice)}).")
        }
        if (side == PaperOrderSide.SHORT && stopLoss != null && stopLoss >= liqPrice) {
            return PaperOrderActionResult.Rejected("Stop Loss above estimated liquidation price ($${fmtPrice(liqPrice)}).")
        }

        val pending = PaperPendingOrder(
            symbol = normSymbol,
            baseAsset = base,
            side = side,
            limitPrice = limitPrice,
            quantity = quantity,
            leverage = lev,
            reservedMarginUsdt = reservedMargin,
            reservedOpeningFeeUsdt = reservedFee,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            status = PendingLimitStatus.PENDING,
            createdAtMs = nowMs,
            sourceSignalId = sourceSignalId,
            rrLabel = rrLabel,
            statusNote = if (side == PaperOrderSide.LONG) {
                "Waiting for price <= $${fmtPrice(limitPrice)}"
            } else {
                "Waiting for price >= $${fmtPrice(limitPrice)}"
            }
        )

        val updatedSnapshot = recalculateSnapshot(
            snapshot.copy(
                availableBalanceUsdt = (snapshot.availableBalanceUsdt - totalReserve).coerceAtLeast(0.0),
                pendingOrders = listOf(pending) + snapshot.pendingOrders,
                lastActionMessage = "LIMIT ${side.name} queued: $normSymbol qty ${fmtQty(quantity)} @ $${fmtPrice(limitPrice)}"
            ),
            nowMs = nowMs
        )
        return PaperOrderActionResult.Success(updatedSnapshot, updatedSnapshot.lastActionMessage!!)
    }

    /**
     * Edits an existing PENDING_LIMIT order (limit price, quantity, SL, TP) and adjusts reserved margin safely.
     */
    fun editPendingOrder(
        snapshot: PaperPortfolioSnapshot,
        orderId: String,
        newLimitPrice: Double,
        newQuantity: Double,
        newStopLoss: Double?,
        newTakeProfit: Double?,
        nowMs: Long = System.currentTimeMillis()
    ): PaperOrderActionResult {
        val existing = snapshot.pendingOrders.firstOrNull { it.id == orderId && it.status == PendingLimitStatus.PENDING }
            ?: return PaperOrderActionResult.Rejected("Pending limit order not found or no longer active.")

        if (!newLimitPrice.isFinite() || newLimitPrice <= 0.0 || !newQuantity.isFinite() || newQuantity <= 0.0) {
            return PaperOrderActionResult.Rejected("Updated limit price and quantity must be positive.")
        }
        val slTpErr = validateSlTp(existing.side, newLimitPrice, newStopLoss, newTakeProfit)
        if (slTpErr != null) return PaperOrderActionResult.Rejected(slTpErr)

        val makerFeeFrac = snapshot.config.makerFeePercentPerSide.coerceAtLeast(0.0) / 100.0
        val newNotional = newLimitPrice * newQuantity
        val newMargin = newNotional / existing.leverage.toDouble()
        val newFee = newNotional * makerFeeFrac
        val oldReserve = existing.reservedMarginUsdt + existing.reservedOpeningFeeUsdt
        val newReserve = newMargin + newFee
        val poolAfterRefund = snapshot.availableBalanceUsdt + oldReserve

        if (newReserve > poolAfterRefund + 1e-6) {
            return PaperOrderActionResult.Rejected(
                "Insufficient balance for edited size. Needed: ${formatUsdt(newReserve)}, Available w/ refund: ${formatUsdt(poolAfterRefund)}."
            )
        }

        val updatedOrder = existing.copy(
            limitPrice = newLimitPrice,
            quantity = newQuantity,
            reservedMarginUsdt = newMargin,
            reservedOpeningFeeUsdt = newFee,
            stopLoss = newStopLoss,
            takeProfit = newTakeProfit,
            updatedAtMs = nowMs,
            statusNote = "Edited (PKT ${TimeZoneUtils.formatKarachiCompact(nowMs)}): Limit $${fmtPrice(newLimitPrice)}"
        )

        val updatedList = snapshot.pendingOrders.map { if (it.id == orderId) updatedOrder else it }
        val updatedSnapshot = recalculateSnapshot(
            snapshot.copy(
                availableBalanceUsdt = (poolAfterRefund - newReserve).coerceAtLeast(0.0),
                pendingOrders = updatedList,
                lastActionMessage = "Edited Limit order ${existing.symbol} @ $${fmtPrice(newLimitPrice)}"
            ),
            nowMs = nowMs
        )
        return PaperOrderActionResult.Success(updatedSnapshot, updatedSnapshot.lastActionMessage!!)
    }

    /**
     * Cancels a PENDING_LIMIT order and returns reserved margin + fee to available balance.
     */
    fun cancelPendingOrder(
        snapshot: PaperPortfolioSnapshot,
        orderId: String,
        nowMs: Long = System.currentTimeMillis()
    ): PaperOrderActionResult {
        val existing = snapshot.pendingOrders.firstOrNull { it.id == orderId && it.status == PendingLimitStatus.PENDING }
            ?: return PaperOrderActionResult.Rejected("Pending order not found.")

        val refund = existing.reservedMarginUsdt + existing.reservedOpeningFeeUsdt
        val updatedPending = snapshot.pendingOrders.filterNot { it.id == orderId }
        val updatedSnapshot = recalculateSnapshot(
            snapshot.copy(
                availableBalanceUsdt = snapshot.availableBalanceUsdt + refund,
                pendingOrders = updatedPending,
                lastActionMessage = "Cancelled Limit ${existing.side.name} ${existing.symbol}; released ${formatUsdt(refund)}"
            ),
            nowMs = nowMs
        )
        return PaperOrderActionResult.Success(updatedSnapshot, updatedSnapshot.lastActionMessage!!)
    }

    /**
     * Updates SL / TP on an active open position.
     */
    fun updatePositionSlTp(
        snapshot: PaperPortfolioSnapshot,
        positionId: String,
        newStopLoss: Double?,
        newTakeProfit: Double?,
        nowMs: Long = System.currentTimeMillis()
    ): PaperOrderActionResult {
        val pos = snapshot.openPositions.firstOrNull { it.id == positionId }
            ?: return PaperOrderActionResult.Rejected("Open position not found.")

        val err = validateSlTp(pos.side, pos.entryPrice, newStopLoss, newTakeProfit)
        if (err != null) return PaperOrderActionResult.Rejected(err)

        val updatedPositions = snapshot.openPositions.map {
            if (it.id == positionId) it.copy(stopLoss = newStopLoss, takeProfit = newTakeProfit) else it
        }
        val updatedSnapshot = recalculateSnapshot(
            snapshot.copy(
                openPositions = updatedPositions,
                lastActionMessage = "Updated SL/TP for ${pos.symbol} ${pos.side.name}"
            ),
            nowMs = nowMs
        )
        return PaperOrderActionResult.Success(updatedSnapshot, updatedSnapshot.lastActionMessage!!)
    }

    /**
     * Manually closes an open paper position at verified live market price.
     */
    fun closePositionManually(
        snapshot: PaperPortfolioSnapshot,
        positionId: String,
        currentMarketPrice: Double,
        nowMs: Long = System.currentTimeMillis()
    ): PaperOrderActionResult {
        val pos = snapshot.openPositions.firstOrNull { it.id == positionId }
            ?: return PaperOrderActionResult.Rejected("Open position not found.")
        if (!currentMarketPrice.isFinite() || currentMarketPrice <= 0.0) {
            return PaperOrderActionResult.Rejected("Cannot close position without valid market price.")
        }

        val slipFrac = snapshot.config.slippagePercentPerSide.coerceAtLeast(0.0) / 100.0
        val exitFillPrice = if (pos.side == PaperOrderSide.LONG) {
            currentMarketPrice * (1.0 - slipFrac)
        } else {
            currentMarketPrice * (1.0 + slipFrac)
        }
        val exitSlipCost = abs(currentMarketPrice - exitFillPrice) * pos.quantity

        val (closedTrade, returnedBalance) = finalizeClosedTrade(
            position = pos,
            exitPrice = exitFillPrice,
            exitSlippageCostUsdt = exitSlipCost,
            exitReason = PaperExitReason.MANUAL_CLOSE,
            config = snapshot.config,
            nowMs = nowMs
        )

        val updatedSnapshot = recalculateSnapshot(
            snapshot.copy(
                availableBalanceUsdt = snapshot.availableBalanceUsdt + returnedBalance,
                openPositions = snapshot.openPositions.filterNot { it.id == positionId },
                closedTrades = listOf(closedTrade) + snapshot.closedTrades,
                lastPriceTickMs = nowMs,
                lastPriceTickPkt = TimeZoneUtils.formatKarachiWithSeconds(nowMs),
                lastActionMessage = "Closed ${pos.symbol} ${pos.side.name} @ $${fmtPrice(exitFillPrice)} | Net PnL ${formatUsdt(closedTrade.netPnlUsdt)}"
            ),
            nowMs = nowMs
        )
        return PaperOrderActionResult.Success(updatedSnapshot, updatedSnapshot.lastActionMessage!!)
    }

    /**
     * Evaluates verified live market prices against:
     * 1) Pending Limit Orders (fills strictly when price reaches limit: LONG <= limit, SHORT >= limit)
     * 2) Open Positions (mark-to-market Gross/Net PnL, funding accrual, and SL / TP / Liquidation triggers)
     */
    fun onMarketPricesUpdated(
        snapshot: PaperPortfolioSnapshot,
        pricesBySymbol: Map<String, Double>,
        nowMs: Long = System.currentTimeMillis()
    ): PaperPortfolioSnapshot {
        if (pricesBySymbol.isEmpty()) return snapshot

        val normalizedPrices = HashMap<String, Double>(pricesBySymbol.size)
        pricesBySymbol.forEach { (k, v) ->
            if (v.isFinite() && v > 0.0) {
                normalizedPrices[normalizePairSymbol(k)] = v
            }
        }
        if (normalizedPrices.isEmpty()) return snapshot

        var available = snapshot.availableBalanceUsdt
        val remainingPending = mutableListOf<PaperPendingOrder>()
        val openedFromLimit = mutableListOf<PaperPosition>()
        val actionNotes = mutableListOf<String>()

        // 1. Evaluate Pending Limit Orders
        for (order in snapshot.pendingOrders) {
            if (order.status != PendingLimitStatus.PENDING) continue
            val mktPrice = normalizedPrices[normalizePairSymbol(order.symbol)]
            val triggered = mktPrice != null && when (order.side) {
                PaperOrderSide.LONG -> mktPrice <= order.limitPrice
                PaperOrderSide.SHORT -> mktPrice >= order.limitPrice
            }
            if (triggered && mktPrice != null) {
                val fillPrice = order.limitPrice
                val liqPrice = calculateLiquidationPrice(order.side, fillPrice, order.leverage)
                val newPos = PaperPosition(
                    symbol = order.symbol,
                    baseAsset = order.baseAsset,
                    side = order.side,
                    orderType = PaperOrderType.LIMIT,
                    quantity = order.quantity,
                    entryPrice = fillPrice,
                    marketEntryReferencePrice = mktPrice,
                    leverage = order.leverage,
                    marginUsedUsdt = order.reservedMarginUsdt,
                    notionalUsdt = order.quantity * fillPrice,
                    stopLoss = order.stopLoss,
                    takeProfit = order.takeProfit,
                    liquidationPrice = liqPrice,
                    openingFeeUsdt = order.reservedOpeningFeeUsdt,
                    entrySlippageCostUsdt = 0.0,
                    accumulatedFundingUsdt = 0.0,
                    lastFundingAccrualMs = nowMs,
                    currentMarkPrice = mktPrice,
                    openedAtMs = nowMs,
                    sourceSignalId = order.sourceSignalId,
                    rrLabel = order.rrLabel
                )
                openedFromLimit.add(updatePositionMarkToMarket(newPos, mktPrice, snapshot.config, nowMs))
                actionNotes.add("LIMIT ${order.side.name} ${order.symbol} filled @ $${fmtPrice(fillPrice)}")
            } else {
                remainingPending.add(order)
            }
        }

        // 2. Evaluate Open Positions (including newly filled limit orders if needed on same tick)
        val remainingPositions = mutableListOf<PaperPosition>()
        val newlyClosed = mutableListOf<PaperClosedTrade>()

        for (pos in (openedFromLimit + snapshot.openPositions)) {
            val markPrice = normalizedPrices[normalizePairSymbol(pos.symbol)]
            if (markPrice == null) {
                remainingPositions.add(pos)
                continue
            }

            val updatedPos = updatePositionMarkToMarket(pos, markPrice, snapshot.config, nowMs)

            val hitLiquidation = when (updatedPos.side) {
                PaperOrderSide.LONG -> markPrice <= updatedPos.liquidationPrice
                PaperOrderSide.SHORT -> markPrice >= updatedPos.liquidationPrice
            }
            val hitSl = updatedPos.stopLoss != null && when (updatedPos.side) {
                PaperOrderSide.LONG -> markPrice <= updatedPos.stopLoss
                PaperOrderSide.SHORT -> markPrice >= updatedPos.stopLoss
            }
            val hitTp = updatedPos.takeProfit != null && when (updatedPos.side) {
                PaperOrderSide.LONG -> markPrice >= updatedPos.takeProfit
                PaperOrderSide.SHORT -> markPrice <= updatedPos.takeProfit
            }

            when {
                hitLiquidation -> {
                    val (closed, returned) = finalizeClosedTrade(
                        position = updatedPos,
                        exitPrice = updatedPos.liquidationPrice,
                        exitSlippageCostUsdt = 0.0,
                        exitReason = PaperExitReason.LIQUIDATED,
                        config = snapshot.config,
                        nowMs = nowMs
                    )
                    available += returned
                    newlyClosed.add(closed)
                    actionNotes.add("LIQUIDATED ${updatedPos.symbol} @ $${fmtPrice(updatedPos.liquidationPrice)}")
                }
                hitSl -> {
                    val slipFrac = snapshot.config.slippagePercentPerSide.coerceAtLeast(0.0) / 100.0
                    val slBase = updatedPos.stopLoss!!
                    val execSl = if (updatedPos.side == PaperOrderSide.LONG) slBase * (1.0 - slipFrac) else slBase * (1.0 + slipFrac)
                    val slSlip = abs(slBase - execSl) * updatedPos.quantity
                    val (closed, returned) = finalizeClosedTrade(
                        position = updatedPos,
                        exitPrice = execSl,
                        exitSlippageCostUsdt = slSlip,
                        exitReason = PaperExitReason.SL_HIT,
                        config = snapshot.config,
                        nowMs = nowMs
                    )
                    available += returned
                    newlyClosed.add(closed)
                    actionNotes.add("SL HIT ${updatedPos.symbol} (${formatUsdt(closed.netPnlUsdt)})")
                }
                hitTp -> {
                    val tpBase = updatedPos.takeProfit!!
                    val (closed, returned) = finalizeClosedTrade(
                        position = updatedPos,
                        exitPrice = tpBase,
                        exitSlippageCostUsdt = 0.0,
                        exitReason = PaperExitReason.TP_HIT,
                        config = snapshot.config,
                        nowMs = nowMs
                    )
                    available += returned
                    newlyClosed.add(closed)
                    actionNotes.add("TP HIT ${updatedPos.symbol} (${formatUsdt(closed.netPnlUsdt)})")
                }
                else -> remainingPositions.add(updatedPos)
            }
        }

        return recalculateSnapshot(
            snapshot.copy(
                availableBalanceUsdt = available,
                pendingOrders = remainingPending,
                openPositions = remainingPositions,
                closedTrades = newlyClosed + snapshot.closedTrades,
                lastPriceTickMs = nowMs,
                lastPriceTickPkt = TimeZoneUtils.formatKarachiWithSeconds(nowMs),
                lastActionMessage = actionNotes.firstOrNull() ?: snapshot.lastActionMessage
            ),
            nowMs = nowMs
        )
    }

    private fun updatePositionMarkToMarket(
        pos: PaperPosition,
        markPrice: Double,
        config: PaperWalletConfig,
        nowMs: Long
    ): PaperPosition {
        val elapsedMs = max(0L, nowMs - pos.lastFundingAccrualMs)
        val fundingPeriods = (elapsedMs / EIGHT_HOURS_MS).toInt()
        val addedFunding = if (fundingPeriods > 0) {
            val rateFrac = config.fundingRatePer8hPercent / 100.0
            (pos.quantity * markPrice) * rateFrac * fundingPeriods
        } else 0.0
        val newLastFundingMs = pos.lastFundingAccrualMs + (fundingPeriods.toLong() * EIGHT_HOURS_MS)
        val totalFunding = pos.accumulatedFundingUsdt + addedFunding

        val grossPnl = computeGrossPnl(pos.side, pos.entryPrice, markPrice, pos.quantity)
        val estCloseFee = (pos.quantity * markPrice) * (config.takerFeePercentPerSide / 100.0)
        val estCloseSlip = (pos.quantity * markPrice) * (config.slippagePercentPerSide / 100.0)
        val netPnl = grossPnl - pos.openingFeeUsdt - estCloseFee - pos.entrySlippageCostUsdt - estCloseSlip - totalFunding

        return pos.copy(
            currentMarkPrice = markPrice,
            accumulatedFundingUsdt = totalFunding,
            lastFundingAccrualMs = newLastFundingMs,
            grossUnrealizedPnlUsdt = grossPnl,
            netUnrealizedPnlUsdt = netPnl
        )
    }

    private fun finalizeClosedTrade(
        position: PaperPosition,
        exitPrice: Double,
        exitSlippageCostUsdt: Double,
        exitReason: PaperExitReason,
        config: PaperWalletConfig,
        nowMs: Long
    ): Pair<PaperClosedTrade, Double> {
        val elapsedMs = max(0L, nowMs - position.lastFundingAccrualMs)
        val extraPeriods = (elapsedMs / EIGHT_HOURS_MS).toInt()
        val extraFunding = if (extraPeriods > 0) {
            (position.quantity * exitPrice) * (config.fundingRatePer8hPercent / 100.0) * extraPeriods
        } else 0.0
        val totalFunding = position.accumulatedFundingUsdt + extraFunding

        val closeFeeRate = if (exitReason == PaperExitReason.TP_HIT) {
            config.makerFeePercentPerSide / 100.0
        } else {
            config.takerFeePercentPerSide / 100.0
        }
        val closingFee = (position.quantity * exitPrice) * closeFeeRate
        val grossPnl = computeGrossPnl(position.side, position.entryPrice, exitPrice, position.quantity)

        val totalCosts = position.openingFeeUsdt + closingFee + position.entrySlippageCostUsdt + exitSlippageCostUsdt + totalFunding
        // Available balance ALREADY had (marginUsed + openingFee + entrySlippage implicitly in fillPrice) accounted on open:
        // Wait: on Market open, fillPrice already includes entrySlippage vs marketRef, so grossPnl is computed from fillPrice -> exitPrice!
        // Thus entrySlippageCostUsdt is tracked separately for transparency ONLY and NOT double-subtracted from fillPrice-based grossPnl!
        // Same for exitSlippageCostUsdt: exitFillPrice already reflects slippage.
        // Only openingFee, closingFee, and funding reduce fill-to-fill grossPnl to cash netPnl!
        val trueMarketGrossPnl = computeGrossPnl(position.side, position.marketEntryReferencePrice, exitPrice, position.quantity) + exitSlippageCostUsdt
        val cashNetPnl = grossPnl - closingFee - totalFunding
        val reportedNetPnl = if (exitReason == PaperExitReason.LIQUIDATED) {
            -(position.marginUsedUsdt + position.openingFeeUsdt)
        } else {
            grossPnl - position.openingFeeUsdt - closingFee - totalFunding
        }

        val returnedCashToAvailable = if (exitReason == PaperExitReason.LIQUIDATED) {
            0.0
        } else {
            (position.marginUsedUsdt + cashNetPnl).coerceAtLeast(0.0)
        }

        val slRiskUsdt = position.stopLoss?.let { sl ->
            abs(position.entryPrice - sl) * position.quantity
        }?.takeIf { it > 0.0 }
        val rMultiple = slRiskUsdt?.let { reportedNetPnl / it }
        val roiPct = if (position.marginUsedUsdt > 0.0) (reportedNetPnl / position.marginUsedUsdt) * 100.0 else 0.0

        val closed = PaperClosedTrade(
            symbol = position.symbol,
            baseAsset = position.baseAsset,
            side = position.side,
            orderType = position.orderType,
            quantity = position.quantity,
            entryPrice = position.entryPrice,
            exitPrice = exitPrice,
            leverage = position.leverage,
            marginUsedUsdt = position.marginUsedUsdt,
            openingFeeUsdt = position.openingFeeUsdt,
            closingFeeUsdt = closingFee,
            entrySlippageCostUsdt = position.entrySlippageCostUsdt,
            exitSlippageCostUsdt = exitSlippageCostUsdt,
            fundingCostUsdt = totalFunding,
            grossPnlUsdt = grossPnl,
            netPnlUsdt = reportedNetPnl,
            netRoiPercent = roiPct,
            netRMultiple = rMultiple,
            exitReason = exitReason,
            openedAtMs = position.openedAtMs,
            openedAtPkt = position.openedAtPkt,
            closedAtMs = nowMs
        )
        return closed to returnedCashToAvailable
    }

    fun computeGrossPnl(
        side: PaperOrderSide,
        entryPrice: Double,
        exitPrice: Double,
        quantity: Double
    ): Double {
        return when (side) {
            PaperOrderSide.LONG -> (exitPrice - entryPrice) * quantity
            PaperOrderSide.SHORT -> (entryPrice - exitPrice) * quantity
        }
    }

    fun recalculateSnapshot(
        snapshot: PaperPortfolioSnapshot,
        nowMs: Long = System.currentTimeMillis()
    ): PaperPortfolioSnapshot {
        val reservedMargin = snapshot.pendingOrders
            .filter { it.status == PendingLimitStatus.PENDING }
            .sumOf { it.reservedMarginUsdt + it.reservedOpeningFeeUsdt }
        val usedMargin = snapshot.openPositions.sumOf { it.marginUsedUsdt }
        val unrealGross = snapshot.openPositions.sumOf { it.grossUnrealizedPnlUsdt }
        val unrealNet = snapshot.openPositions.sumOf { it.netUnrealizedPnlUsdt }

        // Equity = available cash + locked margin + open unrealized gross PnL - open opening fees already deducted (approx margin + unrealNet + openingFee)
        val openOpeningFees = snapshot.openPositions.sumOf { it.openingFeeUsdt }
        val equity = snapshot.availableBalanceUsdt + reservedMargin + usedMargin + unrealNet + openOpeningFees
        val totalNetPnl = equity - snapshot.config.initialBalanceUsdt
        val totalNetReturnPct = if (snapshot.config.initialBalanceUsdt > 0.0) {
            (totalNetPnl / snapshot.config.initialBalanceUsdt) * 100.0
        } else 0.0

        val stats = computeStatistics(
            closedTrades = snapshot.closedTrades,
            initialBalanceUsdt = snapshot.config.initialBalanceUsdt,
            nowMs = nowMs
        )

        return snapshot.copy(
            reservedLimitMarginUsdt = reservedMargin,
            usedPositionMarginUsdt = usedMargin,
            unrealizedGrossPnlUsdt = unrealGross,
            unrealizedNetPnlUsdt = unrealNet,
            equityUsdt = equity,
            totalNetPnlUsdt = totalNetPnl,
            totalNetReturnPercent = totalNetReturnPct,
            stats = stats
        )
    }

    fun computeStatistics(
        closedTrades: List<PaperClosedTrade>,
        initialBalanceUsdt: Double,
        nowMs: Long = System.currentTimeMillis()
    ): PaperTradingStatistics {
        if (closedTrades.isEmpty()) return PaperTradingStatistics()

        val chronological = closedTrades.sortedBy { it.closedAtMs }
        val total = chronological.size
        val winsList = chronological.filter { it.netPnlUsdt > 0.0 }
        val lossList = chronological.filter { it.netPnlUsdt <= 0.0 }

        val grossProfit = winsList.sumOf { it.netPnlUsdt }
        val grossLoss = abs(lossList.sumOf { it.netPnlUsdt })
        val pf = when {
            grossLoss > 1e-6 -> grossProfit / grossLoss
            grossProfit > 0.0 -> 99.9
            else -> 0.0
        }

        val todayKeyPkt = TimeZoneUtils.formatKarachiDateOnly(nowMs)
        val todayTrades = chronological.filter { it.closedDatePkt == todayKeyPkt }
        val todayNet = todayTrades.sumOf { it.netPnlUsdt }

        var eq = initialBalanceUsdt.coerceAtLeast(1.0)
        var peak = eq
        var maxDdUsdt = 0.0
        var maxDdPct = 0.0
        for (trade in chronological) {
            eq += trade.netPnlUsdt
            if (eq > peak) peak = eq
            val ddUsdt = (peak - eq).coerceAtLeast(0.0)
            val ddPct = if (peak > 0.0) (ddUsdt / peak) * 100.0 else 0.0
            if (ddUsdt > maxDdUsdt) maxDdUsdt = ddUsdt
            if (ddPct > maxDdPct) maxDdPct = ddPct
        }

        val longs = chronological.filter { it.side == PaperOrderSide.LONG }
        val shorts = chronological.filter { it.side == PaperOrderSide.SHORT }
        val longWr = if (longs.isNotEmpty()) longs.count { it.netPnlUsdt > 0.0 }.toDouble() / longs.size * 100.0 else 0.0
        val shortWr = if (shorts.isNotEmpty()) shorts.count { it.netPnlUsdt > 0.0 }.toDouble() / shorts.size * 100.0 else 0.0

        return PaperTradingStatistics(
            totalTrades = total,
            wins = winsList.size,
            losses = lossList.size,
            winRatePercent = (winsList.size.toDouble() / total) * 100.0,
            grossProfitUsdt = grossProfit,
            grossLossUsdt = grossLoss,
            profitFactor = pf,
            totalGrossPnlUsdt = chronological.sumOf { it.grossPnlUsdt },
            totalNetPnlUsdt = chronological.sumOf { it.netPnlUsdt },
            totalFeesPaidUsdt = chronological.sumOf { it.openingFeeUsdt + it.closingFeeUsdt },
            totalSlippageUsdt = chronological.sumOf { it.entrySlippageCostUsdt + it.exitSlippageCostUsdt },
            totalFundingUsdt = chronological.sumOf { it.fundingCostUsdt },
            todayNetPnlUsdt = todayNet,
            todayTradesCount = todayTrades.size,
            maxDrawdownUsdt = maxDdUsdt,
            maxDrawdownPercent = maxDdPct,
            averageNetWinUsdt = if (winsList.isNotEmpty()) grossProfit / winsList.size else 0.0,
            averageNetLossUsdt = if (lossList.isNotEmpty()) grossLoss / lossList.size else 0.0,
            longTradesCount = longs.size,
            shortTradesCount = shorts.size,
            longWinRatePercent = longWr,
            shortWinRatePercent = shortWr
        )
    }

    private fun validateSlTp(
        side: PaperOrderSide,
        entryPrice: Double,
        stopLoss: Double?,
        takeProfit: Double?
    ): String? {
        if (stopLoss != null) {
            if (!stopLoss.isFinite() || stopLoss <= 0.0) return "Stop Loss must be a positive price."
            if (side == PaperOrderSide.LONG && stopLoss >= entryPrice) {
                return "For LONG trades, Stop Loss must be below entry price ($${fmtPrice(entryPrice)})."
            }
            if (side == PaperOrderSide.SHORT && stopLoss <= entryPrice) {
                return "For SHORT trades, Stop Loss must be above entry price ($${fmtPrice(entryPrice)})."
            }
        }
        if (takeProfit != null) {
            if (!takeProfit.isFinite() || takeProfit <= 0.0) return "Take Profit must be a positive price."
            if (side == PaperOrderSide.LONG && takeProfit <= entryPrice) {
                return "For LONG trades, Take Profit must be above entry price ($${fmtPrice(entryPrice)})."
            }
            if (side == PaperOrderSide.SHORT && takeProfit >= entryPrice) {
                return "For SHORT trades, Take Profit must be below entry price ($${fmtPrice(entryPrice)})."
            }
        }
        return null
    }

    private fun fmtPrice(p: Double): String = when {
        p >= 1000.0 -> String.format(Locale.US, "%,.2f", p)
        p >= 1.0 -> String.format(Locale.US, "%.2f", p)
        else -> String.format(Locale.US, "%.4f", p)
    }

    private fun fmtQty(q: Double): String = when {
        q >= 100.0 -> String.format(Locale.US, "%.2f", q)
        q >= 1.0 -> String.format(Locale.US, "%.3f", q)
        else -> String.format(Locale.US, "%.5f", q)
    }
}
