package com.example.data.ai

import com.example.data.learning.LearningLanguageMode
import java.util.Locale

/**
 * Limited Rule-Based Fallback Engine used ONLY when the online Gemini 3.8 Flash
 * model is unavailable (network offline, App Check failure, or free-tier quota exhausted).
 *
 * Never pretends to be a conversational AI. Clearly discloses its rule-based limitations,
 * exact data timestamps, and why online AI was unavailable, while tailoring responses
 * dynamically to the user's question and verified live market context.
 */
object OfflineTradingAnalysisEngine {

    private enum class FallbackIntent {
        MARKET_FALL_OR_MOVE,
        WATCHLIST_STRONGEST_SETUP,
        BUY_OPPORTUNITY_AND_ENTRY,
        SUPPORT_RESISTANCE_LEVELS,
        FURTHER_DOWNSIDE_RISK,
        EXPLAIN_PPI,
        EXPLAIN_CPI_MACRO,
        EXPLAIN_FED_RATES,
        EXPLAIN_FUNDING_RATE,
        EXPLAIN_OPEN_INTEREST,
        EXPLAIN_LIQUIDATIONS,
        TIMEFRAME_COMPARISON,
        RISK_MANAGEMENT,
        GENERAL_ASSET_SNAPSHOT,
        OUT_OF_SCOPE_OR_FOLLOWUP
    }

    fun generateAnalysis(
        query: String,
        context: MarketContextSnapshot,
        language: LearningLanguageMode,
        unavailableReason: String? = null,
        recentHistory: List<NexusChatMessage> = emptyList()
    ): String {
        val intent = detectIntent(query, recentHistory)
        val header = buildDisclosureHeader(context, language, unavailableReason)
        val body = when (intent) {
            FallbackIntent.MARKET_FALL_OR_MOVE -> buildMarketMoveAnalysis(query, context, language)
            FallbackIntent.WATCHLIST_STRONGEST_SETUP -> buildWatchlistSetupAnalysis(context, language)
            FallbackIntent.BUY_OPPORTUNITY_AND_ENTRY -> buildBalancedEntryScenarios(context, language)
            FallbackIntent.SUPPORT_RESISTANCE_LEVELS -> buildSupportResistanceLevels(context, language)
            FallbackIntent.FURTHER_DOWNSIDE_RISK -> buildFurtherDownsideScenarios(context, language)
            FallbackIntent.EXPLAIN_PPI -> buildPpiExplanation(context, language)
            FallbackIntent.EXPLAIN_CPI_MACRO -> buildCpiMacroExplanation(context, language)
            FallbackIntent.EXPLAIN_FED_RATES -> buildFedExplanation(context, language)
            FallbackIntent.EXPLAIN_FUNDING_RATE -> buildFundingRateExplanation(context, language)
            FallbackIntent.EXPLAIN_OPEN_INTEREST -> buildOpenInterestExplanation(context, language)
            FallbackIntent.EXPLAIN_LIQUIDATIONS -> buildLiquidationsExplanation(context, language)
            FallbackIntent.TIMEFRAME_COMPARISON -> buildTimeframeAdvice(context, language)
            FallbackIntent.RISK_MANAGEMENT -> buildRiskAnalysis(context, language)
            FallbackIntent.GENERAL_ASSET_SNAPSHOT -> buildGeneralAssetSnapshot(context, language)
            FallbackIntent.OUT_OF_SCOPE_OR_FOLLOWUP -> buildLimitedScopeNotice(query, context, language)
        }
        return "$header\n\n$body"
    }

    private fun detectIntent(query: String, recentHistory: List<NexusChatMessage>): FallbackIntent {
        val q = query.lowercase(Locale.US).trim()
        val combinedContext = (recentHistory.takeLast(2).joinToString(" ") { it.text } + " " + q).lowercase(Locale.US)

        val tfTokenCount = listOf("1m", "5m", "15m", "1h", "4h", "1d").count { tf ->
            Regex("(^|[^a-z0-9])$tf([^a-z0-9]|$)").containsMatchIn(q)
        }
        return when {
            q.contains("ppi") || q.contains("producer price") -> FallbackIntent.EXPLAIN_PPI
            q.contains("funding") || q.contains("فنڈنگ") -> FallbackIntent.EXPLAIN_FUNDING_RATE
            q.contains("open interest") || q.contains("اوپن انٹرسٹ") || Regex("\\boi\\b").containsMatchIn(q) -> FallbackIntent.EXPLAIN_OPEN_INTEREST
            q.contains("liquidation") || q.contains("liquidate") || q.contains("لیکویڈیشن") -> FallbackIntent.EXPLAIN_LIQUIDATIONS
            q.contains("fed") || q.contains("fomc") || q.contains("powell") || q.contains("interest rate") || q.contains("rate cut") || q.contains("سود") -> FallbackIntent.EXPLAIN_FED_RATES
            q.contains("cpi") || q.contains("inflation") || q.contains("mehngai") || q.contains("سی پی آئی") || q.contains("مہنگائی") -> FallbackIntent.EXPLAIN_CPI_MACRO
            q.contains("watchlist") || q.contains("strongest setup") || q.contains("best coin") || q.contains("best setup") || q.contains("top setup") || q.contains("scanner") || q.contains("واچ لسٹ") -> FallbackIntent.WATCHLIST_STRONGEST_SETUP
            q.contains("timeframe") || tfTokenCount >= 2 || q.contains("ٹائم فریم") || q.contains("چارٹ") || q.contains("پانچ منٹ") -> FallbackIntent.TIMEFRAME_COMPARISON
            q.contains("fall further") || q.contains("drop more") || q.contains("crash more") || q.contains("mazeed gir") || q.contains("نیچے جا") || q.contains("مزید گر") -> FallbackIntent.FURTHER_DOWNSIDE_RISK
            q.contains("support") || q.contains("resistance") || q.contains("levels") || q.contains("سپورٹ") || q.contains("مزاحمت") -> FallbackIntent.SUPPORT_RESISTANCE_LEVELS
            q.contains("good time to buy") || q.contains("should i buy") || q.contains("entry zone") || q.contains("entry") || q.contains("kharid") || q.contains("buy") || q.contains("انٹری") || q.contains("خرید") -> FallbackIntent.BUY_OPPORTUNITY_AND_ENTRY
            q.contains("falling") || q.contains("dump") || q.contains("crash") || q.contains("moving") || q.contains("causing") || q.contains("why is") || q.contains("gir") || q.contains("کیوں گر") || q.contains("کیوں") -> FallbackIntent.MARKET_FALL_OR_MOVE
            q.contains("5m") || q.contains("15m") || q.contains("1h") || q.contains("4h") -> FallbackIntent.TIMEFRAME_COMPARISON
            q.contains("risk") || q.contains("leverage") || q.contains("stop loss") || q.contains("رسک") || q.contains("لیوریج") || q.contains("اسٹاپ لاس") -> FallbackIntent.RISK_MANAGEMENT
            q.contains("trend") || q.contains("analy") || q.contains("price") || q.contains("btc") || q.contains("eth") || q.contains("sol") || q.contains("gold") || q.contains("تجزیہ") || q.contains("قیمت") -> FallbackIntent.GENERAL_ASSET_SNAPSHOT
            // Short follow-ups like "what about eth?" or "and support?"
            combinedContext.contains("support") || combinedContext.contains("resistance") -> FallbackIntent.SUPPORT_RESISTANCE_LEVELS
            q.length <= 28 && recentHistory.isNotEmpty() -> FallbackIntent.GENERAL_ASSET_SNAPSHOT
            else -> FallbackIntent.OUT_OF_SCOPE_OR_FOLLOWUP
        }
    }

