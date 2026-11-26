package com.example.data.scanner

import com.example.util.TimeZoneUtils
import java.util.Locale

enum class ManagedSignalState(val label: String) {
    WATCHING("WATCHING"),
    CONFIRMED("CONFIRMED"),
    ACTIVE("ACTIVE"),
    TARGET_HIT("TARGET_HIT"),
    STOP_LOSS_HIT("STOP_LOSS_HIT"),
    INVALIDATED("INVALIDATED"),
    EXPIRED("EXPIRED");

    val isOpenOrLive: Boolean
        get() = this == CONFIRMED || this == ACTIVE

    val isTerminal: Boolean
        get() = this == TARGET_HIT || this == STOP_LOSS_HIT || this == INVALIDATED || this == EXPIRED
}

data class SignalTransitionRecord(
    val signalId: String,
    val symbol: String,
    val direction: FuturesDirection,
    val fromState: ManagedSignalState?,
    val toState: ManagedSignalState,
    val reason: String,
    val timestampMs: Long,
    val timestampPkt: String = TimeZoneUtils.formatKarachiTimestamp(timestampMs)
)

data class ManagedSignalEntry(
    val signalId: String,
    val symbol: String,
    val direction: FuturesDirection,
    val state: ManagedSignalState,
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit1: Double,
    val takeProfit2: Double,
    val netRiskReward: Double,
    val qualityScore: Int,
    val dataSourceName: String,
    val setupReason: String,
    val confirmationCandleCloseMs: Long,
    val expiresAtMs: Long,
    val lastUpdatedMs: Long,
    val statusReason: String
)

data class SignalIntakeOutcome(
    val accepted: Boolean,
    val entry: ManagedSignalEntry?,
    val transition: SignalTransitionRecord?,
    val rejectionReason: String? = null,
    val isDuplicateSuppressed: Boolean = false
)

/**
 * Part 3 Active Signal State Manager.
 * Tracks every signal through:
 * WATCHING -> CONFIRMED -> ACTIVE -> TARGET_HIT / STOP_LOSS_HIT / INVALIDATED / EXPIRED.
 *
 * Key integrity rules:
 * - Deterministic unique signal ID per symbol, direction, and 15M candle close window.
 * - Strictly BLOCKS silent LONG <-> SHORT reversal while an ACTIVE/CONFIRMED signal exists for the symbol.
 * - Allows opposite direction ONLY after prior signal reaches a terminal state AND passes all filters.
 * - Prevents duplicate entries and suppresses alert spam via per-symbol/direction cooldowns.
 */
