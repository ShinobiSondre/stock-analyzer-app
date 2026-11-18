package com.shinobisondre.stockanalyzer.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * "How low could it dip, and how high could it get?" for the next [days] trading sessions.
 *
 *  likelyLow / likelyHigh — it gets at least this low / this high about half the time.
 *  wideLow / wideHigh     — only about 1 time in 10 does it go past these.
 * Prices are intraday lows/highs (what a limit order would catch), not just closes.
 */
data class RangeForecast(
    val days: Int,
    val label: String,
    val price: Double,
    val likelyLow: Double,
    val likelyHigh: Double,
    val wideLow: Double,
    val wideHigh: Double,
    /** −1 the dip tends to come early, +1 late, 0 no lean. */
    val lowTiming: Int,
    /** Out of sample on this stock's recent past: how often the "likely low"/"likely high" (tuned on older data) were reached. ~0.5 = well tuned. */
    val pastLowHit: Double?,
    val pastHighHit: Double?,
    val samples: Int,
    val lines: List<PlainReason>,
    val earningsInside: Boolean,
    val support: Double?,
    val resistance: Double?,
)

object Outlook {
    val HORIZONS = listOf(5 to "Next week", 22 to "Next month")

    /** Calendar days → trading sessions (approx.). */
    fun sessions(calendarDays: Int): Int = (calendarDays * 5.0 / 7.0).roundToInt()