    private fun buildDisclosureHeader(
        ctx: MarketContextSnapshot,
        lang: LearningLanguageMode,
        unavailableReason: String?
    ): String {
        val reasonNote = unavailableReason?.takeIf { it.isNotBlank() } ?: "Online Gemini AI unavailable"
        val dataMeta = "${ctx.dataStatus} • ${ctx.dataSource} (${ctx.lastUpdatedFormatted})"
        return when (lang) {
            LearningLanguageMode.ENGLISH -> """
                ⚠️ [LIMITED OFFLINE RULE-BASED ANALYSIS — Not Conversational AI]
                Online AI status: $reasonNote
                Market Data: $dataMeta
            """.trimIndent()
            LearningLanguageMode.ROMAN_URDU -> """
                ⚠️ [OFFLINE RULE-BASED ANALYSIS — Limited Fallback (Online AI Dastiyab Nahi)]
                Wajah: $reasonNote
                Data Status: $dataMeta
            """.trimIndent()
            LearningLanguageMode.STANDARD_URDU -> """
                ⚠️ [آف لائن اصولوں پر مبنی تجارتی تجزیہ — محدود متبادل موڈ]
                آن لائن اے آئی دستیاب نہ ہونے کی وجہ: $reasonNote
                مارکیٹ ڈیٹا کا وقت: $dataMeta
            """.trimIndent()
            LearningLanguageMode.BILINGUAL -> """
                ⚠️ [HYBRID BILINGUAL ANALYSIS / محدود آف لائن اصولی تجزیہ — Online AI Unavailable]
                Reason: $reasonNote | Data: $dataMeta
            """.trimIndent()
        }
    }

    private fun buildMarketMoveAnalysis(
        query: String,
        ctx: MarketContextSnapshot,
        lang: LearningLanguageMode
    ): String {
        val priceStr = if (ctx.isRealData) String.format(Locale.US, "$%.2f", ctx.currentPrice) else "Unavailable"
        val chgStr = if (ctx.isRealData) String.format(Locale.US, "%+.2f%%", ctx.change24hPercent) else "N/A"
        val rsiStr = ctx.rsi14?.let { String.format(Locale.US, "%.1f", it) } ?: "Unavailable"
        val derivLine = ctx.derivativesSummary ?: "Derivatives data (Funding/OI) unavailable right now."
        val newsNote = if (ctx.recentNewsHeadlines.isNotEmpty()) {
            "News feed (${ctx.newsSourceStatus}): ${ctx.recentNewsHeadlines.take(2).joinToString(" | ")}"
        } else {
            "Live breaking news catalyst unavailable."
        }

        return when (lang) {
            LearningLanguageMode.ENGLISH -> """
                📉 Verified Market Movement Breakdown (${ctx.assetName} / ${ctx.symbol})
                • Current Price: $priceStr (24h: $chgStr)
                • Broad Market: ${ctx.marketBreadthDetails}
                • Momentum & Structure: RSI(14) is $rsiStr | ${ctx.emaCondition ?: "EMA structure unavailable"} | ${ctx.bosChochCondition ?: "SMC structure pending"}
                • Derivatives Positioning: $derivLine
                • News Context: $newsNote

                Honest Assessment: Live technicals and market breadth above show what price is doing right now, without guessing unverified news rumors. Watch support at ${fmtPrice(ctx.supportLevel)} and resistance at ${fmtPrice(ctx.resistanceLevel)}.
            """.trimIndent()
            LearningLanguageMode.ROMAN_URDU -> """
                📉 Market Harkat Ka Haqeeqi Jaiza (${ctx.assetName} / ${ctx.symbol})
                • Mojooda Qeemat: $priceStr (24h: $chgStr)
                • Market Breadth: ${ctx.marketBreadthDetails}
                • Momentum aur Structure: RSI(14) = $rsiStr | ${ctx.emaCondition ?: "EMA data dastiyab nahi"} | ${ctx.bosChochCondition ?: "SMC structure range mein hai"}
                • Derivatives (Funding/OI): $derivLine
                • News Soorat-e-Haal: $newsNote

                Wazahat: Rule-based fallback koi jhooti wajah nahi banata. Agar live news mein koi khas khabar nahi to harkat ki wajah zyada tar liquidity sweeps, funding positioning, aur technical levels hote hain. Support ${fmtPrice(ctx.supportLevel)} aur Resistance ${fmtPrice(ctx.resistanceLevel)} par nazar rakhein.
            """.trimIndent()
            LearningLanguageMode.STANDARD_URDU -> """
                📉 مارکیٹ کی حالیہ حرکت کا مستند جائزہ (${ctx.assetName} / ${ctx.symbol})
                • موجودہ قیمت: $priceStr (24 گھنٹے: $chgStr)
                • مجموعی مارکیٹ صورتحال: ${ctx.marketBreadthDetails}
                • مومینٹم اور اسٹرکچر: RSI(14) = $rsiStr | ${ctx.emaCondition ?: "ای ایم اے ڈیٹا دستیاب نہیں"}
                • ڈیریویٹوز (فنڈنگ/اوپن انٹرسٹ): $derivLine
                • خبروں کی صورتحال: $newsNote

                نوٹ: آف لائن اصولی تجزیہ صرف تصدیق شدہ ڈیٹا دکھاتا ہے اور کوئی فرضی وجہ نہیں گھڑتا۔ اہم سپورٹ ${fmtPrice(ctx.supportLevel)} اور مزاحمت ${fmtPrice(ctx.resistanceLevel)} پر نظر رکھیں۔
            """.trimIndent()
            LearningLanguageMode.BILINGUAL -> """
                📉 Market Move Breakdown (${ctx.symbol}) / مارکیٹ کی حرکت کا جائزہ
                • Price: $priceStr ($chgStr) | RSI(14): $rsiStr
                • Breadth: ${ctx.marketBreadthDetails}
                • Derivatives: $derivLine
                • News: $newsNote
                • اردو خلاصہ: تصدیق شدہ ڈیٹا کے مطابق اہم سپورٹ ${fmtPrice(ctx.supportLevel)} اور مزاحمت ${fmtPrice(ctx.resistanceLevel)} ہے؛ بغیر تصدیق کسی خبر پر قیاس آرائی نہ کریں۔
            """.trimIndent()
        }
    }

