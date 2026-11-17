package com.example.data.providers

import java.util.Locale
import kotlin.math.abs

/**
 * Validates cross-exchange price consistency strictly.
 *
 * Rules enforced:
 * 1. Matching normalized symbol (e.g. BTCUSDT == BTCUSDT).
 * 2. Matching quote currency (e.g. USDT == USDT, never USDT vs USD).
 * 3. Matching instrument type (FUTURES_PERPETUAL compared ONLY with FUTURES_PERPETUAL;
 *    never silently mix SPOT and FUTURES prices).
 * 4. Comparable fetch/exchange timestamps within maxTimestampSkewMs (default 60 seconds).
 */
object CrossExchangeValidator {

    const val DEFAULT_MAX_TIMESTAMP_SKEW_MS = 60_000L

    fun compareQuotes(
        primary: NormalizedMarketQuote,
        secondary: NormalizedMarketQuote?,
        maxTimestampSkewMs: Long = DEFAULT_MAX_TIMESTAMP_SKEW_MS
    ): CrossExchangeComparison {
        if (secondary == null) {
            return CrossExchangeComparison(
                symbol = primary.symbol,
                quoteCurrency = primary.quoteCurrency,
                marketType = primary.marketType,
                primaryQuote = primary,
                secondaryQuote = null,
                priceSpreadUsd = null,
                spreadBps = null,
                timestampSkewMs = null,
                isComparable = false,
                validationNote = "Secondary cross-exchange quote unavailable (${primary.exchange} ${primary.marketType.label} only)."
            )
        }

        if (!primary.symbol.equals(secondary.symbol, ignoreCase = true)) {
            return CrossExchangeComparison(
                symbol = primary.symbol,
                quoteCurrency = primary.quoteCurrency,
                marketType = primary.marketType,
                primaryQuote = primary,
                secondaryQuote = secondary,
                priceSpreadUsd = null,
                spreadBps = null,
                timestampSkewMs = null,
                isComparable = false,
                validationNote = "Rejected cross-check: symbol mismatch (${primary.symbol} vs ${secondary.symbol})."
            )
        }

        if (!primary.quoteCurrency.equals(secondary.quoteCurrency, ignoreCase = true)) {
            return CrossExchangeComparison(
                symbol = primary.symbol,
                quoteCurrency = primary.quoteCurrency,
                marketType = primary.marketType,
                primaryQuote = primary,
                secondaryQuote = secondary,
                priceSpreadUsd = null,
                spreadBps = null,
                timestampSkewMs = null,
                isComparable = false,
                validationNote = "Rejected cross-check: quote currency mismatch (${primary.quoteCurrency} vs ${secondary.quoteCurrency})."
            )
        }

        if (primary.marketType != secondary.marketType) {
            return CrossExchangeComparison(
                symbol = primary.symbol,
                quoteCurrency = primary.quoteCurrency,
                marketType = primary.marketType,
                primaryQuote = primary,
                secondaryQuote = secondary,
                priceSpreadUsd = null,
                spreadBps = null,
                timestampSkewMs = null,
                isComparable = false,
                validationNote = "Rejected cross-check: cannot mix ${primary.marketType.label} (${primary.exchange}) with ${secondary.marketType.label} (${secondary.exchange})."
            )
        }

        val skewMs = abs(primary.fetchedAtMillis - secondary.fetchedAtMillis)
        if (skewMs > maxTimestampSkewMs) {
            return CrossExchangeComparison(
                symbol = primary.symbol,
                quoteCurrency = primary.quoteCurrency,
                marketType = primary.marketType,
                primaryQuote = primary,
                secondaryQuote = secondary,
                priceSpreadUsd = null,
                spreadBps = null,
                timestampSkewMs = skewMs,
                isComparable = false,
                validationNote = "Stale timestamp skew (${skewMs / 1000}s > ${maxTimestampSkewMs / 1000}s limit); cross-check skipped."
            )
        }

        if (primary.lastPrice <= 0.0 || secondary.lastPrice <= 0.0) {
            return CrossExchangeComparison(
                symbol = primary.symbol,
                quoteCurrency = primary.quoteCurrency,
                marketType = primary.marketType,
                primaryQuote = primary,
                secondaryQuote = secondary,
                priceSpreadUsd = null,
                spreadBps = null,
                timestampSkewMs = skewMs,
                isComparable = false,
                validationNote = "Invalid non-positive price in quote comparison."
            )
        }

        val diff = secondary.lastPrice - primary.lastPrice
        val spreadBps = (abs(diff) / primary.lastPrice) * 10_000.0
        val note = String.format(
            Locale.US,
            "%s vs %s (%s, %s): Δ $%.4f (%.2f bps), timestamp skew %.1fs",
            primary.exchange,
            secondary.exchange,
            primary.symbol,
            primary.marketType.label,
            diff,
            spreadBps,
            skewMs / 1000.0
        )

        return CrossExchangeComparison(
            symbol = primary.symbol,
            quoteCurrency = primary.quoteCurrency,
            marketType = primary.marketType,
            primaryQuote = primary,
            secondaryQuote = secondary,
            priceSpreadUsd = diff,
            spreadBps = spreadBps,
            timestampSkewMs = skewMs,
            isComparable = true,
            validationNote = note
        )
    }
}
