package com.shinobisondre.stockanalyzer.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Indicator values + their human reads at one bar. */
data class IndicatorSnapshot(
    val date: String,
    val lastClose: Double,
    val rsi: Double?, val rsiRead: Read,
    val macd: Double?, val macdSignal: Double?, val macdHist: Double?, val macdRead: Read,
    val bbUpper: Double?, val bbMiddle: Double?, val bbLower: Double?, val percentB: Double?, val bbRead: Read,
    val sma20: Double?, val sma50: Double?, val sma200: Double?, val ema12: Double?, val ema26: Double?,
    val maRead: Read, val goldenCross: Boolean, val deathCross: Boolean,
    val stochK: Double?, val stochD: Double?, val stochRead: Read,
    val adx: Double?, val pdi: Double?, val mdi: Double?, val adxRead: Read,
    val atr: Double?, val atrPct: Double?,
    val obvBullish: Boolean?,
    val williamsR: Double?, val willRRead: Read,
    val volume: Double, val volumeAvg20: Double?, val volumeRatio: Double?, val volumeRead: Read,
    val ret12_1: Double?,
    val dailyVol: Double?,
) {
    val aboveSma200: Boolean? get() = sma200?.let { lastClose > it }
}

data class Pattern(val name: String, val type: Tone, val score: Double, val description: String, val date: String, val barsAgo: Int)

enum class LevelType { SUPPORT, RESISTANCE }
data class Level(val price: Double, val type: LevelType, val touches: Int, val distancePct: Double)

object Technical {

    fun snapshot(s: Series, i: Int = s.last): IndicatorSnapshot {
        val c = s.close[i]
        val rsi = s.rsi.at(i)
        val macd = s.macd.line.at(i); val macdSig = s.macd.signal.at(i); val hist = s.macd.hist.at(i)
        val pm = s.macd.line.at(i - 1); val ps = s.macd.signal.at(i - 1)
        val up = s.bb.upper.at(i); val mid = s.bb.middle.at(i); val lo = s.bb.lower.at(i)
        val pctB = if (up != null && lo != null && up > lo) (c - lo) / (up - lo) else null
        val sma20 = s.sma20.at(i); val sma50 = s.sma50.at(i); val sma200 = s.sma200.at(i)
        val k = s.stoch.k.at(i); val d = s.stoch.d.at(i)
        val adx = s.adx.adx.at(i); val pdi = s.adx.pdi.at(i); val mdi = s.adx.mdi.at(i)
        val atr = s.atr.at(i)
        val obvB = s.obvSma20.at(i)?.let { s.obv[i] > it }
        val wr = s.willR.at(i)
        val volAvg = s.volSma20.at(i)
        val volRatio = if (volAvg != null && volAvg > 0) s.volume[i] / volAvg else null
        val prevClose = if (i > 0) s.close[i - 1] else c
        return IndicatorSnapshot(
            date = s.dates[i], lastClose = c,
            rsi = rsi, rsiRead = rsiRead(rsi),
            macd = macd, macdSignal = macdSig, macdHist = hist, macdRead = macdRead(macd, macdSig, pm, ps),
            bbUpper = up, bbMiddle = mid, bbLower = lo, percentB = pctB, bbRead = bbRead(pctB),
            sma20 = sma20, sma50 = sma50, sma200 = sma200, ema12 = s.ema12.at(i), ema26 = s.ema26.at(i),
            maRead = maRead(c, sma20, sma50, sma200),
            goldenCross = crossed(s.sma50, s.sma200, i, up = true),
            deathCross = crossed(s.sma50, s.sma200, i, up = false),
            stochK = k, stochD = d, stochRead = stochRead(k),
            adx = adx, pdi = pdi, mdi = mdi, adxRead = adxRead(adx, pdi, mdi),
            atr = atr, atrPct = atr?.let { it / c * 100 },
            obvBullish = obvB,
            williamsR = wr, willRRead = willRRead(wr),
            volume = s.volume[i], volumeAvg20 = volAvg, volumeRatio = volRatio,
            volumeRead = volumeRead(volRatio, c, prevClose),
            ret12_1 = s.ret12_1(i),
            dailyVol = s.ewmaVol.at(i) ?: s.vol20.at(i),
        )
    }

    // ── Reads. Oversold/overbought are deliberately asymmetric: the web app's
    //    21-year walk-forward found "overbought" does not predict declines.
    fun rsiRead(rsi: Double?): Read = when {
        rsi == null -> Read("N/A", 0.0, Tone.NEUTRAL)
        rsi < 25 -> Read("Deeply oversold", 2.0)
        rsi < 35 -> Read("Oversold", 1.5)
        rsi < 45 -> Read("Pullback zone", 0.8)
        rsi < 55 -> Read("Neutral", 0.0)
        rsi < 65 -> Read("Firm", -0.2)
        rsi < 75 -> Read("Extended", -0.4)
        else -> Read("Overbought (momentum may persist)", -0.6)
    }