    private fun buildWatchlistSetupAnalysis(ctx: MarketContextSnapshot, lang: LearningLanguageMode): String {
        val setupText = ctx.watchlistSummary
            ?: "No high-conviction watchlist or futures scanner setups are currently populated. Open the Scanner tab and run a live Binance USDT-M scan."
        return when (lang) {
            LearningLanguageMode.ENGLISH -> """
                🔍 Watchlist & Scanner Setup Rankings
                $setupText

                Note: Higher scanner scores reflect multi-timeframe alignment, volume, and R:R — not guaranteed outcomes. Always verify invalidation/Stop Loss before entering.
            """.trimIndent()
            LearningLanguageMode.ROMAN_URDU -> """
                🔍 Watchlist aur Scanner Setups Ki Ranking
                $setupText

                Mashwara: Sirf high-score setup par bhi bina Stop Loss aur confirmation ke trade na lein; Scanner tab se live scan refresh zaroor karein.
            """.trimIndent()
            LearningLanguageMode.STANDARD_URDU -> """
                🔍 واچ لسٹ اور اسکینر سیٹ اپس کی درجہ بندی
                $setupText

                ہدایت: زیادہ اسکور والے سیٹ اپ میں بھی اسٹاپ لاس اور کینڈل تصدیق کے بغیر انٹری نہ لیں۔
            """.trimIndent()
            LearningLanguageMode.BILINGUAL -> """
                🔍 Watchlist Setup Rankings / واچ لسٹ کے مضبوط سیٹ اپس
                $setupText
                • اردو نوٹ: کسی بھی سیٹ اپ پر ٹریڈ سے پہلے اسٹاپ لاس اور رسک ٹو ریوارڈ کی تصدیق لازمی ہے۔
            """.trimIndent()
        }
    }

    private fun buildBalancedEntryScenarios(ctx: MarketContextSnapshot, lang: LearningLanguageMode): String {
        val sup = fmtPrice(ctx.supportLevel)
        val res = fmtPrice(ctx.resistanceLevel)
        val fib = ctx.fibGoldenPocket ?: "Unavailable"
        val rsi = ctx.rsi14?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A"
        return when (lang) {
            LearningLanguageMode.ENGLISH -> """
                ⚖️ Balanced Trade Scenarios for ${ctx.assetName} (${ctx.symbol}) — Not Financial Advice
                • Current Price: ${fmtPrice(ctx.currentPrice)} | RSI(14): $rsi | Timeframe: ${ctx.timeframe}
                • Bullish Scenario: Wait for price to hold support near $sup (or Fib Golden Pocket $fib) with bullish structure/CHoCH confirmation. Invalidation: clean candle close below $sup.
                • Bearish Scenario: Rejection near resistance $res or breakdown below $sup opens downside continuation; avoid chasing green candles into resistance.
                • Risk Rule: Never risk more than 1–2% of account equity; no entry guarantees profit.
            """.trimIndent()
            LearningLanguageMode.ROMAN_URDU -> """
                ⚖️ ${ctx.assetName} (${ctx.symbol}) Ke Balanced Scenarios (Koi Guarantee Nahi)
                • Qeemat: ${fmtPrice(ctx.currentPrice)} | RSI(14): $rsi | Timeframe: ${ctx.timeframe}
                • Bullish Scenario (Kharidari Zone): Agar price $sup support ya Fib Golden Pocket ($fib) par hold kare aur bullish confirmation de tab chhota entry zone ban sakta hai. Invalidation: $sup ke neeche candle close.
                • Bearish Scenario (Girawat Khatra): Agar $res resistance se rejection aaye ya $sup toot jaye to mazeed neeche ja sakti hai.
                • Risk Usool: Hamesha 1–2% capital risk aur Stop Loss lagayein.
            """.trimIndent()
            LearningLanguageMode.STANDARD_URDU -> """
                ⚖️ ${ctx.assetName} (${ctx.symbol}) کے متوازن تجارتی منظرنامے
                • موجودہ قیمت: ${fmtPrice(ctx.currentPrice)} | RSI: $rsi
                • بلش منظرنامہ (خریداری کا امکان): سپورٹ $sup یا فبوناچی گولڈن پاکٹ ($fib) پر مضبوطی اور بلش تصدیق کے بعد انٹری پر غور کیا جا سکتا ہے، جبکہ $sup سے نیچے کلوزنگ پر سیٹ اپ منسوخ (Invalidate) ہو گا۔
                • بیئرش منظرنامہ: مزاحمت $res سے واپسی یا سپورٹ ٹوٹنے کی صورت میں مزید کمی کا خطرہ موجود رہے گا۔
                • رسک اصول: منافع کی کوئی ضمانت نہیں ہوتی؛ کل سرمائے کا ۱ سے ۲ فیصد سے زیادہ رسک نہ لیں۔
            """.trimIndent()
            LearningLanguageMode.BILINGUAL -> """
                ⚖️ Balanced Scenarios (${ctx.symbol}) / متوازن تجارتی امکانات
                • Price: ${fmtPrice(ctx.currentPrice)} | Support: $sup | Resistance: $res | Fib Zone: $fib
                • Bullish trigger: Hold above $sup with confirmation (Invalidation < $sup). Bearish risk: Rejection at $res.
                • اردو ہدایت: جلد بازی میں خریداری کے بجائے تصدیق اور واضح اسٹاپ لاس کا انتظار کریں۔
            """.trimIndent()
        }
    }

