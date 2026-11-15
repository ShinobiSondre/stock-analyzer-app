package com.shinobisondre.stockanalyzer.engine

import kotlin.math.max

/**
 * Tyler Hill's four-stack (from the web app): sell when RSI > 70 with bearish
 * divergence, price at the upper band, volume fading on a rally and crowd
 * euphoria all line up — and the mirror image for buys. Run on the daily and
 * the weekly chart.
 *
 * The web app's 21-year test found the stack "leans the right way but
 * weakly" (buy side 58.1% @10d vs 55.1% base; sell side 53.4%, i.e. stocks
 * still rose more often than not). The probability model gets the daily
 * stack as an input and decides for itself how much it is worth.
 */
data class StackCheck(val name: String, val hit: Boolean, val detail: String)

data class StackRead(
    val rsi: Double?, val pctB: Double?, val monthReturnPct: Double,
    val sellChecks: List<StackCheck>, val buyChecks: List<StackCheck>,
) {
    val sellHits get() = sellChecks.count { it.hit }
    val buyHits get() = buyChecks.count { it.hit }
}

enum class StackVerdict(val label: String, val tone: Tone, val message: String) {
    FULL_SELL("FULL SELL", Tone.STRONG_SELL, "All four sell signals stacked — Tyler would take meaningful profit."),
    TRIM("TRIM", Tone.SELL, "Three of four sell signals stacked — Tyler would trim some."),
    FULL_BUY("FULL BUY", Tone.STRONG_BUY, "All four mirror signals stacked — Tyler's high-conviction buy zone."),
    ACCUMULATE("ACCUMULATE", Tone.BUY, "Three of four mirror signals stacked — start accumulating."),
    NEUTRAL("NEUTRAL", Tone.NEUTRAL, "No 3- or 4-stack on either side."),
}

data class TylerResult(val daily: StackRead?, val weekly: StackRead?, val verdict: StackVerdict) {
    val sellHits get() = max(daily?.sellHits ?: 0, weekly?.sellHits ?: 0)
    val buyHits get() = max(daily?.buyHits ?: 0, weekly?.buyHits ?: 0)
}

object Tyler {
    private const val LOOKBACK = 20
    private const val VOL_LOOKBACK = 10

    /** Stack read at bar [i] of [s]. [newsScore] is optional (−3..3); 0 when unknown. */
    fun read(s: Series, i: Int = s.last, newsScore: Double = 0.0): StackRead? {
        if (i < 30) return null
        val rsiNow = s.rsi.at(i) ?: return null
        val up = s.bb.upper.at(i); val lo = s.bb.lower.at(i)
        val c = s.close[i]
        val pctB = if (up != null && lo != null && up > lo) (c - lo) / (up - lo) else 0.5

        val overbought = rsiNow > 70; val oversold = rsiNow < 30
        val bearDiv = divergence(s, i, bearish = true)
        val bullDiv = divergence(s, i, bearish = false)
        val priceSlope = slope(s.close, i, VOL_LOOKBACK)
        val volSlope = slope(s.volume, i, VOL_LOOKBACK)
        val weakVolRally = priceSlope > 0.001 && volSlope < -0.01
        val risingVolSelloff = priceSlope < -0.001 && volSlope > 0.01
        val back = max(0, i - 22)
        val monthRet = if (i > 22 && s.close[back] > 0) (c - s.close[back]) / s.close[back] else 0.0
        val euphoric = (newsScore >= 1 && monthRet >= 0.15) || monthRet >= 0.25
        val capitulation = (newsScore <= -1 && monthRet <= -0.15) || monthRet <= -0.25

        val sell = listOf(
            StackCheck("RSI overbought + bearish divergence", overbought && bearDiv, "RSI ${fmt(rsiNow, 1)}${if (bearDiv) " with bearish divergence" else ""}"),
            StackCheck("Price at upper Bollinger band", pctB >= 0.95, "%B ${fmt(pctB * 100, 0)}%"),
            StackCheck("Volume fading on a rally", weakVolRally, "price ${pct(priceSlope * 100, 2)}/bar, volume ${pct(volSlope * 100, 1)}/bar"),
            StackCheck("Crowd euphoria (parabolic month)", euphoric, "1-month return ${pct(monthRet * 100)}"),
        )
        val buy = listOf(
            StackCheck("RSI oversold + bullish divergence", oversold && bullDiv, "RSI ${fmt(rsiNow, 1)}${if (bullDiv) " with bullish divergence" else ""}"),
            StackCheck("Price at lower Bollinger band", pctB <= 0.05, "%B ${fmt(pctB * 100, 0)}%"),
            StackCheck("Volume rising into a selloff", risingVolSelloff, "price ${pct(priceSlope * 100, 2)}/bar, volume ${pct(volSlope * 100, 1)}/bar"),
            StackCheck("Capitulation (crashing month)", capitulation, "1-month return ${pct(monthRet * 100)}"),
        )
        return StackRead(rsiNow, pctB, monthRet * 100, sell, buy)
    }