    fun macdRead(m: Double?, s: Double?, pm: Double?, ps: Double?): Read {
        if (m == null || s == null) return Read("N/A", 0.0, Tone.NEUTRAL)
        val crossUp = pm != null && ps != null && m > s && pm <= ps
        val crossDn = pm != null && ps != null && m < s && pm >= ps
        return when {
            crossUp -> Read("Bullish crossover", 2.0)
            crossDn -> Read("Bearish crossover", -2.0)
            m > s && m > 0 -> Read("Bullish", 1.5)
            m > s -> Read("Weakly bullish", 0.5)
            m < s && m < 0 -> Read("Bearish", -1.5)
            m < s -> Read("Weakly bearish", -0.5)
            else -> Read("Neutral", 0.0)
        }
    }

    fun bbRead(pctB: Double?): Read = when {
        pctB == null -> Read("N/A", 0.0, Tone.NEUTRAL)
        pctB < 0 -> Read("Below lower band", 2.0)
        pctB < 0.2 -> Read("Near lower band", 1.0)
        pctB < 0.4 -> Read("Lower half", 0.3, Tone.NEUTRAL)
        pctB < 0.6 -> Read("Mid-band", 0.0)
        pctB < 0.8 -> Read("Upper half", -0.3, Tone.NEUTRAL)
        pctB < 1.0 -> Read("Near upper band", -1.0)
        else -> Read("Above upper band", -2.0)
    }

    fun maRead(price: Double, s20: Double?, s50: Double?, s200: Double?): Read {
        var score = 0.0
        if (s20 != null) score += if (price > s20) 0.5 else -0.5
        if (s20 != null && s50 != null) score += if (s20 > s50) 0.5 else -0.5
        if (s200 != null) {
            score += if (price > s200) 0.5 else -0.5
            if (s50 != null) score += if (s50 > s200) 0.5 else -0.5
        }
        val label = when {
            score > 1.5 -> "Strong uptrend"
            score > 0.5 -> "Uptrend"
            score < -1.5 -> "Strong downtrend"
            score < -0.5 -> "Downtrend"
            else -> "Mixed"
        }
        return Read(label, score)
    }

    fun stochRead(k: Double?): Read = when {
        k == null -> Read("N/A", 0.0, Tone.NEUTRAL)
        k < 20 -> Read("Oversold", 1.5)
        k < 30 -> Read("Near oversold", 0.8)
        k > 80 -> Read("Overbought (momentum may persist)", -0.5)
        k > 70 -> Read("Near overbought", -0.3, Tone.NEUTRAL)
        else -> Read("Neutral", 0.0)
    }

    fun adxRead(adx: Double?, pdi: Double?, mdi: Double?): Read {
        if (adx == null || pdi == null || mdi == null) return Read("N/A", 0.0, Tone.NEUTRAL)
        val strength = when {
            adx > 50 -> "Extremely strong"; adx > 35 -> "Strong"; adx > 25 -> "Moderate"; adx > 15 -> "Weak"; else -> "No trend"
        }
        val bull = pdi > mdi
        return Read("$strength ${if (bull) "up" else "down"}trend", if (bull) 0.5 else -0.5)
    }

    fun willRRead(r: Double?): Read = when {
        r == null -> Read("N/A", 0.0, Tone.NEUTRAL)
        r < -80 -> Read("Oversold", 1.5)
        r < -50 -> Read("Below midline", 0.5)
        r < -20 -> Read("Above midline", -0.3, Tone.NEUTRAL)
        else -> Read("Overbought (momentum may persist)", -0.5)
    }

    fun volumeRead(ratio: Double?, cur: Double, prev: Double): Read {
        if (ratio == null) return Read("N/A", 0.0, Tone.NEUTRAL)
        val rising = cur > prev
        return when {
            ratio > 2 && rising -> Read("High-volume breakout", 1.0)
            ratio > 1.5 && cur < prev -> Read("High-volume selloff", -1.0)
            ratio > 1.2 -> Read("Above-average volume", 0.0)
            ratio < 0.5 -> Read("Very low volume", 0.0)
            else -> Read("Normal volume", 0.0)
        }
    }

    /** SMA50 crossed SMA200 in the direction given within the last 5 bars ending at i. */
    fun crossed(fast: DoubleArray, slow: DoubleArray, i: Int, up: Boolean): Boolean {
        for (k in 0 until 5) {
            val a = i - k; val b = a - 1
            if (b < 0) break
            val fc = fast.at(a) ?: continue; val fp = fast.at(b) ?: continue
            val sc = slow.at(a) ?: continue; val sp = slow.at(b) ?: continue
            if (up && fc > sc && fp <= sp) return true
            if (!up && fc < sc && fp >= sp) return true
        }
        return false
    }