    private fun buildSupportResistanceLevels(ctx: MarketContextSnapshot, lang: LearningLanguageMode): String {
        val sup = fmtPrice(ctx.supportLevel)
        val res = fmtPrice(ctx.resistanceLevel)
        val fib = ctx.fibGoldenPocket ?: "Unavailable"
        return when (lang) {
            LearningLanguageMode.ENGLISH -> """
                📐 Verified Key Levels for ${ctx.assetName} (${ctx.symbol}, ${ctx.timeframe})
                • Current Price: ${fmtPrice(ctx.currentPrice)}
                • Nearest Support / Demand: $sup
                • Nearest Resistance / Supply: $res
                • Fibonacci Golden Pocket (0.5–0.618): $fib
                • SMC Context: ${ctx.bosChochCondition ?: "Pending"} | ${ctx.fvgStatus ?: "No FVG summary"}
            """.trimIndent()
            LearningLanguageMode.ROMAN_URDU -> """
                📐 ${ctx.assetName} (${ctx.symbol}) Ke Ahem Levels (${ctx.timeframe})
                • Qeemat: ${fmtPrice(ctx.currentPrice)}
                • Support / Demand Zone: $sup
                • Resistance / Supply Zone: $res
                • Fibonacci Golden Pocket: $fib
                • SMC Structure: ${ctx.bosChochCondition ?: "Pending"}
            """.trimIndent()
            LearningLanguageMode.STANDARD_URDU -> """
                📐 ${ctx.assetName} (${ctx.symbol}) کی اہم سطحیں (${ctx.timeframe})
                • موجودہ قیمت: ${fmtPrice(ctx.currentPrice)}
                • قریبی سپورٹ (سہارا): $sup
                • قریبی مزاحمت (رکاوٹ): $res
                • فبوناچی زون (0.5–0.618): $fib
                • اسمارٹ منی اسٹرکچر: ${ctx.bosChochCondition ?: "زیر جائزہ"}
            """.trimIndent()
            LearningLanguageMode.BILINGUAL -> """
                📐 Key Levels (${ctx.symbol}) / اہم سپورٹ و مزاحمت
                • Support: $sup | Resistance: $res | Fib Golden Pocket: $fib
                • اردو: قیمت ${fmtPrice(ctx.currentPrice)} پر ہے؛ سپورٹ ٹوٹنے پر احتیاط اور مزاحمت کے بریک آؤٹ پر ری ٹیسٹ دیکھیں۔
            """.trimIndent()
        }
    }

    private fun buildFurtherDownsideScenarios(ctx: MarketContextSnapshot, lang: LearningLanguageMode): String {
        val sup = fmtPrice(ctx.supportLevel)
        val res = fmtPrice(ctx.resistanceLevel)
        val deriv = ctx.derivativesSummary ?: "Funding/OI unavailable"
        return when (lang) {
            LearningLanguageMode.ENGLISH -> """
                🧭 Downside vs Recovery Risk Assessment (${ctx.symbol})
                Nobody can predict with 100% certainty whether price will fall further, so evaluate both triggers:
                • Further Downside Trigger: If ${ctx.symbol} closes decisively below support ($sup) on elevated volume or rising Open Interest with negative momentum, lower liquidity pockets become vulnerable.
                • Stabilization / Bounce Trigger: Holding $sup, cooling/negative funding rates, and reclaiming $res weaken bearish pressure.
                • Live Derivatives: $deriv
            """.trimIndent()
            LearningLanguageMode.ROMAN_URDU -> """
                🧭 Kya Market Mazeed Gir Sakti Hai? (${ctx.symbol})
                100% yaqeen se koi nahi keh sakta, is liye dono taraf ke triggers dekhein:
                • Mazeed Girawat Ka Khatra: Agar price $sup support se neeche close de aur selling volume barhe to agle liquidity levels tak girawat aa sakti hai.
                • Sambhalne Ki Nishani: Agar $sup hold rahe aur price $res ki taraf wapas barhe to dabao kam ho sakta hai.
                • Derivatives: $deriv
            """.trimIndent()
            LearningLanguageMode.STANDARD_URDU -> """
                🧭 کیا مارکیٹ مزید نیچے گر سکتی ہے؟ (${ctx.symbol})
                سو فیصد یقین کے ساتھ پیش گوئی ممکن نہیں، لہٰذا دونوں صورتوں پر نظر رکھیں:
                • مزید کمی کا خطرہ: اگر قیمت سپورٹ ($sup) کے نیچے مضبوطی سے بند ہو جائے تو نچلی لیکویڈیٹی کی طرف دباؤ بڑھ سکتا ہے۔
                • بحالی کا امکان: اگر سپورٹ برقرار رہے اور قیمت مزاحمت ($res) کی طرف لوٹے تو مندی کا دباؤ کم ہو سکتا ہے۔
                • ڈیریویٹوز: $deriv
            """.trimIndent()
            LearningLanguageMode.BILINGUAL -> """
                🧭 Downside Risk (${ctx.symbol}) / مزید کمی یا بحالی کا امکان
                • Downside risk increases if $sup breaks; recovery needs $sup hold & reclaim toward $res.
                • اردو: سپورٹ $sup ٹوٹنے پر مزید کمی ممکن ہے، جبکہ اس کے برقرار رہنے پر بحالی کا امکان رہے گا۔
            """.trimIndent()
        }
    }

    private fun buildPpiExplanation(ctx: MarketContextSnapshot, lang: LearningLanguageMode): String = when (lang) {
        LearningLanguageMode.ENGLISH -> """
            🏭 Beginner Guide: What Is PPI (Producer Price Index)?
            • Definition: PPI measures average price changes received by domestic producers (wholesalers/manufacturers) for their goods and services.
            • Why It Matters for Crypto & Gold: PPI is a leading inflation indicator that often feeds into CPI. Hotter-than-expected PPI signals sticky inflation, which can make the Federal Reserve keep interest rates higher for longer (bearish for risk assets/BTC in the short term, bullish for DXY). Cooler PPI eases rate fears.
            • Trading Tip: Watch both Headline PPI and Core PPI (excluding food & energy), and wait 15–30 minutes after release for liquidity sweeps to settle.
        """.trimIndent()
        LearningLanguageMode.ROMAN_URDU -> """
            🏭 Asaan Lafzon Mein: PPI (Producer Price Index) Kya Hota Hai?
            • Matlab: PPI batata hai ke factories aur manufacturers ke level par cheezon ki qeematon mein kitni mehngai barh ya ghat rahi hai.
            • Crypto aur Gold Par Asar: PPI aksar CPI se pehle mehngai ka ishara deta hai. Agar PPI tawaqqo se zyada aaye to Fed sood ki sharah kam karne mein der karta hai (jo crypto ke liye dabao ban sakta hai). Agar PPI kam aaye to market mein umeed barhti hai.
            • Ehtiyat: Core PPI aur market reaction ke 15–30 minute baad hi structure dekh kar faisla karein.
        """.trimIndent()
        LearningLanguageMode.STANDARD_URDU -> """
            🏭 آسان الفاظ میں: پی پی آئی (PPI - Producer Price Index) کیا ہے؟
            • تعریف: پی پی آئی یہ ناپتا ہے کہ کارخانوں اور پیداواری سطح پر اشیاء اور خدمات کی قیمتوں میں کتنی تبدیلی آ رہی ہے۔
            • کرپٹو اور سونے پر اثر: یہ مستقبل کی مہنگائی (CPI) کا ابتدائی اشارہ ہوتا ہے۔ اگر پی پی آئی توقع سے زیادہ آئے تو فیڈرل ریزرو سود کی شرح زیادہ رکھنے پر مجبور ہو سکتا ہے، جس سے بٹ کوائن اور رسکی اثاثوں پر دباؤ آتا ہے؛ کم آنے پر مارکیٹ کو سہارا ملتا ہے۔
        """.trimIndent()
        LearningLanguageMode.BILINGUAL -> """
            🏭 PPI (Producer Price Index) / پی پی آئی کی وضاحت
            • PPI tracks wholesale/producer inflation and acts as a leading signal for CPI and Fed rate policy.
            • اردو: پی پی آئی پیداواری سطح کی مہنگائی ظاہر کرتا ہے؛ اس کے زیادہ آنے سے شرحِ سود میں نرمی مؤخر ہونے کا خدشہ اور کم آنے پر مارکیٹ میں مثبت امید پیدا ہوتی ہے۔
        """.trimIndent()
    }

