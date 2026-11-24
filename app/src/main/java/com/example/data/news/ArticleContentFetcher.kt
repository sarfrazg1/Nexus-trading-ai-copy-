package com.example.data.news

import com.example.data.model.ArticleContentStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ExtractedArticleResult(
    val url: String,
    val extractedText: String,
    val contentStatus: ArticleContentStatus,
    val charCount: Int,
    val fetchNote: String
)

/**
 * Safely fetches publicly accessible HTML article text where legally and technically permitted,
 * strips scripts/navigation/boilerplate, and honestly labels COMPLETE vs PARTIAL / SUMMARY ONLY.
 * Never fabricates missing paragraphs or bypasses paywalls.
 */
class ArticleContentFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private val cache = ConcurrentHashMap<String, ExtractedArticleResult>()

    suspend fun fetchArticleBody(url: String, fallbackSummary: String): ExtractedArticleResult =
        withContext(Dispatchers.IO) {
            val cleanUrl = url.trim()
            if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
                !cleanUrl.startsWith("https://", ignoreCase = true)
            ) {
                return@withContext ExtractedArticleResult(
                    url = cleanUrl,
                    extractedText = fallbackSummary,
                    contentStatus = ArticleContentStatus.PARTIAL_SUMMARY_ONLY,
                    charCount = fallbackSummary.length,
                    fetchNote = "No HTTP source URL available — summary only."
                )
            }

            cache[cleanUrl]?.let { return@withContext it }

            val request = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "Mozilla/5.0 (compatible; NexusTradingNewsReader/1.0)")
                .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9")
                .build()

            val result = try {
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    if (code == 401 || code == 402 || code == 403 || code == 451) {
                        ExtractedArticleResult(
                            url = cleanUrl,
                            extractedText = fallbackSummary,
                            contentStatus = ArticleContentStatus.PARTIAL_SUMMARY_ONLY,
                            charCount = fallbackSummary.length,
                            fetchNote = "Source restricted full text (HTTP $code) — showing verified headline/summary only."
                        )
                    } else if (!response.isSuccessful) {
                        ExtractedArticleResult(
                            url = cleanUrl,
                            extractedText = fallbackSummary,
                            contentStatus = ArticleContentStatus.PARTIAL_SUMMARY_ONLY,
                            charCount = fallbackSummary.length,
                            fetchNote = "Source returned HTTP $code — showing verified summary only."
                        )
                    } else {
                        val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.US)
                        if (contentType.isNotBlank() && !contentType.contains("text/") && !contentType.contains("html") && !contentType.contains("xml")) {
                            ExtractedArticleResult(
                                url = cleanUrl,
                                extractedText = fallbackSummary,
                                contentStatus = ArticleContentStatus.PARTIAL_SUMMARY_ONLY,
                                charCount = fallbackSummary.length,
                                fetchNote = "Non-HTML source ($contentType) — showing summary only."
                            )
                        } else {
                            val rawHtml = response.body?.string()?.take(MAX_RAW_HTML_CHARS).orEmpty()
                            extractFromHtml(cleanUrl, rawHtml, fallbackSummary)
                        }
                    }
                }
            } catch (e: Exception) {
                ExtractedArticleResult(
                    url = cleanUrl,
                    extractedText = fallbackSummary,
                    contentStatus = ArticleContentStatus.PARTIAL_SUMMARY_ONLY,
                    charCount = fallbackSummary.length,
                    fetchNote = "Could not retrieve full article (${e.message ?: "network error"}) — showing verified summary only."
                )
            }

            cache[cleanUrl] = result
            result
        }

    internal fun extractFromHtml(
        url: String,
        rawHtml: String,
        fallbackSummary: String
    ): ExtractedArticleResult {
        if (rawHtml.isBlank()) {
            return ExtractedArticleResult(
                url = url,
                extractedText = fallbackSummary,
                contentStatus = ArticleContentStatus.PARTIAL_SUMMARY_ONLY,
                charCount = fallbackSummary.length,
                fetchNote = "Source returned an empty page — showing summary only."
            )
        }

        val lowerRaw = rawHtml.lowercase(Locale.US)
        val paywallOrJsBlock = lowerRaw.contains("enable javascript and cookies") ||
            lowerRaw.contains("cf-browser-verification") ||
            lowerRaw.contains("subscribe to continue reading") ||
            lowerRaw.contains("this article is for subscribers")

        // Prefer <article>...</article> if present
        val articleMatch = Regex("(?is)<article\\b[^>]*>(.*?)</article>").find(rawHtml)
        val scopeHtml = articleMatch?.groupValues?.getOrNull(1) ?: rawHtml

        val cleaned = scopeHtml
            .replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|header|aside|form)[^>]*>.*?</\\1>"), " ")

        val paragraphs = Regex("(?is)<p\\b[^>]*>(.*?)</p>")
            .findAll(cleaned)
            .map { m -> stripTagsAndDecode(m.groupValues[1]) }
            .filter { p ->
                p.length >= 45 &&
                    !p.contains("cookie policy", ignoreCase = true) &&
                    !p.contains("all rights reserved", ignoreCase = true) &&
                    !p.contains("privacy policy", ignoreCase = true) &&
                    !p.contains("sign up for", ignoreCase = true)
            }
            .distinct()
            .toList()

        val joined = paragraphs.joinToString("\n\n").take(MAX_EXTRACTED_CHARS).trim()

        return if (joined.length >= MIN_COMPLETE_ARTICLE_CHARS && paragraphs.size >= 2 && !paywallOrJsBlock) {
            ExtractedArticleResult(
                url = url,
                extractedText = joined,
                contentStatus = ArticleContentStatus.COMPLETE,
                charCount = joined.length,
                fetchNote = "Complete readable article text extracted from original publisher."
            )
        } else {
            val partialBody = if (joined.length > fallbackSummary.length && joined.length >= 120) {
                "$fallbackSummary\n\nExtracted Publisher Excerpt:\n$joined"
            } else {
                fallbackSummary
            }
            val reason = if (paywallOrJsBlock) {
                "Publisher requires subscription or JavaScript verification — partial/summary only."
            } else {
                "Only partial snippet or metadata reachable (${joined.length} chars) — labeled Partial / Summary Only."
            }
            ExtractedArticleResult(
                url = url,
                extractedText = partialBody,
                contentStatus = ArticleContentStatus.PARTIAL_SUMMARY_ONLY,
                charCount = partialBody.length,
                fetchNote = reason
            )
        }
    }

    private fun stripTagsAndDecode(htmlFragment: String): String {
        return htmlFragment
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun clearCache() {
        cache.clear()
    }

    companion object {
        private const val MAX_RAW_HTML_CHARS = 280_000
        private const val MAX_EXTRACTED_CHARS = 8_000
        private const val MIN_COMPLETE_ARTICLE_CHARS = 360
    }
}
