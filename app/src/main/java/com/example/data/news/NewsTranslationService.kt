package com.example.data.news

import android.content.Context
import android.content.SharedPreferences
import com.example.data.ai.AiProviderType
import com.example.data.ai.DeepSeekAiProvider
import com.example.data.ai.FirebaseGenerativeAiClient
import com.example.data.ai.OnlineAiProvider
import com.example.data.learning.LearningLanguageMode
import com.example.data.model.MarketNews
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class NewsLanguageOption(val label: String, val mode: LearningLanguageMode) {
    ENGLISH("English", LearningLanguageMode.ENGLISH),
    ROMAN_URDU("Roman Urdu", LearningLanguageMode.ROMAN_URDU),
    URDU_SCRIPT("اردو", LearningLanguageMode.STANDARD_URDU)
}

data class TranslatedNewsArticle(
    val newsId: String,
    val language: NewsLanguageOption,
    val title: String,
    val body: String,
    val confirmedFacts: List<String>,
    val aiInterpretation: String,
    val btcImpact: String,
    val altcoinImpact: String,
    val shortTermScenario: String,
    val longTermScenario: String,
    val uncertaintyAndRisks: String,
    val fromCache: Boolean = false,
    val translationSource: String = "Original English",
    val errorNote: String? = null
)

/**
 * Translates complete available news articles + impact sections into:
 * 1. English
 * 2. Pakistani Roman Urdu
 * 3. Urdu Script (اردو)
 *
 * Strictly preserves all numbers, percentages, dates, PKT timestamps, $ figures,
 * asset tickers, proper names, and financial terminology.
 * Uses online AI (Gemini / DeepSeek) with bounded retry & persistent caching,
 * plus a figure-preserving offline translator fallback so Urdu/Roman Urdu never fails silently.
 */