    private fun buildCpiMacroExplanation(ctx: MarketContextSnapshot, lang: LearningLanguageMode): String = when (lang) {
        LearningLanguageMode.ENGLISH -> """
            🏛️ [MACROECONOMIC & CPI REASONING]
            What CPI Is & Why Markets Sometimes Drop Even on "Good" CPI:
            • CPI (Consumer Price Index) tracks retail inflation paid by consumers. Core CPI excludes volatile food and energy.
            • Why price can drop on cooler CPI:
              1. "Sell the News": Institutions often price in soft CPI days beforehand and take profits on release.
              2. Core vs Headline Divergence: If Core CPI remains sticky, the Fed stays cautious on rate cuts.
              3. DXY & Treasury Yields: Strong bond yields still tighten liquidity.
              4. Liquidation Sweeps: Over-leveraged longs get wiped out before the real move develops.
        """.trimIndent()
        LearningLanguageMode.ROMAN_URDU -> """
            🏛️ [CPI AUR MACROECONOMIC REASONING]
            CPI kya hai aur kabhi achi CPI par bhi market kyun girti hai?
            1. Sell the News: Baray traders news se pehle buy kar chuke hote hain aur news par profit book karte hain.
            2. Core CPI: Agar food/energy ke ilawa Core CPI zyada rahe to Fed narmi nahi dikhata.
            3. DXY aur Yields: Mazboot Dollar aur bond yields crypto liquidity par dabao dalte hain.
            4. Liquidation Sweep: High leverage longs pehle liquidate hotay hain.
        """.trimIndent()
        LearningLanguageMode.STANDARD_URDU -> """
            🏛️ [سی پی آئی اور معاشی ڈیٹا کی منطق]
            سی پی آئی صارفین کی سطح پر مہنگائی کو ناپتا ہے۔ کبھی کم مہنگائی پر بھی مارکیٹ گرنے کی وجوہات:
            ۱. خبر پر منافع خوری (Sell the News): بڑے ادارے پہلے خرید کر خبر آتے ہی منافع سمیٹتے ہیں۔
            ۲. کور سی پی آئی: بنیادی مہنگائی برقرار رہنے پر فیڈرل ریزرو سخت پالیسی جاری رکھ سکتا ہے۔
            ۳. لیکویڈیشن سوئپ: زیادہ لیوریج والی پوزیشنز کے اسٹاپ لاس ہٹ ہوتے ہیں۔
        """.trimIndent()
        LearningLanguageMode.BILINGUAL -> """
            🏛️ [CPI & MACRO REASONING / سی پی آئی کی وضاحت]
            • CPI measures consumer inflation; markets can still dip on "Sell the News" profit-taking, sticky Core CPI, or leveraged liquidation sweeps.
            • اردو: سی پی آئی صارفین کی مہنگائی کا پیمانہ ہے؛ خبر مثبت آنے پر بھی منافع خوری اور لیکویڈیشن سوئپ کی وجہ سے وقتی کمی آ سکتی ہے۔
        """.trimIndent()
    }

    private fun buildFedExplanation(ctx: MarketContextSnapshot, lang: LearningLanguageMode): String = when (lang) {
        LearningLanguageMode.ENGLISH -> """
            🏦 Beginner Guide: How Fed (FOMC) Rate Decisions Move Crypto
            • Rate Hikes / "Higher for Longer": Borrowing becomes expensive, USD (DXY) strengthens, and liquidity leaves volatile assets like Bitcoin and altcoins.
            • Rate Cuts / Dovish Guidance: Lower borrowing costs expand global liquidity and typically support risk-on assets over the medium term.
            • Key Nuance: Markets react more to the FOMC dot plot & Jerome Powell's press conference tone than the expected rate decision itself.
        """.trimIndent()
        LearningLanguageMode.ROMAN_URDU -> """
            🏦 Asaan Lafzon Mein: Fed (FOMC) Interest Rate Faislay Ka Asar
            • Sood Barhna / High Rehna (Hawkish): Qarza mehnga hota hai, Dollar (DXY) mazboot hota hai, aur crypto jaisi volatile markets se paisa nikal sakta hai.
            • Sood Kam Hona (Dovish): Market mein sasta paisa aur liquidity aati hai jo aksar BTC aur stocks ko sahara deti hai.
            • Zaroori Baat: Rate decision se zyada Jerome Powell ki press conference aur aage ke isharay market ko hilatay hain.
        """.trimIndent()
        LearningLanguageMode.STANDARD_URDU -> """
            🏦 آسان الفاظ میں: فیڈرل ریزرو (Fed / FOMC) کے فیصلوں کا اثر
            • شرحِ سود میں اضافہ یا سخت مؤقف: ڈالر مضبوط ہوتا ہے اور مارکیٹ میں نقد لیکویڈیٹی کم ہونے سے کرپٹو پر دباؤ آتا ہے۔
            • شرحِ سود میں کمی یا نرم اشارے: مارکیٹ میں سرمایہ کاری اور لیکویڈیٹی بڑھتی ہے۔
            • اہم نکتہ: شرحِ سود کے اعلان کے ساتھ فیڈ چیئرمین کی گفتگو اور آئندہ معاشی پیش گوئی زیادہ بڑا ردعمل پیدا کرتی ہے۔
        """.trimIndent()
        LearningLanguageMode.BILINGUAL -> """
            🏦 Fed / FOMC Rate Decisions / فیڈرل ریزرو پالیسی
            • Hawkish/high rates tighten liquidity and boost DXY; dovish/rate cuts ease financial conditions.
            • اردو: شرحِ سود زیادہ رہنے پر ڈالر مضبوط اور کرپٹو پر دباؤ رہتا ہے، جبکہ کمی کے اشاروں سے لیکویڈیٹی بہتر ہوتی ہے۔
        """.trimIndent()
    }

