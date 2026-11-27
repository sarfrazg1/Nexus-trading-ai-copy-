package com.example.util

import com.example.data.model.Candle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Shared timezone and completed-candle utilities.
 * All Scanner Entry Timer and News Monitor displayed timestamps normalize to Pakistan Standard Time (Asia/Karachi).
 */
object TimeZoneUtils {

    const val ASIA_KARACHI_ID = "Asia/Karachi"

    fun karachiTimeZone(): TimeZone = TimeZone.getTimeZone(ASIA_KARACHI_ID)

    fun formatKarachiTimestamp(millis: Long): String {
        if (millis <= 0L) return "Unavailable"
        val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a 'PKT'", Locale.US)
        fmt.timeZone = karachiTimeZone()
        return fmt.format(Date(millis))
    }

    fun formatKarachiShort(millis: Long): String {
        if (millis <= 0L) return "Unavailable"
        val fmt = SimpleDateFormat("MMM dd, HH:mm 'PKT'", Locale.US)
        fmt.timeZone = karachiTimeZone()
        return fmt.format(Date(millis))
    }

    fun formatKarachiWithSeconds(millis: Long): String {
        if (millis <= 0L) return "Unavailable"
        val fmt = SimpleDateFormat("MMM dd, HH:mm:ss 'PKT'", Locale.US)
        fmt.timeZone = karachiTimeZone()
        return fmt.format(Date(millis))
    }

    fun formatKarachiFullWithTz(millis: Long): String {
        if (millis <= 0L) return "Unavailable"
        val fmt = SimpleDateFormat("HH:mm:ss dd-MMM-yyyy 'PKT'", Locale.US)
        fmt.timeZone = karachiTimeZone()
        return fmt.format(Date(millis))
    }

    fun formatKarachiTimeOnly(millis: Long): String {
        if (millis <= 0L) return "Unavailable"
        val fmt = SimpleDateFormat("HH:mm:ss 'PKT'", Locale.US)
        fmt.timeZone = karachiTimeZone()
        return fmt.format(Date(millis))
    }

    fun formatKarachiTime12h(millis: Long): String {
        if (millis <= 0L) return "Unavailable"
        val fmt = SimpleDateFormat("h:mm:ss a 'PKT'", Locale.US)
        fmt.timeZone = karachiTimeZone()
        return fmt.format(Date(millis))
    }

    fun formatKarachiDateOnly(millis: Long): String {
        if (millis <= 0L) return "Unavailable"
        val fmt = SimpleDateFormat("MMM dd, yyyy", Locale.US)
        fmt.timeZone = karachiTimeZone()
        return fmt.format(Date(millis))
    }

    fun formatKarachiMonthKey(millis: Long): String {
        if (millis <= 0L) return "Unavailable"
        val fmt = SimpleDateFormat("yyyy-MM", Locale.US)
        fmt.timeZone = karachiTimeZone()
        return fmt.format(Date(millis))
    }

    fun formatKarachiCompact(millis: Long): String {
        if (millis <= 0L) return "Unavailable"
        val fmt = SimpleDateFormat("dd/MM HH:mm 'PKT'", Locale.US)
        fmt.timeZone = karachiTimeZone()
        return fmt.format(Date(millis))
    }

    /**
     * Filters out corrupted candles and any currently forming/unclosed candle whose closeTime
     * is strictly in the future relative to [referenceTimeMs].
     *
     * Note: Historical/synthetic candles with closeTime <= 0L are treated as completed bars.
     */
    fun filterCompletedCandles(
        candles: List<Candle>,
        referenceTimeMs: Long = System.currentTimeMillis()
    ): List<Candle> {
        if (candles.isEmpty()) return emptyList()
        return candles.filter { c ->
            val validValues = c.open.isFinite() && c.high.isFinite() &&
                c.low.isFinite() && c.close.isFinite() && c.volume.isFinite() &&
                c.open > 0.0 && c.high > 0.0 && c.low > 0.0 && c.close > 0.0 &&
                c.volume >= 0.0 && c.high >= c.low
            val isClosed = c.closeTime <= 0L || c.closeTime <= referenceTimeMs
            validValues && isClosed
        }
    }
}
