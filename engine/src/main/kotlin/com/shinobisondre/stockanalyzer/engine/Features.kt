package com.shinobisondre.stockanalyzer.engine

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The feature vector the probability model sees. Built from the SAME Series
 * code in the app and in the trainer, so what was validated is what runs on
 * the phone.
 *
 * Every feature is causal (uses bars ≤ i only) and clipped, so a single
 * crazy print cannot dominate a prediction.
 */
object Features {

    /** Name, plus the plain-English label used to explain what drove a call. */
    data class Def(val key: String, val label: String)

    val DEFS = listOf(
        Def("rsi_c", "RSI level"),
        Def("rsi_c2", "RSI extreme"),
        Def("rsi_lt30", "RSI below 30"),
        Def("d200", "Distance from 200-day"),
        Def("d200_sq", "Stretch from 200-day"),
        Def("above200", "Above 200-day"),
        Def("d50", "Distance from 50-day"),
        Def("ret5", "Last week's move"),
        Def("ret21", "Last month's move"),
        Def("mom121", "12-month momentum"),
        Def("rel_mom", "Momentum vs S&P 500"),
        Def("rs_leader", "Relative-strength leader"),
        Def("rs_laggard", "Relative-strength laggard"),
        Def("dd252", "Drawdown from 1-year high"),
        Def("vol20", "Volatility"),
        Def("pctb", "Bollinger position"),
        Def("macd_n", "MACD histogram"),
        Def("di_diff", "Trend direction (DI)"),
        Def("adx_n", "Trend strength (ADX)"),
        Def("vol_ratio", "Volume vs average"),
        Def("obv_bull", "On-balance volume rising"),
        Def("stoch_c", "Stochastic"),
        Def("vix_l", "VIX level"),
        Def("vix_gt25", "VIX above 25"),
        Def("vix_spike", "VIX spike"),
        Def("spy_dd", "S&P 500 drawdown"),
        Def("spy_dc", "S&P 500 death cross"),
        Def("spy_ret63", "S&P 500 3-month trend"),
        Def("leader_pb", "Leader pullback setup"),
        Def("fear_setup", "Fear-spike setup"),
        Def("tyler_buy", "Tyler buy stack (3+)"),
        Def("tyler_sell", "Tyler sell stack (3+)"),
        // v2: short-term reversal relative to the market, lottery effect, fear momentum
        Def("rel_ret5", "Last week vs S&P 500"),
        Def("rel_ret21", "Last month vs S&P 500"),
        Def("spy_ret5", "S&P 500 last week"),
        Def("max_ret21", "Biggest up-day this month"),
        Def("vix_chg5", "VIX change this week"),
        Def("ret1", "Yesterday's move"),
        Def("vol_signed", "Volume surge × direction"),
    )
    val NAMES = DEFS.map { it.key }
    val SIZE = DEFS.size

    /** Bars of history a feature vector needs (12-1 momentum + 1-year high). */
    const val MIN_BARS = 253