    private fun buildFundingRateExplanation(ctx: MarketContextSnapshot, lang: LearningLanguageMode): String {
        val liveFunding = ctx.fundingRatePercent?.let { String.format(Locale.US, "%+.4f%%", it) } ?: "Unavailable"
        return when (lang) {
            LearningLanguageMode.ENGLISH -> """
                💸 Beginner Guide: Perpetual Futures Funding Rates
                • Current ${ctx.symbol} Funding Rate: $liveFunding
                • What It Is: Periodic fee paid between Long and Short traders in perpetual futures so futures price stays close to spot price.
                • Positive Funding (+): Longs pay Shorts — too many traders are leveraged long (crowded bullishness risks a long squeeze).
                • Negative Funding (-): Shorts pay Longs — bearish crowding (can fuel a short squeeze upward if support holds).
            """.trimIndent()
            LearningLanguageMode.ROMAN_URDU -> """
                💸 Asaan Lafzon Mein: Funding Rate Kya Hota Hai?
                • ${ctx.symbol} Mojooda Funding Rate: $liveFunding
                • Matlab: Futures market mein Long aur Short traders ke darmian har kuch ghanton baad ada hone wali fee.
                • Positive (+): Long walay Short walon ko dete hain — yani zyada log Long hain (zyada bheed ho to neeche jhatka/long squeeze aa sakta hai).
                • Negative (-): Short walay Long walon ko dete hain — zyada log bearish hain (short squeeze ka imkan ban sakta hai).
            """.trimIndent()
            LearningLanguageMode.STANDARD_URDU -> """
                💸 آسان الفاظ میں: فنڈنگ ریٹ (Funding Rate) کیا ہے؟
                • ${ctx.symbol} کا موجودہ فنڈنگ ریٹ: $liveFunding
                • تعریف: فیوچرز مارکیٹ میں لانگ اور شارٹ ٹریڈرز کے درمیان لگنے والی متوازن فیس۔
                • مثبت (+) فنڈنگ: لانگ ٹریڈرز ادائیگی کرتے ہیں (مارکیٹ میں ضرورت سے زیادہ خریدار اور لانگ اسکوئیز کا خطرہ)۔
                • منفی (-) فنڈنگ: شارٹ ٹریڈرز ادائیگی کرتے ہیں (زیادہ مندی کی پوزیشنز، جس سے شارٹ اسکوئیز آ سکتا ہے)۔
            """.trimIndent()
            LearningLanguageMode.BILINGUAL -> """
                💸 Funding Rates (${ctx.symbol}: $liveFunding) / فنڈنگ ریٹ کی وضاحت
                • Positive funding means crowded longs (longs pay shorts); negative funding means crowded shorts.
                • اردو: مثبت فنڈنگ میں لانگ پوزیشنز زیادہ ہوتی ہیں اور منفی فنڈنگ میں شارٹ پوزیشنز کا ہجوم ہوتا ہے۔
            """.trimIndent()
        }
    }

    private fun buildOpenInterestExplanation(ctx: MarketContextSnapshot, lang: LearningLanguageMode): String {
        val liveOi = ctx.openInterestContracts?.let { String.format(Locale.US, "%.2f contracts", it) } ?: "Unavailable"
        return when (lang) {
            LearningLanguageMode.ENGLISH -> """
                📊 Beginner Guide: Open Interest (OI)
                • Current ${ctx.symbol} Open Interest: $liveOi
                • What It Is: Total number of active, unsettled futures contracts currently open in the market.
                • Price Up + OI Up: Fresh leveraged money entering long trend (strong trend, though watch overheating).
                • Price Down + OI Up: Aggressive new shorts opening (heavy bearish pressure or short-squeeze fuel).
                • Price Down + OI Down: Long liquidations / positions closing out (leverage flush).
            """.trimIndent()
            LearningLanguageMode.ROMAN_URDU -> """
                📊 Asaan Lafzon Mein: Open Interest (OI) Kya Hai?
                • ${ctx.symbol} Mojooda OI: $liveOi
                • Matlab: Market mein is waqt kitne total futures contracts khulay (active) hain.
                • Price Ooper + OI Ooper: Naya paisa trend mein aa raha hai.
                • Price Neeche + OI Neeche: Log positions band kar rahe hain ya longs liquidate ho rahe hain (market leverage saaf ho rahi hai).
                • Price Neeche + OI Ooper: Naye aggressive Shorts khul rahe hain.
            """.trimIndent()
            LearningLanguageMode.STANDARD_URDU -> """
                📊 آسان الفاظ میں: اوپن انٹرسٹ (Open Interest) کیا ہے؟
                • ${ctx.symbol} کا موجودہ اوپن انٹرسٹ: $liveOi
                • تعریف: مارکیٹ میں اس وقت کھلے ہوئے کل فیوچرز کنٹریکٹس کی تعداد۔
                • قیمت اور OI دونوں بڑھیں: نیا سرمایہ اور لیوریج مارکیٹ میں داخل ہو رہا ہے۔
                • قیمت گرے اور OI بھی گرے: پرانی پوزیشنز بند یا لیکویڈیٹ ہو رہی ہیں۔
            """.trimIndent()
            LearningLanguageMode.BILINGUAL -> """
                📊 Open Interest (${ctx.symbol}: $liveOi) / اوپن انٹرسٹ کی وضاحت
                • Measures total active open futures contracts; rising OI = new leverage entering, falling OI = positions closing/liquidating.
                • اردو: اوپن انٹرسٹ کھلے فیوچرز معاہدوں کی کل تعداد ہے جو مارکیٹ میں نئے پیسے یا لیکویڈیشن کی نشاندہی کرتا ہے۔
            """.trimIndent()
        }
    }