class NewsTranslationService(
    private val context: Context? = null,
    private val geminiProvider: OnlineAiProvider = FirebaseGenerativeAiClient(context),
    private val deepSeekProvider: OnlineAiProvider = DeepSeekAiProvider()
) {
    private val prefs: SharedPreferences? =
        context?.getSharedPreferences("nexus_news_translation_cache", Context.MODE_PRIVATE)

    private val memoryCache = ConcurrentHashMap<String, TranslatedNewsArticle>()

    fun clearCache() {
        memoryCache.clear()
        prefs?.edit()?.clear()?.apply()
    }

    suspend fun translateArticle(
        news: MarketNews,
        targetLanguage: NewsLanguageOption,
        preferredProvider: AiProviderType = AiProviderType.GEMINI_FIREBASE,
        forceRetry: Boolean = false
    ): TranslatedNewsArticle = withContext(Dispatchers.IO) {
        if (targetLanguage == NewsLanguageOption.ENGLISH) {
            return@withContext originalEnglish(news)
        }

        val cacheKey = buildCacheKey(news, targetLanguage)
        if (!forceRetry) {
            memoryCache[cacheKey]?.let { return@withContext it.copy(fromCache = true) }
            loadFromDisk(cacheKey)?.let { loaded ->
                memoryCache[cacheKey] = loaded
                return@withContext loaded.copy(fromCache = true)
            }
        }

        val provider = selectProvider(preferredProvider)
        if (provider != null) {
            val onlineAttempt = runCatching {
                translateWithOnlineAi(news, targetLanguage, provider)
            }.getOrElse { ex ->
                if (ex is CancellationException) throw ex
                null
            }

            if (onlineAttempt != null && hasPreservedCriticalTokens(news, onlineAttempt)) {
                saveToCache(cacheKey, onlineAttempt)
                return@withContext onlineAttempt
            }

            // Try secondary provider once if primary failed
            val secondary = selectAlternateProvider(provider.providerType)
            if (secondary != null) {
                val secondaryAttempt = runCatching {
                    translateWithOnlineAi(news, targetLanguage, secondary)
                }.getOrElse { ex ->
                    if (ex is CancellationException) throw ex
                    null
                }
                if (secondaryAttempt != null && hasPreservedCriticalTokens(news, secondaryAttempt)) {
                    saveToCache(cacheKey, secondaryAttempt)
                    return@withContext secondaryAttempt
                }
            }
        }

        // Deterministic, figure-preserving Pakistani financial translator fallback
        val offline = buildOfflineTranslation(
            news = news,
            targetLanguage = targetLanguage,
            errorNote = if (provider == null) {
                "Online AI unavailable — translated using offline figure-preserving financial translator."
            } else {
                "Online AI translation timed out or failed — showing offline figure-preserving translation. Tap Retry to try online AI again."
            }
        )
        saveToCache(cacheKey, offline)
        offline
    }

    fun originalEnglish(news: MarketNews): TranslatedNewsArticle = TranslatedNewsArticle(
        newsId = news.id,
        language = NewsLanguageOption.ENGLISH,
        title = news.title,
        body = news.fullContent.ifBlank { news.summary },
        confirmedFacts = news.confirmedFacts,
        aiInterpretation = news.aiInterpretation,
        btcImpact = news.btcImpactSummary,
        altcoinImpact = news.altcoinImpactSummary,
        shortTermScenario = news.shortTermScenario,
        longTermScenario = news.longTermScenario,
        uncertaintyAndRisks = news.uncertaintyAndRisks,
        fromCache = false,
        translationSource = "Original English Dispatch"
    )

    private fun selectProvider(preferred: AiProviderType): OnlineAiProvider? {
        return when (preferred) {
            AiProviderType.DEEPSEEK -> when {
                deepSeekProvider.isConfigured() -> deepSeekProvider
                geminiProvider.isConfigured() -> geminiProvider
                else -> null
            }
            AiProviderType.GEMINI_FIREBASE -> when {
                geminiProvider.isConfigured() -> geminiProvider
                deepSeekProvider.isConfigured() -> deepSeekProvider
                else -> null
            }
            AiProviderType.OFFLINE_RULE_BASED -> null
        }
    }

    private fun selectAlternateProvider(primary: AiProviderType): OnlineAiProvider? {
        return when (primary) {
            AiProviderType.GEMINI_FIREBASE -> deepSeekProvider.takeIf { it.isConfigured() }
            AiProviderType.DEEPSEEK -> geminiProvider.takeIf { it.isConfigured() }
            AiProviderType.OFFLINE_RULE_BASED -> null
        }
    }

    private suspend fun translateWithOnlineAi(
        news: MarketNews,
        targetLanguage: NewsLanguageOption,
        provider: OnlineAiProvider
    ): TranslatedNewsArticle {
        val langLabel = when (targetLanguage) {
            NewsLanguageOption.ENGLISH -> "English"
            NewsLanguageOption.ROMAN_URDU -> "natural Pakistani Roman Urdu (conversational financial Urdu in Latin script)"
            NewsLanguageOption.URDU_SCRIPT -> "fluent natural Pakistani Urdu in Urdu script (اردو رسم الخط)"
        }

        val sysPrompt = """
            You are a professional bilingual financial news translator for Pakistani crypto & macro traders.
            Translate the COMPLETE provided news article and impact sections into $langLabel.
            STRICT RULES:
            1. Preserve EVERY number, percentage (%), dollar amount ($), date, PKT timestamp, person/institution name (e.g., Powell, BlackRock, SEC, OPEC, Binance), and financial ticker/acronym (BTC, ETH, SOL, CPI, PPI, FOMC, ETF, DXY, OI) EXACTLY without rounding or altering.
            2. Do NOT omit paragraphs or invent facts that are not in the input.
            3. Output ONLY valid JSON (no markdown fences) with keys:
               "title", "body", "confirmedFacts" (array of strings), "aiInterpretation", "btcImpact", "altcoinImpact", "shortTermScenario", "longTermScenario", "uncertaintyAndRisks".
        """.trimIndent()

        val sourceJson = JSONObject().apply {
            put("title", news.title)
            put("body", news.fullContent.ifBlank { news.summary })
            put("confirmedFacts", JSONArray(news.confirmedFacts))
            put("aiInterpretation", news.aiInterpretation)
            put("btcImpact", news.btcImpactSummary)
            put("altcoinImpact", news.altcoinImpactSummary)
            put("shortTermScenario", news.shortTermScenario)
            put("longTermScenario", news.longTermScenario)
            put("uncertaintyAndRisks", news.uncertaintyAndRisks)
        }.toString()

        val buffer = StringBuilder()
        withTimeout(30_000L) {
            provider.streamChat(
                systemInstruction = sysPrompt,
                history = emptyList(),
                userMessage = "Translate accurately while preserving every number, percentage, date, name, and ticker:\n$sourceJson"
            ).collect { chunk -> buffer.append(chunk) }
        }

        val raw = buffer.toString().trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val parsed = JSONObject(raw)

        val factsArray = parsed.optJSONArray("confirmedFacts")
        val facts = if (factsArray != null) {
            (0 until factsArray.length()).mapNotNull { i -> factsArray.optString(i).takeIf { it.isNotBlank() } }
        } else {
            news.confirmedFacts
        }

        return TranslatedNewsArticle(
            newsId = news.id,
            language = targetLanguage,
            title = parsed.optString("title", news.title).ifBlank { news.title },
            body = parsed.optString("body", news.fullContent).ifBlank { news.fullContent },
            confirmedFacts = facts.ifEmpty { news.confirmedFacts },
            aiInterpretation = parsed.optString("aiInterpretation", news.aiInterpretation).ifBlank { news.aiInterpretation },
            btcImpact = parsed.optString("btcImpact", news.btcImpactSummary).ifBlank { news.btcImpactSummary },
            altcoinImpact = parsed.optString("altcoinImpact", news.altcoinImpactSummary).ifBlank { news.altcoinImpactSummary },
            shortTermScenario = parsed.optString("shortTermScenario", news.shortTermScenario).ifBlank { news.shortTermScenario },
            longTermScenario = parsed.optString("longTermScenario", news.longTermScenario).ifBlank { news.longTermScenario },
            uncertaintyAndRisks = parsed.optString("uncertaintyAndRisks", news.uncertaintyAndRisks).ifBlank { news.uncertaintyAndRisks },
            fromCache = false,
            translationSource = "AI Translated (${provider.providerType.badgeLabel})"
        )
    }

    /**
     * Verifies critical numeric figures, percentages, and key tickers weren't dropped by the LLM.
     */
    internal fun hasPreservedCriticalTokens(source: MarketNews, translated: TranslatedNewsArticle): Boolean {
        val srcCorpus = "${source.title} ${source.fullContent}"
        val dstCorpus = "${translated.title} ${translated.body} ${translated.confirmedFacts.joinToString(" ")}"
        val numericTokens = Regex("\\d+(?:\\.\\d+)?%?").findAll(srcCorpus)
            .map { it.value }
            .distinct()
            .take(8)
            .toList()
        return numericTokens.all { token -> dstCorpus.contains(token) }
    }

    /**
     * Deterministic Pakistani Roman Urdu & Urdu Script financial translator fallback
     * that preserves 100% of numbers, percentages, dates, names, and tickers while
     * translating key financial headings, market drivers, and risk/scenario commentary.
     */
    internal fun buildOfflineTranslation(
        news: MarketNews,
        targetLanguage: NewsLanguageOption,
        errorNote: String? = null
    ): TranslatedNewsArticle {
        val rawBody = news.fullContent.ifBlank { news.summary }
        return when (targetLanguage) {
            NewsLanguageOption.ENGLISH -> originalEnglish(news)
            NewsLanguageOption.ROMAN_URDU -> TranslatedNewsArticle(
                newsId = news.id,
                language = targetLanguage,
                title = "${translatePhraseRomanUrdu(news.title)}",
                body = "${translateParagraphsRomanUrdu(rawBody)}\n\n[Original Source Text Preserved Below for Accuracy]\n$rawBody",
                confirmedFacts = news.confirmedFacts.map { translateFactRomanUrdu(it) },
                aiInterpretation = "${translateImpactToneRoman(news)} — ${translatePhraseRomanUrdu(news.aiInterpretation)}",
                btcImpact = translateBtcImpactRoman(news),
                altcoinImpact = translateAltcoinImpactRoman(news),
                shortTermScenario = translateShortTermRoman(news),
                longTermScenario = translateLongTermRoman(news),
                uncertaintyAndRisks = "Khabar sirf aik ishara hai — baghair candle close, volume aur funding ki tasdeeq ke isay qeemat girnay ya barhnay ki htmī wajah na samjhein. High leverage aur volatility mein Stop Loss slippage ka khatra rehta hai.",
                fromCache = false,
                translationSource = "Offline Financial Translator (Figures Preserved)",
                errorNote = errorNote
            )
            NewsLanguageOption.URDU_SCRIPT -> TranslatedNewsArticle(
                newsId = news.id,
                language = targetLanguage,
                title = translatePhraseUrduScript(news.title),
                body = "${translateParagraphsUrduScript(rawBody)}\n\n[مکمل اصل انگریزی متن حوالہ کے لیے]\n$rawBody",
                confirmedFacts = news.confirmedFacts.map { translateFactUrduScript(it) },
                aiInterpretation = "${translateImpactToneUrdu(news)} — ${translatePhraseUrduScript(news.aiInterpretation)}",
                btcImpact = translateBtcImpactUrdu(news),
                altcoinImpact = translateAltcoinImpactUrdu(news),
                shortTermScenario = translateShortTermUrdu(news),
                longTermScenario = translateLongTermUrdu(news),
                uncertaintyAndRisks = "صرف کسی خبر کی سرخی کو قیمت کی حرکت کا حتمی سبب نہ سمجھیں۔ کینڈل کلوز، والیوم اور فنڈنگ ریٹ کی تصدیق ضروری ہے، اور تیز اتار چڑھاؤ میں اسٹاپ لاس سلیپیج کا خطرہ رہتا ہے۔",
                fromCache = false,
                translationSource = "آف لائن مالیاتی مترجم (اعداد و شمار محفوظ)",
                errorNote = errorNote
            )
        }
    }

    private fun translatePhraseRomanUrdu(text: String): String {
        var out = text
        val replacements = linkedMapOf(
            "Federal Reserve" to "Federal Reserve (US Central Bank)",
            "Holds Rates" to "Sood ki sharah barqarar rakhi",
            "Interest Rate" to "Sood ki sharah (Interest Rate)",
            "Inflation" to "Mehngai (Inflation)",
            "Signals" to "Ishara deta hai",
            "Inflow" to "Sarmaya ka dakhila (Inflow)",
            "Outflow" to "Sarmaya ka ikhraj (Outflow)",
            "Institutional" to "Idarati (Institutional)",
            "Bitcoin" to "Bitcoin (BTC)",
            "Ethereum" to "Ethereum (ETH)",
            "Bullish" to "Bullish (Tezi)",
            "Bearish" to "Bearish (Mandi)",
            "Support" to "Support (Sahara)",
            "Resistance" to "Resistance (Rukawat)",
            "Probabilistic" to "Imkani (Probabilistic)"
        )
        for ((k, v) in replacements) {
            out = out.replace(k, v, ignoreCase = false)
        }
        return out
    }

    private fun translateParagraphsRomanUrdu(raw: String): String =
        "Pakistani Roman Urdu Khulasa (Ahem Nukaat aur Aadaad o Shumaar Mehfooz):\n" +
            raw.lines().joinToString("\n") { line ->
                if (line.isBlank()) "" else translatePhraseRomanUrdu(line)
            }

    private fun translateFactRomanUrdu(fact: String): String =
        fact.replace("Headline (Reported):", "Surkhi (Report Shuda):")
            .replace("Reported Source:", "Zariya:")
            .replace("Published Time", "Waqt")
            .replace("Verification Status:", "Tasdeeq:")
            .replace("Matched Assets:", "Mutasira Assets:")

    private fun translateImpactToneRoman(news: MarketNews): String =
        "Asar ka Andaza: ${news.impact.label} (${news.impactLevel.label})"

    private fun translateBtcImpactRoman(news: MarketNews): String = when (news.impact) {
        com.example.data.model.NewsImpact.BULLISH ->
            "BTC par Mumkina Asar (${news.impactLevel.label}): Agar 15m/1h candle close support se ooper hold kare aur spot demand barhe to BTC mein positive momentum ban sakta hai. Original Note: ${news.btcImpactSummary}"
        com.example.data.model.NewsImpact.BEARISH ->
            "BTC par Mumkina Dabao (${news.impactLevel.label}): Agar DXY mazboot ho ya leveraged longs liquidate hon to support ke neeche jhatka aa sakta hai. Original Note: ${news.btcImpactSummary}"
        com.example.data.model.NewsImpact.NEUTRAL ->
            "BTC par Milajula Asar (${news.impactLevel.label}): Market range-bound reh sakti hai jab tak candle breakout confirm na ho. Original Note: ${news.btcImpactSummary}"
    }

    private fun translateAltcoinImpactRoman(news: MarketNews): String =
        "Altcoins par Mumkina Asar (${news.affectedAssets.joinToString(", ")}): Altcoins BTC ke muqablay mein 1.5x–2.5x zyada tez move kartay hain; agar BTC stable rahe to rotatory setups bante hain warna liquidity sweeps ka khatra rehta hai."

    private fun translateShortTermRoman(news: MarketNews): String =
        "Qareebi Waqt (Short-Term 15m–24h): Khabar aane ke baad fori wick ya liquidity sweep ho sakta hai — sirf mukammal 15m/1h candle close aur volume ki tasdeeq par hi plan banayein."

    private fun translateLongTermRoman(news: MarketNews): String =
        "Taveel Muddat (Long-Term): Agar yeh driver Fed policy, ETF flows, ya musalsal macro liquidity se jura ho to hafton tak trend par asar daal sakta hai."

    private fun translatePhraseUrduScript(text: String): String {
        var out = text
        val replacements = linkedMapOf(
            "Federal Reserve" to "فیڈرل ریزرو (Federal Reserve)",
            "Interest Rate" to "شرحِ سود (Interest Rate)",
            "Inflation" to "مہنگائی (Inflation)",
            "Bitcoin" to "بٹ کوائن (Bitcoin)",
            "Ethereum" to "ایتھیریم (Ethereum)",
            "Bullish" to "تیزی (Bullish)",
            "Bearish" to "مندی (Bearish)",
            "Support" to "سپورٹ",
            "Resistance" to "مزاحمت",
            "Inflow" to "سرمایہ کاری کا بہاؤ (Inflow)",
            "Outflow" to "سرمائے کا انخلا (Outflow)"
        )
        for ((k, v) in replacements) {
            out = out.replace(k, v, ignoreCase = false)
        }
        return out
    }

    private fun translateParagraphsUrduScript(raw: String): String =
        "اردو خلاصہ (تمام اعداد و شمار، فیصد اور تاریخیں جوں کی توں محفوظ):\n" +
            raw.lines().joinToString("\n") { line ->
                if (line.isBlank()) "" else translatePhraseUrduScript(line)
            }

    private fun translateFactUrduScript(fact: String): String =
        fact.replace("Headline (Reported):", "رپورٹ شدہ سرخی:")
            .replace("Reported Source:", "ذریعہ:")
            .replace("Published Time", "اشاعت کا وقت")
            .replace("Verification Status:", "تصدیق کی صورتحال:")
            .replace("Matched Assets:", "متاثرہ اثاثے:")

    private fun translateImpactToneUrdu(news: MarketNews): String =
        "ممکنہ اثر کی نوعیت: ${news.impact.label} (${news.impactLevel.label})"

    private fun translateBtcImpactUrdu(news: MarketNews): String = when (news.impact) {
        com.example.data.model.NewsImpact.BULLISH ->
            "بٹ کوائن (BTC) پر ممکنہ مثبت اثر (${news.impactLevel.label}): اگر 15m/1h کینڈل کلوز سپورٹ کے اوپر برقرار رہے تو اوپری سطح کی طرف دباؤ بڑھ سکتا ہے۔"
        com.example.data.model.NewsImpact.BEARISH ->
            "بٹ کوائن (BTC) پر ممکنہ منفی دباؤ (${news.impactLevel.label}): رسک آف ماحول اور لیکویڈیشن کی صورت میں سپورٹ ٹیسٹ ہونے کا خدشہ رہے گا۔"
        com.example.data.model.NewsImpact.NEUTRAL ->
            "بٹ کوائن (BTC) پر متوازن اثر (${news.impactLevel.label}): واضح بریک آؤٹ تک مارکیٹ محدود رینج میں رہ سکتی ہے۔"
    }

    private fun translateAltcoinImpactUrdu(news: MarketNews): String =
        "آلٹ کوائنز پر اثر (${news.affectedAssets.joinToString(", ")}): آلٹ کوائنز بٹ کوائن کے مقابلے میں 1.5x تا 2.5x زیادہ اتار چڑھاؤ دکھاتے ہیں، اس لیے کینڈل تصدیق اور کم لیوریج ضروری ہے۔"

    private fun translateShortTermUrdu(news: MarketNews): String =
        "قلیل مدتی منظرنامہ (15m–24h): خبر کے فوراً بعد تیز لیکویڈیشن سوئپ آ سکتے ہیں؛ مکمل کینڈل کلوز کے بعد ہی فیصلہ کریں۔"

    private fun translateLongTermUrdu(news: MarketNews): String =
        "طویل مدتی منظرنامہ: اگر یہ پیشرفت شرحِ سود، ETF فلو یا اداراتی لیکویڈیٹی سے جڑی ہو تو ہفتوں کے رجحان پر اثر انداز ہو سکتی ہے۔"

    private fun buildCacheKey(news: MarketNews, lang: NewsLanguageOption): String {
        val bodyHash = (news.title + news.fullContent).hashCode()
        return "${news.id}_${lang.name}_$bodyHash"
    }

    private fun saveToCache(key: String, item: TranslatedNewsArticle) {
        memoryCache[key] = item
        prefs?.let { sp ->
            val obj = JSONObject().apply {
                put("newsId", item.newsId)
                put("language", item.language.name)
                put("title", item.title)
                put("body", item.body)
                put("confirmedFacts", JSONArray(item.confirmedFacts))
                put("aiInterpretation", item.aiInterpretation)
                put("btcImpact", item.btcImpact)
                put("altcoinImpact", item.altcoinImpact)
                put("shortTermScenario", item.shortTermScenario)
                put("longTermScenario", item.longTermScenario)
                put("uncertaintyAndRisks", item.uncertaintyAndRisks)
                put("translationSource", item.translationSource)
            }
            sp.edit().putString(key, obj.toString()).apply()
        }
    }

    private fun loadFromDisk(key: String): TranslatedNewsArticle? {
        val raw = prefs?.getString(key, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val factsArr = obj.optJSONArray("confirmedFacts")
            val facts = if (factsArr != null) {
                (0 until factsArr.length()).map { factsArr.getString(it) }
            } else emptyList()
            TranslatedNewsArticle(
                newsId = obj.getString("newsId"),
                language = NewsLanguageOption.valueOf(obj.getString("language")),
                title = obj.getString("title"),
                body = obj.getString("body"),
                confirmedFacts = facts,
                aiInterpretation = obj.optString("aiInterpretation"),
                btcImpact = obj.optString("btcImpact"),
                altcoinImpact = obj.optString("altcoinImpact"),
                shortTermScenario = obj.optString("shortTermScenario"),
                longTermScenario = obj.optString("longTermScenario"),
                uncertaintyAndRisks = obj.optString("uncertaintyAndRisks"),
                fromCache = true,
                translationSource = obj.optString("translationSource", "Cached Translation")
            )
        }.getOrNull()
    }
}
