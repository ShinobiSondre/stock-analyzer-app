package com.shinobisondre.stockanalyzer.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Classic technical indicators. Every function returns an array ALIGNED with
 * its input (same length), with NaN where the indicator is not yet defined.
 * All of them are causal: value i only uses inputs 0..i, so the same code is
 * safe to use in the walk-forward trainer (no look-ahead).
 *
 * Conventions follow the `technicalindicators` npm package the web app used
 * (SMA-seeded EMA, Wilder RSI/ATR/ADX, population standard deviation).
 */
object Indicators {

    private fun firstOk(v: DoubleArray): Int {
        for (i in v.indices) if (v[i].isOk()) return i
        return -1
    }

    fun sma(v: DoubleArray, p: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        val s = firstOk(v)
        if (s < 0 || p <= 0 || v.size - s < p) return out
        var sum = 0.0
        for (i in s until v.size) {
            val x = if (v[i].isOk()) v[i] else 0.0
            sum += x
            if (i - s >= p) sum -= (if (v[i - p].isOk()) v[i - p] else 0.0)
            if (i - s >= p - 1) out[i] = sum / p
        }
        return out
    }

    fun ema(v: DoubleArray, p: Int): DoubleArray {
        val out = DoubleArray(v.size) { Double.NaN }
        val s = firstOk(v)
        if (s < 0 || p <= 0 || v.size - s < p) return out
        var seed = 0.0
        for (i in s until s + p) seed += v[i]
        var prev = seed / p
        out[s + p - 1] = prev
        val k = 2.0 / (p + 1)
        for (i in s + p until v.size) {
            prev = (v[i] - prev) * k + prev
            out[i] = prev
        }
        return out
    }

    /** Wilder RSI. */
    fun rsi(close: DoubleArray, p: Int = 14): DoubleArray {
        val n = close.size
        val out = DoubleArray(n) { Double.NaN }
        if (n <= p) return out
        var g = 0.0; var l = 0.0
        for (i in 1..p) {
            val d = close[i] - close[i - 1]
            if (d > 0) g += d else l -= d
        }
        var avgG = g / p; var avgL = l / p
        out[p] = rsiOf(avgG, avgL)
        for (i in p + 1 until n) {
            val d = close[i] - close[i - 1]
            avgG = (avgG * (p - 1) + (if (d > 0) d else 0.0)) / p
            avgL = (avgL * (p - 1) + (if (d < 0) -d else 0.0)) / p
            out[i] = rsiOf(avgG, avgL)
        }
        return out
    }

    private fun rsiOf(g: Double, l: Double): Double = when {
        l == 0.0 && g == 0.0 -> 50.0
        l == 0.0 -> 100.0
        else -> 100.0 - 100.0 / (1.0 + g / l)
    }

    class Macd(val line: DoubleArray, val signal: DoubleArray, val hist: DoubleArray)

    fun macd(close: DoubleArray, fast: Int = 12, slow: Int = 26, sig: Int = 9): Macd {
        val f = ema(close, fast)
        val s = ema(close, slow)
        val line = DoubleArray(close.size) { if (f[it].isOk() && s[it].isOk()) f[it] - s[it] else Double.NaN }
        val signal = ema(line, sig)
        val hist = DoubleArray(close.size) { if (line[it].isOk() && signal[it].isOk()) line[it] - signal[it] else Double.NaN }
        return Macd(line, signal, hist)
    }

    class Bands(val upper: DoubleArray, val middle: DoubleArray, val lower: DoubleArray)

    fun bollinger(close: DoubleArray, p: Int = 20, k: Double = 2.0): Bands {
        val n = close.size
        val mid = sma(close, p)
        val up = DoubleArray(n) { Double.NaN }
        val lo = DoubleArray(n) { Double.NaN }
        for (i in p - 1 until n) {
            val m = mid[i]
            if (!m.isOk()) continue
            var ss = 0.0
            for (j in i - p + 1..i) { val d = close[j] - m; ss += d * d }
            val sd = sqrt(ss / p)
            up[i] = m + k * sd
            lo[i] = m - k * sd
        }
        return Bands(up, mid, lo)
    }

    class Stoch(val k: DoubleArray, val d: DoubleArray)

    fun stochastic(high: DoubleArray, low: DoubleArray, close: DoubleArray, p: Int = 14, sig: Int = 3): Stoch {
        val n = close.size
        val k = DoubleArray(n) { Double.NaN }
        for (i in p - 1 until n) {
            var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
            for (j in i - p + 1..i) { hh = max(hh, high[j]); ll = min(ll, low[j]) }
            k[i] = if (hh - ll > 0) (close[i] - ll) / (hh - ll) * 100.0 else 50.0
        }
        return Stoch(k, sma(k, sig))
    }

    fun williamsR(high: DoubleArray, low: DoubleArray, close: DoubleArray, p: Int = 14): DoubleArray {
        val n = close.size
        val out = DoubleArray(n) { Double.NaN }
        for (i in p - 1 until n) {
            var hh = Double.NEGATIVE_INFINITY; var ll = Double.POSITIVE_INFINITY
            for (j in i - p + 1..i) { hh = max(hh, high[j]); ll = min(ll, low[j]) }
            out[i] = if (hh - ll > 0) (hh - close[i]) / (hh - ll) * -100.0 else -50.0
        }
        return out
    }