    private fun buildLiquidationsExplanation(ctx: MarketContextSnapshot, lang: LearningLanguageMode): String = when (lang) {
        LearningLanguageMode.ENGLISH -> """
            💥 Beginner Guide: Crypto Liquidations & Liquidation Cascades
            • What It Is: When a leveraged trader's margin drops below the maintenance threshold, the exchange forcibly closes their position at market price.
            • Why It Causes Violent Moves: Forced closure of Longs triggers market sell orders, pushing price lower and liquidating the next cluster of stops — a "liquidation cascade" (and vice versa for Short squeezes).
            • How to Protect Yourself: Keep leverage low (3x–5x max), always place a Stop Loss before the liquidation price, and avoid trading right into clustered liquidity sweeps.
        """.trimIndent()
        LearningLanguageMode.ROMAN_URDU -> """
            💥 Asaan Lafzon Mein: Liquidations Kya Hoti Hain?
            • Matlab: Jab aap high leverage par trade lete hain aur price mukhalif simt mein jaye to exchange apki margin khatam hone par zabardasti trade band kar deta hai — isay Liquidation kehte hain.
            • Tez Girawat Kyun Aati Hai: Jab bohot saaray Longs aik sath liquidate hote hain to exchange ki auto-selling price ko mazeed neeche khenchti hai (Cascade).
            • Bachao: Leverage kam rakhein (3x–5x) aur liquidation se pehle Stop Loss lagayein.
        """.trimIndent()
        LearningLanguageMode.STANDARD_URDU -> """
            💥 آسان الفاظ میں: لیکویڈیشن (Liquidations) کیا ہوتی ہیں؟
            • تعریف: جب لیوریج ٹریڈ میں نقصان آپ کے مارجن کے برابر پہنچ جائے تو ایکسچینج زبردستی پوزیشن بند کر دیتا ہے۔
            • تیز اتار چڑھاؤ کی وجہ: جب بہت سے ٹریڈرز ایک ساتھ لیکویڈیٹ ہوتے ہیں تو خودکار آرڈرز قیمت کو مزید تیزی سے دھکیلتے ہیں۔
            • تحفظ: کم لیوریج (زیادہ سے زیادہ ۳ تا ۵ گنا) استعمال کریں اور ہمیشہ اسٹاپ لاس لگائیں۔
        """.trimIndent()
        LearningLanguageMode.BILINGUAL -> """
            💥 Liquidations Guide / لیکویڈیشن کی وضاحت
            • Forced position closures on excess leverage can trigger sharp cascades; protect capital with 3x–5x max leverage and hard Stop Loss.
            • اردو: زیادہ لیوریج پر ایکسچینج کے زبردستی پوزیشن بند کرنے سے تیز جھٹکے آتے ہیں؛ ہمیشہ کم لیوریج اور اسٹاپ لاس استعمال کریں۔
        """.trimIndent()
    }

    private fun buildTimeframeAdvice(ctx: MarketContextSnapshot, lang: LearningLanguageMode): String = when (lang) {
        LearningLanguageMode.ENGLISH -> """
            ⏱️ [TIMEFRAME ADVISOR & NOISE COMPARISON]
            Comparing 1m, 5m, 15m, 1h, and 4h for ${ctx.assetName} (${ctx.symbol}):
            • 1m & 5m (Scalping): High noise and false breakouts.
            • 15m (Execution): Best for timing entries aligned with 1h/4h direction.
            • 1h & 4h (Structure): Reveals institutional trend, BOS/CHoCH, and key S/R (${ctx.multiTimeframeSummary ?: "check Analysis tab"}).
        """.trimIndent()
        LearningLanguageMode.ROMAN_URDU -> """
            ⏱️ [TIMEFRAME ADVISOR - KAUNSA TIMEFRAME BEHTAR HAI?]
            ${ctx.assetName} (${ctx.symbol}) ke liye mawazna:
            • 5m: Bohat zyada market noise aur fake breakouts.
            • 15m: Entry timing ke liye behtareen jab 1h/4h trend clear ho.
            • 1h & 4h: Asal market structure aur trend ke liye (${ctx.multiTimeframeSummary ?: "Analysis tab dekhein"}).
        """.trimIndent()
        LearningLanguageMode.STANDARD_URDU -> """
            ⏱️ [ٹائم فریم مشیر — پانچ منٹ بمقابلہ ایک گھنٹہ]
            ${ctx.assetName} کے لیے موازنہ:
            • ۵ منٹ: زیادہ شور اور فیک بریک آؤٹس۔
            • ۱۵ منٹ: انٹری ٹائمنگ کے لیے بہتر۔
            • ۱ گھنٹہ اور ۴ گھنٹہ: حقیقی رجحان اور سپورٹ/مزاحمت کی شناخت کے لیے بہترین۔
        """.trimIndent()
        LearningLanguageMode.BILINGUAL -> """
            ⏱️ [TIMEFRAME ADVISOR / ٹائم فریم کا موازنہ]
            • 5m has high noise; use 1h/4h for macro bias and 15m for execution (${ctx.symbol}).
            • اردو: ۵ منٹ کے بجائے ۱ اور ۴ گھنٹے پر سمت دیکھ کر ۱۵ منٹ پر انٹری تلاش کریں۔
        """.trimIndent()
    }

    private fun buildRiskAnalysis(ctx: MarketContextSnapshot, lang: LearningLanguageMode): String = when (lang) {
        LearningLanguageMode.ENGLISH -> """
            🛡️ [CAPITAL PRESERVATION & RISK MANAGEMENT]
            • Position Risk: Cap risk per trade at 1%–2% of total account equity.
            • Leverage: Keep leverage modest (3x–5x max) to avoid liquidation cascades.
            • Stop Loss: Place invalidation below verified support (${fmtPrice(ctx.supportLevel)}) for longs or above resistance (${fmtPrice(ctx.resistanceLevel)}) for shorts.
        """.trimIndent()
        LearningLanguageMode.ROMAN_URDU -> """
            🛡️ [RISK MANAGEMENT AUR CAPITAL KI HIFAZAT]
            • Account Risk: Aik trade mein 1%–2% se zyada risk na lein.
            • Leverage: 3x–5x tak mehdood rakhein.
            • Stop Loss: Support (${fmtPrice(ctx.supportLevel)}) ke neeche ya Resistance (${fmtPrice(ctx.resistanceLevel)}) ke ooper lazmi lagayein.
        """.trimIndent()
        LearningLanguageMode.STANDARD_URDU -> """
            🛡️ [رسک مینجمنٹ اور سرمائے کا تحفظ]
            • فی ٹریڈ اپنے کل سرمائے کا ۱ سے ۲ فیصد سے زیادہ خطرے میں نہ ڈالیں۔
            • لیوریج ۳ سے ۵ گنا تک محدود رکھیں اور سپورٹ (${fmtPrice(ctx.supportLevel)}) کے قریب لازمی اسٹاپ لاس لگائیں۔
        """.trimIndent()
        LearningLanguageMode.BILINGUAL -> """
            🛡️ [RISK MANAGEMENT / رسک مینجمنٹ]
            • Risk 1%–2% max, cap leverage at 3x–5x, and place Stop Loss beyond ${fmtPrice(ctx.supportLevel)} / ${fmtPrice(ctx.resistanceLevel)}.
            • اردو: ۱ سے ۲ فیصد رسک اور لازمی اسٹاپ لاس کے بغیر ٹریڈ مت کریں۔
        """.trimIndent()
    }