    /**
     * Builds the vector at bar [i], or null when there is not enough history.
     * [tyler] may be passed in when already computed (saves work in the app).
     */
    fun build(s: Series, i: Int, m: MarketPoint?, tyler: StackRead? = null): DoubleArray? {
        if (i < MIN_BARS - 1) return null
        val c = s.close[i]
        if (c <= 0) return null
        val rsi = s.rsi.at(i) ?: return null
        val sma200 = s.sma200.at(i) ?: return null
        val sma50 = s.sma50.at(i) ?: return null
        val c5 = s.close[i - 5]; val c21 = s.close[i - 21]; val c252 = s.close[i - 252]
        if (c5 <= 0 || c21 <= 0 || c252 <= 0) return null

        val rsiC = (rsi - 50) / 10
        val d200 = clamp(ln(c / sma200), -0.8, 0.8)
        val d50 = clamp(ln(c / sma50), -0.5, 0.5)
        val ret5 = clamp(ln(c / c5), -0.4, 0.4)
        val ret21 = clamp(ln(c / c21), -0.6, 0.6)
        val mom = clamp(ln(c21 / c252), -1.5, 1.5)
        val momArith = c21 / c252 - 1

        val spyMom = m?.spyRet12_1
        val relMom = if (spyMom != null && spyMom > -1) clamp(mom - ln(1 + spyMom), -1.5, 1.5) else 0.0
        // Same cut-offs as the web app's walk-forward (arithmetic 12-1 returns).
        val rel = if (spyMom != null) momArith - spyMom else null
        val leader = if (rel != null) rel > 0.10 else momArith > 0.20
        val laggard = if (rel != null) rel < -0.15 else momArith < -0.05

        val hi = s.max252.at(i) ?: c
        val dd = clamp(ln(c / hi), -1.5, 0.0)
        val dv = s.vol20.at(i)
        val vol = if (dv != null && dv > 0) clamp(ln(dv * sqrt(252.0) / 0.30), -2.0, 2.0) else 0.0

        val up = s.bb.upper.at(i); val lo = s.bb.lower.at(i)
        val pctB = if (up != null && lo != null && up > lo) clamp((c - lo) / (up - lo) - 0.5, -1.5, 1.5) else 0.0
        val macdN = s.macd.hist.at(i)?.let { clamp(it / c * 100, -3.0, 3.0) } ?: 0.0
        val pdi = s.adx.pdi.at(i); val mdi = s.adx.mdi.at(i)
        val diDiff = if (pdi != null && mdi != null) (pdi - mdi) / 100 else 0.0
        val adxN = s.adx.adx.at(i)?.let { clamp((it - 25) / 10, -3.0, 3.0) } ?: 0.0
        val va = s.volSma20.at(i)
        val volRatio = if (va != null && va > 0 && s.volume[i] > 0) clamp(ln(s.volume[i] / va), -2.0, 2.0) else 0.0
        val obvBull = s.obvSma20.at(i)?.let { if (s.obv[i] > it) 1.0 else 0.0 } ?: 0.0
        val stochC = s.stoch.k.at(i)?.let { (it - 50) / 25 } ?: 0.0

        val vix = m?.vix
        val vixL = if (vix != null && vix > 0) clamp(ln(vix / 20), -1.5, 1.5) else 0.0
        val vixHigh = vix != null && vix > 25
        val vixSpike = if (vix != null && m.vixMa20 != null && m.vixMa20 > 0) clamp(ln(vix / m.vixMa20), -1.0, 1.0) else 0.0
        val spyDd = (m?.spyDrawdownPct ?: 0.0) / 10
        val spyDc = m?.indexDeathCross ?: false
        val spyRet63 = clamp(m?.spyRet63 ?: 0.0, -0.5, 0.5)

        val above = c > sma200
        val leaderPb = above && leader && rsi < 40
        val fear = vixHigh && above && rsi < 50 && !spyDc

        val spy5 = m?.spyRet5; val spy21 = m?.spyRet21
        val relRet5 = if (spy5 != null && spy5 > -1) clamp(ret5 - ln(1 + spy5), -0.4, 0.4) else 0.0
        val relRet21 = if (spy21 != null && spy21 > -1) clamp(ret21 - ln(1 + spy21), -0.6, 0.6) else 0.0
        val spyRet5 = if (spy5 != null && spy5 > -1) clamp(ln(1 + spy5), -0.25, 0.25) else 0.0
        var maxRet = 0.0
        for (j in i - 20..i) { val p0 = s.close[j - 1]; if (p0 > 0) maxRet = maxOf(maxRet, s.close[j] / p0 - 1) }
        val maxRet21 = clamp(maxRet, 0.0, 0.5)
        val vix5 = m?.vix5
        val vixChg = if (vix != null && vix5 != null && vix5 > 0) clamp(ln(vix / vix5), -1.0, 1.0) else 0.0
        val p1 = s.close[i - 1]
        val ret1 = if (p1 > 0) clamp(ln(c / p1), -0.3, 0.3) else 0.0
        val volSigned = volRatio * (if (ret1 > 0) 1.0 else if (ret1 < 0) -1.0 else 0.0)

        val t = tyler ?: Tyler.read(s, i)
        val tBuy = (t?.buyHits ?: 0) >= 3
        val tSell = (t?.sellHits ?: 0) >= 3

        return doubleArrayOf(
            rsiC, rsiC * rsiC, b(rsi < 30),
            d200, d200 * d200, b(above), d50,
            ret5, ret21, mom, relMom, b(leader), b(laggard),
            dd, vol, pctB, macdN, diDiff, adxN, volRatio, obvBull, stochC,
            vixL, b(vixHigh), vixSpike, spyDd, b(spyDc), spyRet63,
            b(leaderPb), b(fear), b(tBuy), b(tSell),
            relRet5, relRet21, spyRet5, maxRet21, vixChg, ret1, volSigned,
        )
    }

    private fun b(x: Boolean) = if (x) 1.0 else 0.0
}