    fun trueRange(high: DoubleArray, low: DoubleArray, close: DoubleArray): DoubleArray {
        val n = close.size
        val tr = DoubleArray(n) { Double.NaN }
        if (n == 0) return tr
        tr[0] = high[0] - low[0]
        for (i in 1 until n) {
            tr[i] = max(high[i] - low[i], max(abs(high[i] - close[i - 1]), abs(low[i] - close[i - 1])))
        }
        return tr
    }

    /** Wilder ATR. First value at index p. */
    fun atr(high: DoubleArray, low: DoubleArray, close: DoubleArray, p: Int = 14): DoubleArray {
        val n = close.size
        val out = DoubleArray(n) { Double.NaN }
        if (n <= p) return out
        val tr = trueRange(high, low, close)
        var a = 0.0
        for (i in 1..p) a += tr[i]
        a /= p
        out[p] = a
        for (i in p + 1 until n) { a = (a * (p - 1) + tr[i]) / p; out[i] = a }
        return out
    }

    class Adx(val adx: DoubleArray, val pdi: DoubleArray, val mdi: DoubleArray)

    /** Wilder ADX with +DI / −DI. DI from index p, ADX from index 2p−1. */
    fun adx(high: DoubleArray, low: DoubleArray, close: DoubleArray, p: Int = 14): Adx {
        val n = close.size
        val adx = DoubleArray(n) { Double.NaN }
        val pdi = DoubleArray(n) { Double.NaN }
        val mdi = DoubleArray(n) { Double.NaN }
        if (n <= 2 * p) return Adx(adx, pdi, mdi)
        val tr = trueRange(high, low, close)
        val pdm = DoubleArray(n); val mdm = DoubleArray(n)
        for (i in 1 until n) {
            val up = high[i] - high[i - 1]
            val dn = low[i - 1] - low[i]
            pdm[i] = if (up > dn && up > 0) up else 0.0
            mdm[i] = if (dn > up && dn > 0) dn else 0.0
        }
        var sTr = 0.0; var sP = 0.0; var sM = 0.0
        for (i in 1..p) { sTr += tr[i]; sP += pdm[i]; sM += mdm[i] }
        val dx = DoubleArray(n) { Double.NaN }
        fun fill(i: Int) {
            val p1 = if (sTr > 0) 100.0 * sP / sTr else 0.0
            val m1 = if (sTr > 0) 100.0 * sM / sTr else 0.0
            pdi[i] = p1; mdi[i] = m1
            dx[i] = if (p1 + m1 > 0) 100.0 * abs(p1 - m1) / (p1 + m1) else 0.0
        }
        fill(p)
        for (i in p + 1 until n) {
            sTr = sTr - sTr / p + tr[i]
            sP = sP - sP / p + pdm[i]
            sM = sM - sM / p + mdm[i]
            fill(i)
        }
        var a = 0.0
        for (i in p until 2 * p) a += dx[i]
        a /= p
        adx[2 * p - 1] = a
        for (i in 2 * p until n) { a = (a * (p - 1) + dx[i]) / p; adx[i] = a }
        return Adx(adx, pdi, mdi)
    }

    fun obv(close: DoubleArray, volume: DoubleArray): DoubleArray {
        val n = close.size
        val out = DoubleArray(n)
        for (i in 1 until n) {
            out[i] = out[i - 1] + when {
                close[i] > close[i - 1] -> volume[i]
                close[i] < close[i - 1] -> -volume[i]
                else -> 0.0
            }
        }
        return out
    }

    /** Rolling sample standard deviation of daily log returns over [p] bars ending at i. */
    fun logReturnVol(close: DoubleArray, p: Int = 20): DoubleArray {
        val n = close.size
        val out = DoubleArray(n) { Double.NaN }
        if (n <= p) return out
        val r = DoubleArray(n) { if (it == 0 || close[it - 1] <= 0 || close[it] <= 0) 0.0 else kotlin.math.ln(close[it] / close[it - 1]) }
        for (i in p until n) {
            var s = 0.0
            for (j in i - p + 1..i) s += r[j]
            val mu = s / p
            var ss = 0.0
            for (j in i - p + 1..i) { val d = r[j] - mu; ss += d * d }
            out[i] = sqrt(ss / (p - 1))
        }
        return out
    }

    /** Exponentially weighted daily volatility (RiskMetrics, λ = 0.94), seeded with the 20-bar sample vol. */
    fun ewmaVol(close: DoubleArray, lambda: Double = 0.94): DoubleArray {
        val n = close.size
        val out = DoubleArray(n) { Double.NaN }
        if (n <= 21) return out
        val seed = logReturnVol(close, 20)
        var v = seed[20] * seed[20]
        out[20] = sqrt(v)
        for (i in 21 until n) {
            val r = if (close[i - 1] > 0 && close[i] > 0) kotlin.math.ln(close[i] / close[i - 1]) else 0.0
            v = lambda * v + (1 - lambda) * r * r
            out[i] = sqrt(v)
        }
        return out
    }

    /** Rolling max of [v] over [p] bars ending at i (inclusive). */
    fun rollingMax(v: DoubleArray, p: Int): DoubleArray {
        val n = v.size
        val out = DoubleArray(n) { Double.NaN }
        // Monotonic deque for O(n)
        val dq = IntArray(n); var head = 0; var tail = 0
        for (i in 0 until n) {
            while (tail > head && v[dq[tail - 1]] <= v[i]) tail--
            dq[tail++] = i
            if (dq[head] <= i - p) head++
            if (i >= p - 1) out[i] = v[dq[head]]
        }
        return out
    }
}