class ActiveSignalStateManager(
    private val defaultNotificationCooldownMs: Long = 45 * 60 * 1000L
) {
    private val entriesBySymbol = LinkedHashMap<String, ManagedSignalEntry>()
    private val transitionHistory = mutableListOf<SignalTransitionRecord>()
    private val lastNotifiedByKey = HashMap<String, Long>()

    @Synchronized
    fun getActiveSignals(): List<ManagedSignalEntry> =
        entriesBySymbol.values.filter { it.state.isOpenOrLive }.sortedByDescending { it.lastUpdatedMs }

    @Synchronized
    fun getWatchingSignals(): List<ManagedSignalEntry> =
        entriesBySymbol.values.filter { it.state == ManagedSignalState.WATCHING }.sortedByDescending { it.lastUpdatedMs }

    @Synchronized
    fun getAllEntries(): List<ManagedSignalEntry> = entriesBySymbol.values.toList()

    @Synchronized
    fun getTransitionHistory(): List<SignalTransitionRecord> = transitionHistory.toList()

    @Synchronized
    fun getEntryForSymbol(symbol: String): ManagedSignalEntry? =
        entriesBySymbol[symbol.uppercase(Locale.US)]

    @Synchronized
    fun submitCandidate(
        symbol: String,
        direction: FuturesDirection,
        isConfirmed: Boolean,
        entryPrice: Double,
        stopLoss: Double,
        takeProfit1: Double,
        takeProfit2: Double,
        netRiskReward: Double,
        qualityScore: Int,
        dataSourceName: String,
        setupReason: String,
        confirmationCandleCloseMs: Long,
        expiresAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        preferredSignalId: String? = null
    ): SignalIntakeOutcome {
        val normSymbol = symbol.uppercase(Locale.US).trim()
        val existing = entriesBySymbol[normSymbol]

        // 1. Hard guard against silent LONG <-> SHORT flip while an active signal exists
        if (existing != null && existing.state.isOpenOrLive && existing.direction != direction) {
            val blockMsg = "Blocked silent ${existing.direction.name}->${direction.name} reversal on $normSymbol: active signal ${existing.signalId} (${existing.state.label}) must hit target, stop loss, invalidate, or expire first."
            val blockedTransition = SignalTransitionRecord(
                signalId = existing.signalId,
                symbol = normSymbol,
                direction = direction,
                fromState = existing.state,
                toState = existing.state,
                reason = blockMsg,
                timestampMs = nowMs
            )
            appendTransition(blockedTransition)
            return SignalIntakeOutcome(
                accepted = false,
                entry = existing,
                transition = blockedTransition,
                rejectionReason = blockMsg
            )
        }

        val detId = preferredSignalId?.takeIf { it.isNotBlank() }
            ?: buildDeterministicId(normSymbol, direction, confirmationCandleCloseMs)

        // 2. If same-direction active/confirmed signal already exists, update in place (no duplicate)
        if (existing != null && existing.state.isOpenOrLive && existing.direction == direction) {
            if (!isConfirmed) {
                return SignalIntakeOutcome(
                    accepted = false,
                    entry = existing,
                    transition = null,
                    rejectionReason = "Active ${existing.direction.name} signal ${existing.signalId} remains live; unconfirmed watching tick ignored.",
                    isDuplicateSuppressed = true
                )
            }
            val updated = existing.copy(
                entryPrice = entryPrice,
                stopLoss = stopLoss,
                takeProfit1 = takeProfit1,
                takeProfit2 = takeProfit2,
                netRiskReward = netRiskReward,
                qualityScore = maxOf(existing.qualityScore, qualityScore),
                lastUpdatedMs = nowMs,
                statusReason = "Updated existing active signal in-place ($setupReason)"
            )
            entriesBySymbol[normSymbol] = updated
            return SignalIntakeOutcome(
                accepted = true,
                entry = updated,
                transition = null,
                isDuplicateSuppressed = existing.signalId == detId || (nowMs - existing.lastUpdatedMs < defaultNotificationCooldownMs)
            )
        }

        // 3. New setup intake: WATCHING or CONFIRMED -> ACTIVE
        val fromState = existing?.state
        val newEntry = if (isConfirmed) {
            ManagedSignalEntry(
                signalId = detId,
                symbol = normSymbol,
                direction = direction,
                state = ManagedSignalState.ACTIVE,
                entryPrice = entryPrice,
                stopLoss = stopLoss,
                takeProfit1 = takeProfit1,
                takeProfit2 = takeProfit2,
                netRiskReward = netRiskReward,
                qualityScore = qualityScore,
                dataSourceName = dataSourceName,
                setupReason = setupReason,
                confirmationCandleCloseMs = confirmationCandleCloseMs,
                expiresAtMs = expiresAtMs,
                lastUpdatedMs = nowMs,
                statusReason = "Confirmed on 15M close ($setupReason)"
            )
        } else {
            ManagedSignalEntry(
                signalId = detId,
                symbol = normSymbol,
                direction = direction,
                state = ManagedSignalState.WATCHING,
                entryPrice = entryPrice,
                stopLoss = stopLoss,
                takeProfit1 = takeProfit1,
                takeProfit2 = takeProfit2,
                netRiskReward = netRiskReward,
                qualityScore = qualityScore,
                dataSourceName = dataSourceName,
                setupReason = setupReason,
                confirmationCandleCloseMs = confirmationCandleCloseMs,
                expiresAtMs = expiresAtMs,
                lastUpdatedMs = nowMs,
                statusReason = "Developing setup ($setupReason)"
            )
        }

        entriesBySymbol[normSymbol] = newEntry

        val transition = if (isConfirmed) {
            val watchToConfirmed = SignalTransitionRecord(
                signalId = detId,
                symbol = normSymbol,
                direction = direction,
                fromState = fromState ?: ManagedSignalState.WATCHING,
                toState = ManagedSignalState.CONFIRMED,
                reason = "15M candle close + mandatory filters passed ($setupReason)",
                timestampMs = nowMs
            )
            appendTransition(watchToConfirmed)
            val confirmedToActive = SignalTransitionRecord(
                signalId = detId,
                symbol = normSymbol,
                direction = direction,
                fromState = ManagedSignalState.CONFIRMED,
                toState = ManagedSignalState.ACTIVE,
                reason = "Signal promoted to ACTIVE tracking (Net R:R 1:${String.format(Locale.US, "%.2f", netRiskReward)})",
                timestampMs = nowMs
            )
            appendTransition(confirmedToActive)
            confirmedToActive
        } else {
            val rec = SignalTransitionRecord(
                signalId = detId,
                symbol = normSymbol,
                direction = direction,
                fromState = fromState,
                toState = ManagedSignalState.WATCHING,
                reason = setupReason,
                timestampMs = nowMs
            )
            appendTransition(rec)
            rec
        }

        return SignalIntakeOutcome(
            accepted = true,
            entry = newEntry,
            transition = transition,
            isDuplicateSuppressed = false
        )
    }

    @Synchronized
    fun evaluateMarketUpdate(
        symbol: String,
        currentPrice: Double,
        nowMs: Long = System.currentTimeMillis(),
        invalidatedByStructure: Boolean = false,
        invalidationReason: String? = null
    ): SignalTransitionRecord? {
        val normSymbol = symbol.uppercase(Locale.US).trim()
        val entry = entriesBySymbol[normSymbol] ?: return null
        if (!entry.state.isOpenOrLive || !currentPrice.isFinite() || currentPrice <= 0.0) return null

        val isLong = entry.direction == FuturesDirection.LONG
        val newStateAndReason: Pair<ManagedSignalState, String>? = when {
            invalidatedByStructure -> ManagedSignalState.INVALIDATED to (invalidationReason ?: "Market structure invalidated setup")
            entry.expiresAtMs > 0L && nowMs >= entry.expiresAtMs ->
                ManagedSignalState.EXPIRED to "Setup setup validity window expired without target completion"
            (isLong && currentPrice <= entry.stopLoss) || (!isLong && currentPrice >= entry.stopLoss) ->
                ManagedSignalState.STOP_LOSS_HIT to "Price ($currentPrice) breached Stop Loss (${entry.stopLoss})"
            (isLong && currentPrice >= entry.takeProfit2) || (!isLong && currentPrice <= entry.takeProfit2) ->
                ManagedSignalState.TARGET_HIT to "Final target (${entry.takeProfit2}) reached at $currentPrice"
            else -> null
        }

        val (targetState, reason) = newStateAndReason ?: return null
        val updated = entry.copy(
            state = targetState,
            lastUpdatedMs = nowMs,
            statusReason = reason
        )
        entriesBySymbol[normSymbol] = updated
        val record = SignalTransitionRecord(
            signalId = entry.signalId,
            symbol = normSymbol,
            direction = entry.direction,
            fromState = entry.state,
            toState = targetState,
            reason = reason,
            timestampMs = nowMs
        )
        appendTransition(record)
        return record
    }

    @Synchronized
    fun forceTransition(
        symbol: String,
        toState: ManagedSignalState,
        reason: String,
        nowMs: Long = System.currentTimeMillis()
    ): SignalTransitionRecord? {
        val normSymbol = symbol.uppercase(Locale.US).trim()
        val entry = entriesBySymbol[normSymbol] ?: return null
        val updated = entry.copy(
            state = toState,
            lastUpdatedMs = nowMs,
            statusReason = reason
        )
        entriesBySymbol[normSymbol] = updated
        val record = SignalTransitionRecord(
            signalId = entry.signalId,
            symbol = normSymbol,
            direction = entry.direction,
            fromState = entry.state,
            toState = toState,
            reason = reason,
            timestampMs = nowMs
        )
        appendTransition(record)
        return record
    }

    @Synchronized
    fun shouldDispatchNotification(
        signalId: String,
        symbol: String,
        direction: FuturesDirection,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val norm = "${symbol.uppercase(Locale.US)}_${direction.name}"
        if (lastNotifiedByKey.containsKey("id:$signalId")) return false
        val lastSymTime = lastNotifiedByKey[norm] ?: 0L
        if (nowMs - lastSymTime < defaultNotificationCooldownMs) return false
        return true
    }

    @Synchronized
    fun markNotificationDispatched(
        signalId: String,
        symbol: String,
        direction: FuturesDirection,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val norm = "${symbol.uppercase(Locale.US)}_${direction.name}"
        lastNotifiedByKey["id:$signalId"] = nowMs
        lastNotifiedByKey[norm] = nowMs
    }

    private fun appendTransition(record: SignalTransitionRecord) {
        transitionHistory.add(0, record)
        if (transitionHistory.size > MAX_TRANSITION_LOG) {
            transitionHistory.removeAt(transitionHistory.lastIndex)
        }
    }

    companion object {
        private const val MAX_TRANSITION_LOG = 120

        fun buildDeterministicId(
            symbol: String,
            direction: FuturesDirection,
            confirmationCandleCloseMs: Long
        ): String {
            val bucket = if (confirmationCandleCloseMs > 0L) confirmationCandleCloseMs else 0L
            return "sig_${symbol.uppercase(Locale.US)}_${direction.name}_$bucket"
        }
    }
}
