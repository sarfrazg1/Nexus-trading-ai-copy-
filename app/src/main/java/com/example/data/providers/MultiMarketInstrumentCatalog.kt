package com.example.data.providers

import com.example.data.model.AssetCategory
import java.util.Locale

/**
 * Explicit venue and instrument routing for NEXUS Multi-Market Engine.
 * Enforces strict separation between Crypto Futures (Binance USDT-M / Bybit V5 Linear),
 * Commodities (Gold, Silver, Crude Oil), Equities/Stocks (NVDA, GOOG, GOOGL), and FX/Indices.
 * Stocks and commodities are NEVER treated as Binance Futures instruments.
 */
enum class MarketVenue(
    val displayName: String,
    val isCryptoDerivatives: Boolean
) {
    BINANCE_USDT_M_FUTURES("Binance USDT-M Futures", true),
    BYBIT_V5_LINEAR("Bybit V5 Linear Futures", true),
    COMMODITIES_YAHOO("COMEX / NYMEX (Yahoo Finance Feed)", false),
    STOCKS_YAHOO("NASDAQ (Yahoo Finance Feed)", false),
    FOREX_INDEX_YAHOO("Global Macro (Yahoo Finance Feed)", false),
    UNSUPPORTED("Unsupported / Unverified Instrument", false)
}

data class MarketInstrumentSpec(
    val canonicalId: String,
    val displaySymbol: String,
    val displayName: String,
    val category: AssetCategory,
    val primaryVenue: MarketVenue,
    val secondaryVenue: MarketVenue? = null,
    val binanceSymbol: String? = null,
    val bybitSymbol: String? = null,
    val yahooTicker: String? = null,
    val unit: String = "$",
    val roundTripCostPercent: Double = 0.14
) {
    val isBinanceFuturesEligible: Boolean
        get() = category == AssetCategory.CRYPTO && binanceSymbol != null && primaryVenue == MarketVenue.BINANCE_USDT_M_FUTURES

    val isVerifiedSupported: Boolean
        get() = primaryVenue != MarketVenue.UNSUPPORTED
}

object MultiMarketInstrumentCatalog {

    private val cryptoBaseInstruments = listOf(
        "BTC" to "Bitcoin",
        "ETH" to "Ethereum",
        "BNB" to "BNB Chain",
        "SOL" to "Solana",
        "XRP" to "Ripple",
        "DOGE" to "Dogecoin",
        "ADA" to "Cardano",
        "LINK" to "Chainlink",
        "AVAX" to "Avalanche",
        "TRX" to "TRON",
        "TON" to "Toncoin",
        "LTC" to "Litecoin",
        "DOT" to "Polkadot",
        "BCH" to "Bitcoin Cash",
        "SUI" to "Sui Network"
    ).associate { (id, name) ->
        id to MarketInstrumentSpec(
            canonicalId = id,
            displaySymbol = "$id/USDT",
            displayName = name,
            category = AssetCategory.CRYPTO,
            primaryVenue = MarketVenue.BINANCE_USDT_M_FUTURES,
            secondaryVenue = MarketVenue.BYBIT_V5_LINEAR,
            binanceSymbol = "${id}USDT",
            bybitSymbol = "${id}USDT",
            yahooTicker = "$id-USD",
            unit = "$",
            roundTripCostPercent = 0.16 // ~0.10% taker fees + 0.06% slippage
        )
    }

    private val nonCryptoInstruments: Map<String, MarketInstrumentSpec> = mapOf(
        "XAU" to MarketInstrumentSpec(
            canonicalId = "XAU",
            displaySymbol = "XAU/USD",
            displayName = "Gold Spot (COMEX Futures Proxy)",
            category = AssetCategory.COMMODITIES,
            primaryVenue = MarketVenue.COMMODITIES_YAHOO,
            yahooTicker = "GC=F",
            unit = "$/oz",
            roundTripCostPercent = 0.10
        ),
        "XAG" to MarketInstrumentSpec(
            canonicalId = "XAG",
            displaySymbol = "XAG/USD",
            displayName = "Silver Spot (COMEX Futures Proxy)",
            category = AssetCategory.COMMODITIES,
            primaryVenue = MarketVenue.COMMODITIES_YAHOO,
            yahooTicker = "SI=F",
            unit = "$/oz",
            roundTripCostPercent = 0.14
        ),
        "WTI" to MarketInstrumentSpec(
            canonicalId = "WTI",
            displaySymbol = "WTI/USD",
            displayName = "WTI Crude Oil (NYMEX)",
            category = AssetCategory.COMMODITIES,
            primaryVenue = MarketVenue.COMMODITIES_YAHOO,
            yahooTicker = "CL=F",
            unit = "$/bbl",
            roundTripCostPercent = 0.14
        ),
        "BRENT" to MarketInstrumentSpec(
            canonicalId = "BRENT",
            displaySymbol = "BRENT/USD",
            displayName = "Brent Crude Oil (ICE)",
            category = AssetCategory.COMMODITIES,
            primaryVenue = MarketVenue.COMMODITIES_YAHOO,
            yahooTicker = "BZ=F",
            unit = "$/bbl",
            roundTripCostPercent = 0.14
        ),
        "NATGAS" to MarketInstrumentSpec(
            canonicalId = "NATGAS",
            displaySymbol = "NG/USD",
            displayName = "Natural Gas (Henry Hub)",
            category = AssetCategory.COMMODITIES,
            primaryVenue = MarketVenue.COMMODITIES_YAHOO,
            yahooTicker = "NG=F",
            unit = "$/MMBtu",
            roundTripCostPercent = 0.18
        ),
        "NVDA" to MarketInstrumentSpec(
            canonicalId = "NVDA",
            displaySymbol = "NVDA",
            displayName = "NVIDIA Corporation",
            category = AssetCategory.STOCKS,
            primaryVenue = MarketVenue.STOCKS_YAHOO,
            yahooTicker = "NVDA",
            unit = "$",
            roundTripCostPercent = 0.08
        ),
        "GOOG" to MarketInstrumentSpec(
            canonicalId = "GOOG",
            displaySymbol = "GOOG",
            displayName = "Alphabet Inc. (Class C)",
            category = AssetCategory.STOCKS,
            primaryVenue = MarketVenue.STOCKS_YAHOO,
            yahooTicker = "GOOG",
            unit = "$",
            roundTripCostPercent = 0.08
        ),
        "GOOGL" to MarketInstrumentSpec(
            canonicalId = "GOOGL",
            displaySymbol = "GOOGL",
            displayName = "Alphabet Inc. (Class A)",
            category = AssetCategory.STOCKS,
            primaryVenue = MarketVenue.STOCKS_YAHOO,
            yahooTicker = "GOOGL",
            unit = "$",
            roundTripCostPercent = 0.08
        ),
        "EURUSD" to MarketInstrumentSpec(
            canonicalId = "EURUSD",
            displaySymbol = "EUR/USD",
            displayName = "Euro / US Dollar",
            category = AssetCategory.FOREX,
            primaryVenue = MarketVenue.FOREX_INDEX_YAHOO,
            yahooTicker = "EURUSD=X",
            unit = "",
            roundTripCostPercent = 0.04
        ),
        "SPX" to MarketInstrumentSpec(
            canonicalId = "SPX",
            displaySymbol = "SPX500",
            displayName = "S&P 500 Index",
            category = AssetCategory.INDICES,
            primaryVenue = MarketVenue.FOREX_INDEX_YAHOO,
            yahooTicker = "^GSPC",
            unit = "pts",
            roundTripCostPercent = 0.06
        )
    )