    private fun quantile(sorted: DoubleArray, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val pos = q * (sorted.size - 1); val lo = pos.toInt(); val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    /** Rolling σ of daily log returns over the [w] bars ending at i (inclusive). */
    private fun sigma(close: DoubleArray, i: Int, w: Int = 20): Double? {
        if (i - w < 0) return null
        var s = 0.0; var s2 = 0.0
        for (k in i - w + 1..i) { val r = ln(close[k] / close[k - 1]); s += r; s2 += r * r }
        val m = s / w
        val v = s2 / w - m * m
        return if (v > 0) sqrt(v) else null
    }

    /**
     * Normalised excursions on this stock's own history: for each past day, how far the lowest low
     * and highest high over the next [h] sessions went, in units of that day's σ·√h.
     */
    private class Excursions(val down: DoubleArray, val up: DoubleArray, val early: Double, val pastLowHit: Double?, val pastHighHit: Double?)

    private fun excursions(s: Series, h: Int, lookback: Int = 420): Excursions? {
        val end = s.last - h
        val start = maxOf(21, end - lookback)
        if (end - start < 60) return null
        val dn = ArrayList<Double>(); val up = ArrayList<Double>()
        var earlyMin = 0; var counted = 0
        for (i in start..end) {
            val c = s.close[i]; val sg = sigma(s.close, i) ?: continue
            if (c <= 0) continue
            var lo = Double.MAX_VALUE; var hi = 0.0; var loAt = 0
            for (k in 1..h) {
                if (s.low[i + k] < lo) { lo = s.low[i + k]; loAt = k }
                if (s.high[i + k] > hi) hi = s.high[i + k]
            }
            if (lo <= 0 || hi <= 0) continue
            val unit = sg * sqrt(h.toDouble())
            dn += maxOf(0.0, -ln(lo / c)) / unit
            up += maxOf(0.0, ln(hi / c)) / unit
            counted++; if (loAt <= (h + 1) / 2) earlyMin++
        }
        if (counted < 60) return null
        // Honest check: tune on the older 2/3, then count how often the newest 1/3 reached the "likely" levels.
        val cut = dn.size * 2 / 3
        val oldDn = dn.subList(0, cut).toDoubleArray().also { it.sort() }; val oldUp = up.subList(0, cut).toDoubleArray().also { it.sort() }
        val mDn = quantile(oldDn, 0.5); val mUp = quantile(oldUp, 0.5)
        val testDn = dn.subList(cut, dn.size); val testUp = up.subList(cut, up.size)
        val lowHit = if (testDn.size >= 20) testDn.count { it >= mDn }.toDouble() / testDn.size else null
        val highHit = if (testUp.size >= 20) testUp.count { it >= mUp }.toDouble() / testUp.size else null
        return Excursions(dn.toDoubleArray().also { it.sort() }, up.toDoubleArray().also { it.sort() }, earlyMin.toDouble() / counted, lowHit, highHit)
    }

    /**
     * @param news recent headlines (any age; only the last ~24 h count as "fresh").
     * @param daysToEarnings calendar days until the next earnings report, if known.
     */
    fun forecast(a: Analysis, news: List<NewsHeadline> = emptyList(), daysToEarnings: Int? = null): List<RangeForecast> {
        val s = a.series
        val price = a.lastClose
        val sg = a.indicators.dailyVol ?: sigma(s.close, s.last) ?: return emptyList()
        if (price <= 0 || sg <= 0) return emptyList()
        val fresh = news.filter { (it.ageHours ?: 99.0) <= 24 && it.sentiment != 0 }
        val tone = fresh.sumOf { it.sentiment }.coerceIn(-3, 3)
        val earnSessions = daysToEarnings?.takeIf { it >= 0 }?.let { maxOf(1, sessions(it)) }
        val support = a.levels.filter { it.type == LevelType.SUPPORT && it.price < price }.maxByOrNull { it.price }
        val resist = a.levels.filter { it.type == LevelType.RESISTANCE && it.price > price }.minByOrNull { it.price }

        return HORIZONS.mapNotNull { (h, label) ->
            val ex = excursions(s, h) ?: return@mapNotNull null
            // Width: σ√h, wider when earnings land in the window (an earnings day moves ~3× a normal day)
            // and a little wider right after fresh news.
            val earningsInside = earnSessions != null && earnSessions <= h
            var varDays = h.toDouble()
            if (earningsInside) varDays += 8.0
            if (fresh.isNotEmpty() && h <= 5) varDays += minOf(fresh.size, 3) * 0.5
            val unit = sg * sqrt(varDays)
            // Lean: the model's odds of being higher after h sessions (shrunk), plus a small news tilt.
            val p = a.forecast(if (h <= 5) 5 else 22)?.probability
            val lean = (p?.let { (it - 0.5).coerceIn(-0.2, 0.2) * 1.2 } ?: 0.0) * sg * sqrt(h.toDouble()) +
                tone * 0.25 * sg
            fun down(q: Double) = price * exp(-quantile(ex.down, q) * unit + lean)
            fun up(q: Double) = price * exp(quantile(ex.up, q) * unit + lean)
            val likelyLow = minOf(down(0.5), price); val wideLow = minOf(down(0.9), likelyLow)
            val likelyHigh = maxOf(up(0.5), price); val wideHigh = maxOf(up(0.9), likelyHigh)

            val timing = when { ex.early > 0.6 -> -1; ex.early < 0.4 -> 1; else -> 0 }

            val lines = mutableListOf<PlainReason>()
            fun m(v: Double) = Fmt.price(v)
            lines += PlainReason("About half the time it dips to ${m(likelyLow)} or lower, and only about 1 time in 10 below ${m(wideLow)}.", null)
            lines += PlainReason("About half the time it reaches ${m(likelyHigh)} or higher, and only about 1 time in 10 above ${m(wideHigh)}.", null)
            if (p != null && abs(p - 0.5) >= 0.04)
                lines += PlainReason(if (p > 0.5) "The model leans up (${(p * 100).roundToInt()}% chance of being higher), so the range is nudged up a bit."
                else "The model leans down (only ${(p * 100).roundToInt()}% chance of being higher), so the range is nudged down a bit.", p > 0.5)
            if (earningsInside)
                lines += PlainReason("It reports earnings inside this window (in about $daysToEarnings days) — that day can jump or drop a lot, so the range is wider.", null)
            if (fresh.isNotEmpty() && h <= 5)
                lines += PlainReason(when {
                    tone > 0 -> "Fresh good news in the last day — prices often keep drifting the same way for a day or two, but news is usually priced in fast."
                    tone < 0 -> "Fresh bad news in the last day — prices often keep sliding for a day or two, but news is usually priced in fast."
                    else -> "Mixed fresh news in the last day — expect bigger swings than usual."
                }, if (tone == 0) null else tone > 0)
            support?.takeIf { it.price >= wideLow }?.let {
                lines += PlainReason("There's a floor around ${m(it.price)} where buyers stepped in ${it.touches} times before — dips often stop near there.", true)
            }
            resist?.takeIf { it.price <= wideHigh }?.let {
                lines += PlainReason("There's a ceiling around ${m(it.price)} where it turned down ${it.touches} times before — rises often stall there.", false)
            }
            if (timing != 0 && h <= 5)
                lines += PlainReason(if (timing < 0) "On this stock, the week's low has more often come in the first days." else "On this stock, the week's low has more often come later in the week.", null)
            lines += PlainReason("Want to buy cheaper? A limit order near ${m(likelyLow)} fills about half the time. Want to sell higher? ${m(likelyHigh)} is a realistic target.", null)

            RangeForecast(h, label, price, likelyLow, likelyHigh, wideLow, wideHigh, timing, ex.pastLowHit, ex.pastHighHit, ex.down.size, lines,
                earningsInside, support?.price, resist?.price)
        }
    }
}

/** Shared tiny formatter (engine has no Android/Compose). */
object Fmt {
    fun price(v: Double): String = when {
        v >= 1000 -> "%,.0f".format(java.util.Locale.US, v)
        v >= 10 -> "%.2f".format(java.util.Locale.US, v)
        else -> "%.3f".format(java.util.Locale.US, v)
    }
}
