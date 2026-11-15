package com.shinobisondre.stockanalyzer.engine

import kotlin.math.max

enum class FearTier { NONE, PULLBACK, ELEVATED, HIGH }
enum class Regime(val label: String) { BULL("Bull"), CAUTION("Caution"), BEAR("Bear"), HIGH_VOL("High volatility"), UNKNOWN("Unknown") }

/** Market-wide state on one date, from SPY and the VIX. */
data class MarketPoint(
    val date: String,
    val vix: Double?,
    val vixMa20: Double?,
    val spyClose: Double,
    val spySma50: Double?,
    val spySma200: Double?,
    /** % below the trailing 252-session high (positive number). */
    val spyDrawdownPct: Double?,
    val spyRet12_1: Double?,
    val spyRet63: Double?,
    val spyDrift60Pct: Double?,
) {
    val indexDeathCross: Boolean get() = spySma50 != null && spySma200 != null && spySma50 < spySma200
    val spyAbove200: Boolean? get() = spySma200?.let { spyClose > it }

    val fearTier: FearTier get() = when {
        vix != null && vix > 25 -> FearTier.HIGH
        vix != null && vix > 20 -> FearTier.ELEVATED
        spyDrawdownPct != null && spyDrawdownPct > 5 -> FearTier.PULLBACK
        else -> FearTier.NONE
    }

    val regime: Regime get() {
        val above = spyAbove200 ?: return Regime.UNKNOWN
        val drift = spyDrift60Pct ?: 0.0
        return when {
            vix != null && vix > 28 -> Regime.HIGH_VOL
            !above && drift < -5 -> Regime.BEAR
            !above || drift < 0 -> Regime.CAUTION
            else -> Regime.BULL
        }
    }

    fun regimeNote(): String {
        val d = spyDrift60Pct?.let { pct(it) } ?: "—"
        return when (regime) {
            Regime.HIGH_VOL -> "VIX ${fmt(vix, 1)} is above 28 — a volatility regime. Size positions down until the index trend is clear."
            Regime.BEAR -> "S&P 500 is below its 200-day and $d over 60 sessions. Bullish calls are 'better than average', not 'likely to rise'."
            Regime.CAUTION -> if (spyAbove200 == true) "S&P 500 is above its 200-day but $d over 60 sessions." else "S&P 500 is below its 200-day ($d over 60 sessions)."
            Regime.BULL -> "S&P 500 is above its 200-day, $d over 60 sessions."
            Regime.UNKNOWN -> "Not enough index history to classify the market."
        }
    }
}

/**
 * SPY + VIX histories aligned for date lookups. A stock's bar is matched to
 * the latest market date on or before it, so non-US exchanges with different
 * holidays still line up.
 */
class MarketSeries(spy: List<Candle>, vix: List<Candle>) {
    private val spySeries = Series(spy)
    private val vixDates = Array(vix.size) { vix[it].date }
    private val vixClose = DoubleArray(vix.size) { vix[it].close }
    private val vixMa = Indicators.sma(vixClose, 20)
    private val spySma50 = spySeries.sma50
    private val spySma200 = spySeries.sma200
    private val spyMax = Indicators.rollingMax(spySeries.close, 252)

    val size get() = spySeries.n
    val lastDate: String? get() = if (spySeries.n > 0) spySeries.dates[spySeries.n - 1] else null

    private fun floorIndex(dates: Array<String>, date: String): Int {
        var lo = 0; var hi = dates.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (dates[mid] <= date) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    fun at(date: String): MarketPoint? {
        val i = floorIndex(spySeries.dates, date)
        if (i < 0) return null
        return pointAt(i)
    }

    fun latest(): MarketPoint? = if (spySeries.n == 0) null else pointAt(spySeries.n - 1)

    private fun pointAt(i: Int): MarketPoint {
        val d = spySeries.dates[i]
        val vi = floorIndex(vixDates, d)
        // A stale VIX print (more than ~5 sessions old) is worse than none.
        val vixOk = vi >= 0 && vixDates[vi] >= lag(d, 7)
        val c = spySeries.close
        val hi = spyMax.at(i) ?: run { var m = 0.0; for (j in 0..i) m = max(m, c[j]); m }
        return MarketPoint(
            date = d,
            vix = if (vixOk) vixClose[vi] else null,
            vixMa20 = if (vixOk) vixMa.at(vi) else null,
            spyClose = c[i],
            spySma50 = spySma50.at(i),
            spySma200 = spySma200.at(i),
            spyDrawdownPct = if (hi > 0) (hi - c[i]) / hi * 100 else null,
            spyRet12_1 = spySeries.ret12_1(i),
            spyRet63 = spySeries.ret(i, 63),
            spyDrift60Pct = spySeries.ret(i, 60)?.let { it * 100 },
        )
    }

    /** ISO date [days] calendar days before [d]. */
    private fun lag(d: String, days: Int): String {
        var (y, m, dd) = d.split("-").map { it.toInt() }
        val dim = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        repeat(days) {
            dd--
            if (dd < 1) {
                m--
                if (m < 1) { m = 12; y-- }
                val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
                dd = if (m == 2 && leap) 29 else dim[m - 1]
            }
        }
        return "%04d-%02d-%02d".format(y, m, dd)
    }
}
