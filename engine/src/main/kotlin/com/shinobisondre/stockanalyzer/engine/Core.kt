package com.shinobisondre.stockanalyzer.engine

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** One daily bar. [date] is the exchange-local trading date (yyyy-MM-dd). */
data class Candle(
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    /** Split- and dividend-adjusted close. Only used for forward-return labels. */
    val adjClose: Double = close,
)

/** Direction/strength of a read, used to colour things in the UI. */
enum class Tone { STRONG_BUY, BUY, NEUTRAL, SELL, STRONG_SELL;
    val sign: Int get() = when (this) { STRONG_BUY, BUY -> 1; NEUTRAL -> 0; SELL, STRONG_SELL -> -1 }
}

/** A single indicator read: human label + signed score + tone. */
data class Read(val label: String, val score: Double, val tone: Tone = toneOf(score))

fun toneOf(score: Double): Tone = when {
    score >= 1.5 -> Tone.STRONG_BUY
    score > 0.25 -> Tone.BUY
    score <= -1.5 -> Tone.STRONG_SELL
    score < -0.25 -> Tone.SELL
    else -> Tone.NEUTRAL
}

internal fun Double.isOk(): Boolean = !this.isNaN() && !this.isInfinite()
internal fun Double.orNull(): Double? = if (isOk()) this else null
internal fun clamp(v: Double, lo: Double, hi: Double): Double = if (v < lo) lo else if (v > hi) hi else v

fun round(v: Double?, digits: Int = 2): Double? {
    if (v == null || !v.isOk()) return null
    val f = 10.0.pow(digits)
    return (v * f).roundToLong() / f
}

internal fun DoubleArray.lastOk(): Double? = if (isEmpty()) null else this[size - 1].orNull()
internal fun DoubleArray.at(i: Int): Double? = if (i in indices) this[i].orNull() else null

/** Trading-day arithmetic that ignores exchange holidays (guide only). */
object TradingDays {
    private val DAYS_IN_MONTH = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    private fun leap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

    /** Day of week for an ISO date, 1 = Monday … 7 = Sunday. */
    fun dayOfWeek(iso: String): Int {
        val (y0, m0, d) = iso.split("-").map { it.toInt() }
        // Zeller-style via Sakamoto's algorithm
        val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
        val y = if (m0 < 3) y0 - 1 else y0
        val dow = (y + y / 4 - y / 100 + y / 400 + t[m0 - 1] + d) % 7 // 0 = Sunday
        return if (dow == 0) 7 else dow
    }

    fun addDays(iso: String, n: Int): String {
        var (y, m, d) = iso.split("-").map { it.toInt() }
        repeat(n) {
            d++
            val dim = if (m == 2 && leap(y)) 29 else DAYS_IN_MONTH[m - 1]
            if (d > dim) { d = 1; m++; if (m > 12) { m = 1; y++ } }
        }
        return "%04d-%02d-%02d".format(y, m, d)
    }

    fun addTradingDays(iso: String, n: Int): String {
        var cur = iso
        var added = 0
        while (added < n) {
            cur = addDays(cur, 1)
            if (dayOfWeek(cur) <= 5) added++
        }
        return cur
    }
}

internal fun pct(v: Double, digits: Int = 1): String {
    val r = round(v, digits) ?: return "—"
    return (if (r > 0) "+" else "") + r.toString() + "%"
}

internal fun fmt(v: Double?, digits: Int = 2): String = round(v, digits)?.toString() ?: "—"

internal fun approxEq(a: Double, b: Double, tol: Double = 1e-9) = abs(a - b) <= tol