    private val aliasToCanonical: Map<String, String> = mapOf(
        "XAUUSD" to "XAU",
        "GOLD" to "XAU",
        "GC=F" to "XAU",
        "XAU/USD" to "XAU",
        "XAGUSD" to "XAG",
        "SILVER" to "XAG",
        "SI=F" to "XAG",
        "XAG/USD" to "XAG",
        "OIL_WTI" to "WTI",
        "USOIL" to "WTI",
        "OIL" to "WTI",
        "CRUDE" to "WTI",
        "CRUDE_OIL" to "WTI",
        "CL=F" to "WTI",
        "WTI/USD" to "WTI",
        "UKOIL" to "BRENT",
        "BZ=F" to "BRENT",
        "BRENT/USD" to "BRENT",
        "NG=F" to "NATGAS",
        "NG/USD" to "NATGAS",
        "NVIDIA" to "NVDA",
        "ALPHABET" to "GOOGL",
        "EUR/USD" to "EURUSD",
        "EURUSD=X" to "EURUSD",
        "SPX500" to "SPX",
        "^GSPC" to "SPX",
        "S&P500" to "SPX"
    )

    fun normalizeCanonicalId(rawSymbol: String): String {
        val clean = rawSymbol.trim().uppercase(Locale.US)
        aliasToCanonical[clean]?.let { return it }
        val stripped = clean
            .removeSuffix("/USDT")
            .removeSuffix("USDT")
            .removeSuffix("/USD")
        return aliasToCanonical[stripped] ?: stripped
    }

    /**
     * Resolves verified instrument metadata. If an unknown non-crypto symbol is supplied
     * (without USDT suffix and not in verified nonCryptoInstruments), returns null unless
     * explicitly formatted as a crypto USDT perpetual pair.
     */
    fun resolve(rawSymbol: String): MarketInstrumentSpec? {
        val clean = rawSymbol.trim().uppercase(Locale.US)
        if (clean.isBlank()) return null

        val canonical = normalizeCanonicalId(clean)
        nonCryptoInstruments[canonical]?.let { return it }
        cryptoBaseInstruments[canonical]?.let { return it }

        // Allow custom verified crypto USDT perpetuals only (never stocks/commodities)
        if (clean.endsWith("USDT") || clean.endsWith("/USDT")) {
            val base = clean.removeSuffix("/USDT").removeSuffix("USDT")
            if (base.isNotBlank() && !nonCryptoInstruments.containsKey(base)) {
                return MarketInstrumentSpec(
                    canonicalId = base,
                    displaySymbol = "${base}USDT",
                    displayName = "$base Perpetual",
                    category = AssetCategory.CRYPTO,
                    primaryVenue = MarketVenue.BINANCE_USDT_M_FUTURES,
                    secondaryVenue = MarketVenue.BYBIT_V5_LINEAR,
                    binanceSymbol = "${base}USDT",
                    bybitSymbol = "${base}USDT",
                    yahooTicker = "$base-USD"
                )
            }
        }
        return null
    }

    fun isNonCrypto(rawSymbol: String): Boolean {
        val canonical = normalizeCanonicalId(rawSymbol)
        return nonCryptoInstruments.containsKey(canonical)
    }

    fun allMultiMarketScannerSpecs(): List<MarketInstrumentSpec> {
        val coreCrypto = listOf("BTC", "ETH", "SOL", "BNB", "XRP").mapNotNull { cryptoBaseInstruments[it] }
        val commodities = listOf("XAU", "XAG", "WTI").mapNotNull { nonCryptoInstruments[it] }
        val stocks = listOf("NVDA", "GOOG", "GOOGL").mapNotNull { nonCryptoInstruments[it] }
        return coreCrypto + commodities + stocks
    }
}