    private fun buildGeneralAssetSnapshot(ctx: MarketContextSnapshot, lang: LearningLanguageMode): String {
        val rsi = ctx.rsi14 ?: 50.0
        val rsiStatus = when {
            rsi > 70.0 -> "Overbought (>70)"
            rsi < 30.0 -> "Oversold (<30)"
            else -> "Neutral (30-70)"
        }
        val priceStr = fmtPrice(ctx.currentPrice)
        val changeStr = String.format(Locale.US, "%+.2f%%", ctx.change24hPercent)

        return when (lang) {
            LearningLanguageMode.ENGLISH -> """
                📊 [OFFLINE RULE-BASED ANALYSIS]
                Asset: ${ctx.assetName} (${ctx.symbol}) | Price: $priceStr ($changeStr) | TF: ${ctx.timeframe}
                • RSI (14): ${ctx.rsi14?.let { String.format(Locale.US, "%.1f", it) + " — $rsiStatus" } ?: "Unavailable"}
                • Trend / EMA: ${ctx.emaCondition ?: "Unavailable"}
                • SMC Structure: ${ctx.bosChochCondition ?: "Pending"} | ${ctx.fvgStatus ?: "No FVG data"}
                • Key Levels: Support ${fmtPrice(ctx.supportLevel)} | Resistance ${fmtPrice(ctx.resistanceLevel)} | Fib ${ctx.fibGoldenPocket ?: "N/A"}
                • Derivatives: ${ctx.derivativesSummary ?: "Unavailable"}
                • Scenario Guidance: ${if (rsi > 70.0) "Extended/overbought — wait for pullback confirmation before longing." else if (rsi < 30.0) "Oversold — watch for bullish structure shift at support." else "Balanced range — wait for clean breakout above resistance or hold at support with 1-2% risk."}
            """.trimIndent()
            LearningLanguageMode.ROMAN_URDU -> """
                📊 [OFFLINE RULE-BASED ANALYSIS - NO FAKE AI]
                Asset: ${ctx.assetName} (${ctx.symbol}) | Qeemat: $priceStr ($changeStr) | TF: ${ctx.timeframe}
                • RSI (14): ${ctx.rsi14?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A"} ($rsiStatus)
                • EMA Trend: ${ctx.emaCondition ?: "Dastiyab nahi"}
                • Support: ${fmtPrice(ctx.supportLevel)} | Resistance: ${fmtPrice(ctx.resistanceLevel)}
                • Derivatives: ${ctx.derivativesSummary ?: "Dastiyab nahi"}
                • Mashwara: Support ya Resistance ki confirmation ke baghair jaldi mein trade na lein aur Stop Loss lagayein.
            """.trimIndent()
            LearningLanguageMode.STANDARD_URDU -> """
                📊 [آف لائن اصولوں پر مبنی تجارتی تجزیہ]
                اثاثہ: ${ctx.assetName} (${ctx.symbol}) | قیمت: $priceStr ($changeStr) | ٹائم فریم: ${ctx.timeframe}
                • آر ایس آئی (RSI 14): ${ctx.rsi14?.let { String.format(Locale.US, "%.1f", it) } ?: "دستیاب نہیں"}
                • رجحان (EMA): ${ctx.emaCondition ?: "دستیاب نہیں"}
                • سپورٹ: ${fmtPrice(ctx.supportLevel)} | مزاحمت: ${fmtPrice(ctx.resistanceLevel)}
                • ہدایت: تصدیق کے بغیر جلد بازی میں ٹریڈ نہ لگائیں اور ۱ تا ۲ فیصد سے زیادہ رسک نہ لیں۔
            """.trimIndent()
            LearningLanguageMode.BILINGUAL -> """
                📊 [HYBRID BILINGUAL ANALYSIS / مشترکہ تجزیہ]
                Asset: ${ctx.assetName} (${ctx.symbol}) | Price: $priceStr ($changeStr)
                • RSI(14): ${ctx.rsi14?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A"} | Support: ${fmtPrice(ctx.supportLevel)} | Resistance: ${fmtPrice(ctx.resistanceLevel)}
                • اردو خلاصہ: تصدیق شدہ لیولز کے مطابق واضح بریک آؤٹ یا سپورٹ ری ٹیسٹ کا انتظار کریں اور اسٹاپ لاس لازمی لگائیں۔
            """.trimIndent()
        }
    }

    private fun buildLimitedScopeNotice(
        query: String,
        ctx: MarketContextSnapshot,
        lang: LearningLanguageMode
    ): String {
        val priceStr = fmtPrice(ctx.currentPrice)
        return when (lang) {
            LearningLanguageMode.ENGLISH -> """
                ℹ️ Your question ("${query.take(80)}") requires the online generative Gemini AI model, which is currently unreachable.
                This limited rule-based offline fallback cannot answer open-ended conversational questions freely. Please check your internet connection or wait briefly for free-tier quota, then tap Retry Online AI.

                Meanwhile, verified snapshot for ${ctx.symbol}: Price $priceStr (${String.format(Locale.US, "%+.2f%%", ctx.change24hPercent)}) | Support ${fmtPrice(ctx.supportLevel)} | Resistance ${fmtPrice(ctx.resistanceLevel)}.
            """.trimIndent()
            LearningLanguageMode.ROMAN_URDU -> """
                ℹ️ Aapka sawal ("${query.take(80)}") online Gemini AI ke liye hai jo abhi connect nahi ho saka.
                Ye mehdood offline rule-based engine har naye sawal ka azaadana jawab nahi de sakta. Meherbani kar ke internet check karein aur "Retry Online AI" dabayein.

                Filhaal ${ctx.symbol} ki qeemat $priceStr | Support ${fmtPrice(ctx.supportLevel)} | Resistance ${fmtPrice(ctx.resistanceLevel)} hai.
            """.trimIndent()
            LearningLanguageMode.STANDARD_URDU -> """
                ℹ️ آپ کا سوال ("${query.take(80)}") آن لائن جنریٹو جیمینائی اے آئی کے لیے ہے جو فی الحال دستیاب نہیں۔
                یہ محدود آف لائن انجن ہر آزادانہ سوال کا جواب نہیں دے سکتا۔ براہِ کرم انٹرنیٹ کنکشن چیک کریں اور "Retry Online AI" بٹن دبائیں۔

                موجودہ تصدیق شدہ قیمت (${ctx.symbol}): $priceStr | سپورٹ: ${fmtPrice(ctx.supportLevel)} | مزاحمت: ${fmtPrice(ctx.resistanceLevel)}۔
            """.trimIndent()
            LearningLanguageMode.BILINGUAL -> """
                ℹ️ Open-ended query ("${query.take(80)}") requires Online Gemini AI (currently unavailable). Tap Retry Online AI once connected.
                • اردو: یہ محدود آف لائن موڈ ہر سوال کا آزادانہ جواب نہیں دے سکتا؛ براہِ کرم آن لائن اے آئی کو دوبارہ آزمائیں۔ (${ctx.symbol}: $priceStr)
            """.trimIndent()
        }
    }

    private fun fmtPrice(value: Double?): String {
        if (value == null || value <= 0.0) return "Unavailable"
        return if (value >= 100.0) String.format(Locale.US, "$%.2f", value)
        else if (value >= 1.0) String.format(Locale.US, "$%.3f", value)
        else String.format(Locale.US, "$%.4f", value)
    }
}