    // ── Candlestick patterns (last 3 bars). Shown as context; a single
    //    candle shape is fragile, so the model does not use it.
    fun patterns(s: Series, end: Int = s.last): List<Pattern> {
        val out = mutableListOf<Pattern>()
        if (end < 5) return out
        for (i in end downTo max(2, end - 2)) {
            val o = s.open[i]; val h = s.high[i]; val l = s.low[i]; val c = s.close[i]
            val po = s.open[i - 1]; val pc = s.close[i - 1]
            val body = abs(c - o); val range = h - l
            if (range <= 0) continue
            val bull = c > o; val bear = c < o
            val upperShadow = h - max(o, c); val lowerShadow = min(o, c) - l
            val ago = end - i
            val date = s.dates[i]
            // A reversal needs something to reverse: compare with 5 bars earlier.
            val priorDown = i >= 6 && s.close[i - 1] < s.close[i - 6]
            val priorUp = i >= 6 && s.close[i - 1] > s.close[i - 6]
            fun add(name: String, t: Tone, sc: Double, desc: String) = out.add(Pattern(name, t, sc, desc, date, ago))

            if (body / range < 0.1) add("Doji", Tone.NEUTRAL, 0.0, "Indecision candle — a reversal is possible")
            if (lowerShadow >= 2 * body && upperShadow <= 0.3 * max(body, range * 0.05) && priorDown && body / range >= 0.05)
                add("Hammer", Tone.BUY, 1.5, "Buyers rejected lower prices after a decline")
            if (upperShadow >= 2 * body && lowerShadow <= 0.3 * max(body, range * 0.05) && priorUp && body / range >= 0.05)
                add("Shooting star", Tone.SELL, -1.5, "Sellers rejected higher prices after a rise")
            if (pc < po && bull && o <= pc && c >= po && body > abs(pc - po))
                add("Bullish engulfing", Tone.BUY, 2.0, "Buyers overwhelmed the previous red candle")
            if (pc > po && bear && o >= pc && c <= po && body > abs(pc - po))
                add("Bearish engulfing", Tone.SELL, -2.0, "Sellers overwhelmed the previous green candle")
            if (body / range > 0.9) {
                if (bull) add("Bullish marubozu", Tone.BUY, 1.5, "Strong buying all session")
                else if (bear) add("Bearish marubozu", Tone.SELL, -1.5, "Strong selling all session")
            }
            val o1 = s.open[i - 2]; val c1 = s.close[i - 2]
            val b2 = abs(pc - po); val r2 = s.high[i - 1] - s.low[i - 1]
            if (c1 < o1 && r2 > 0 && b2 / r2 < 0.3 && bull && c > (o1 + c1) / 2)
                add("Morning star", Tone.STRONG_BUY, 2.5, "Three-candle bullish reversal")
            if (c1 > o1 && r2 > 0 && b2 / r2 < 0.3 && bear && c < (o1 + c1) / 2)
                add("Evening star", Tone.STRONG_SELL, -2.5, "Three-candle bearish reversal")
            if (c1 > o1 && pc > po && bull && pc > c1 && c > pc && po > o1 && o > po)
                add("Three white soldiers", Tone.BUY, 2.0, "Three advancing green candles")
            if (c1 < o1 && pc < po && bear && pc < c1 && c < pc && po < o1 && o < po)
                add("Three black crows", Tone.SELL, -2.0, "Three declining red candles")
            if (pc < po && bull && o > pc && c < po && body < (po - pc) * 0.6)
                add("Bullish harami", Tone.BUY, 1.0, "Small green inside bar after a red candle")
            if (pc > po && bear && o < pc && c > po && body < (pc - po) * 0.6)
                add("Bearish harami", Tone.SELL, -1.0, "Small red inside bar after a green candle")
        }
        return out.distinctBy { it.name }
    }

    /**
     * Pivot-based support/resistance over the last 120 bars.
     * Fix vs the web app: a level is classified by where it sits relative to
     * the CURRENT price (below = support, above = resistance), not by whether
     * it came from a pivot high or low — an old high the price has broken
     * through is support now, not resistance.
     */
    fun levels(s: Series, end: Int = s.last, lookback: Int = 120, window: Int = 5): List<Level> {
        val start = max(0, end - lookback + 1)
        val price = s.close[end]
        val raw = mutableListOf<Double>()
        for (i in start + window..end - window) {
            var isHigh = true; var isLow = true
            for (j in i - window..i + window) {
                if (s.high[j] > s.high[i]) isHigh = false
                if (s.low[j] < s.low[i]) isLow = false
            }
            if (isHigh) raw.add(s.high[i])
            if (isLow) raw.add(s.low[i])
        }
        // Also the most recent swing extremes (last `window` bars can't be pivots yet).
        val clusters = mutableListOf<DoubleArray>() // [sumPrice, touches]
        for (p in raw) {
            val c = clusters.firstOrNull { abs(it[0] / it[1] - p) / (it[0] / it[1]) < 0.015 }
            if (c != null) { c[0] += p; c[1] += 1.0 } else clusters.add(doubleArrayOf(p, 1.0))
        }
        return clusters
            .map { val lvl = it[0] / it[1]; Level(lvl, if (lvl < price) LevelType.SUPPORT else LevelType.RESISTANCE, it[1].toInt(), (lvl - price) / price * 100) }
            .sortedWith(compareByDescending<Level> { it.touches }.thenBy { abs(it.distancePct) })
            .take(8)
            .sortedByDescending { it.price }
    }
}