    fun full(candles: List<Candle>, daily: Series, newsScore: Double = 0.0): TylerResult {
        val d = read(daily, daily.last, newsScore)
        val weeklyCandles = toWeekly(candles)
        val w = if (weeklyCandles.size >= 31) Series(weeklyCandles).let { read(it, it.last, newsScore) } else null
        val sell = max(d?.sellHits ?: 0, w?.sellHits ?: 0)
        val buy = max(d?.buyHits ?: 0, w?.buyHits ?: 0)
        val verdict = when {
            sell >= 4 -> StackVerdict.FULL_SELL
            sell == 3 -> StackVerdict.TRIM
            buy >= 4 -> StackVerdict.FULL_BUY
            buy == 3 -> StackVerdict.ACCUMULATE
            else -> StackVerdict.NEUTRAL
        }
        return TylerResult(d, w, verdict)
    }

    private fun divergence(s: Series, i: Int, bearish: Boolean): Boolean {
        val start = i - LOOKBACK + 1
        if (start < 15) return false
        val mid = start + LOOKBACK / 2
        var a = start; var b = mid
        for (j in start until mid) if (if (bearish) s.close[j] > s.close[a] else s.close[j] < s.close[a]) a = j
        for (j in mid..i) if (if (bearish) s.close[j] > s.close[b] else s.close[j] < s.close[b]) b = j
        val ra = s.rsi.at(a) ?: return false
        val rb = s.rsi.at(b) ?: return false
        return if (bearish) s.close[b] > s.close[a] * 1.005 && rb < ra - 2
        else s.close[b] < s.close[a] * 0.995 && rb > ra + 2
    }

    /** Linear-regression slope over the last [n] values, normalised by their mean (≈ %/bar). */
    private fun slope(v: DoubleArray, i: Int, n: Int): Double {
        val start = i - n + 1
        if (start < 0) return 0.0
        var mean = 0.0
        for (j in start..i) mean += v[j]
        mean /= n
        if (mean == 0.0) return 0.0
        val xm = (n - 1) / 2.0
        var num = 0.0; var den = 0.0
        for (k in 0 until n) { val dx = k - xm; num += dx * (v[start + k] - mean); den += dx * dx }
        return if (den == 0.0) 0.0 else num / den / mean
    }

    /** Daily → weekly bars, weeks starting Monday. */
    fun toWeekly(daily: List<Candle>): List<Candle> {
        val out = ArrayList<Candle>()
        var key: String? = null
        var o = 0.0; var h = 0.0; var l = 0.0; var c = 0.0; var v = 0.0; var a = 0.0
        for (d in daily) {
            val dow = TradingDays.dayOfWeek(d.date)
            val monday = mondayOf(d.date, dow)
            if (monday != key) {
                if (key != null) out.add(Candle(key, o, h, l, c, v, a))
                key = monday; o = d.open; h = d.high; l = d.low; c = d.close; v = d.volume; a = d.adjClose
            } else {
                h = max(h, d.high); l = kotlin.math.min(l, d.low); c = d.close; v += d.volume; a = d.adjClose
            }
        }
        if (key != null) out.add(Candle(key, o, h, l, c, v, a))
        return out
    }

    private fun mondayOf(iso: String, dow: Int): String {
        if (dow == 1) return iso
        var (y, m, d) = iso.split("-").map { it.toInt() }
        val dim = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        repeat(dow - 1) {
            d--
            if (d < 1) {
                m--; if (m < 1) { m = 12; y-- }
                val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
                d = if (m == 2 && leap) 29 else dim[m - 1]
            }
        }
        return "%04d-%02d-%02d".format(y, m, d)
    }
}
