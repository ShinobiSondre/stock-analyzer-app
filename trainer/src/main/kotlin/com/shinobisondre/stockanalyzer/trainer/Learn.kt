package com.shinobisondre.stockanalyzer.trainer

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Growable primitive arrays (the dataset has ~1M rows × 32 features). */
class FloatList(cap: Int = 1 shl 16) {
    var a = FloatArray(cap); var size = 0
    fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
}
class IntList(cap: Int = 1 shl 16) {
    var a = IntArray(cap); var size = 0
    fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    operator fun get(i: Int) = a[i]
}

/**
 * L2-regularised logistic regression fitted with Newton–Raphson (IRLS) on
 * standardised features. Small, exact, and deterministic.
 */
class Logistic(val k: Int) {
    var mean = DoubleArray(k); var std = DoubleArray(k) { 1.0 }
    var beta = DoubleArray(k + 1) // [intercept, coefs...] on standardised scale

    fun fit(x: FloatArray, y: BooleanArray, rows: IntArray, lambda: Double = 1.0, maxIter: Int = 30): Logistic {
        // Standardise on the training rows only.
        val n = rows.size.toDouble()
        mean = DoubleArray(k); std = DoubleArray(k)
        for (r in rows) for (j in 0 until k) mean[j] += x[r * k + j]
        for (j in 0 until k) mean[j] /= n
        for (r in rows) for (j in 0 until k) { val d = x[r * k + j] - mean[j]; std[j] += d * d }
        for (j in 0 until k) { std[j] = sqrt(std[j] / n); if (std[j] < 1e-9) std[j] = 0.0 }
        val pos = rows.count { y[it] }.toDouble()
        beta = DoubleArray(k + 1)
        beta[0] = ln(max(pos, 1.0) / max(n - pos, 1.0))
        val d = k + 1
        val xs = DoubleArray(d)
        for (iter in 0 until maxIter) {
            val h = DoubleArray(d * d); val g = DoubleArray(d)
            for (r in rows) {
                xs[0] = 1.0
                for (j in 0 until k) xs[j + 1] = if (std[j] > 0) (x[r * k + j] - mean[j]) / std[j] else 0.0
                var z = 0.0
                for (j in 0 until d) z += beta[j] * xs[j]
                val p = 1.0 / (1.0 + exp(-z))
                val w = max(p * (1 - p), 1e-9)
                val e = (if (y[r]) 1.0 else 0.0) - p
                for (a in 0 until d) {
                    val xa = xs[a]
                    if (xa == 0.0) continue
                    g[a] += e * xa
                    val wa = w * xa
                    val row = a * d
                    for (b in a until d) h[row + b] += wa * xs[b]
                }
            }
            for (a in 0 until d) for (b in 0 until a) h[a * d + b] = h[b * d + a]
            for (j in 1 until d) { g[j] -= lambda * beta[j]; h[j * d + j] += lambda }
            val step = solve(h, g, d)
            var maxStep = 0.0
            for (j in 0 until d) { beta[j] += step[j]; maxStep = max(maxStep, abs(step[j])) }
            if (maxStep < 1e-7) break
        }
        return this
    }

    fun prob(x: FloatArray, r: Int): Double {
        var z = beta[0]
        for (j in 0 until k) if (std[j] > 0) z += beta[j + 1] * (x[r * k + j] - mean[j]) / std[j]
        return 1.0 / (1.0 + exp(-z))
    }

    /** Cholesky solve of the (symmetric positive-definite) Newton system. */
    private fun solve(a0: DoubleArray, b: DoubleArray, n: Int): DoubleArray {
        val a = a0.copyOf()
        for (j in 0 until n) {
            var s = a[j * n + j]
            for (k2 in 0 until j) s -= a[j * n + k2] * a[j * n + k2]
            val dj = sqrt(max(s, 1e-12))
            a[j * n + j] = dj
            for (i in j + 1 until n) {
                var t = a[i * n + j]
                for (k2 in 0 until j) t -= a[i * n + k2] * a[j * n + k2]
                a[i * n + j] = t / dj
            }
        }
        val z = DoubleArray(n)
        for (i in 0 until n) { var s = b[i]; for (k2 in 0 until i) s -= a[i * n + k2] * z[k2]; z[i] = s / a[i * n + i] }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) { var s = z[i]; for (k2 in i + 1 until n) s -= a[k2 * n + i] * x[k2]; x[i] = s / a[i * n + i] }
        return x
    }
}

object Metrics {
    /** Area under the ROC curve (Mann–Whitney, ties averaged). */
    fun auc(score: DoubleArray, y: BooleanArray): Double {
        val n = score.size
        if (n == 0) return Double.NaN
        val idx = (0 until n).sortedBy { score[it] }
        var rankSumPos = 0.0
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && score[idx[j + 1]] == score[idx[i]]) j++
            val avgRank = (i + j) / 2.0 + 1
            for (t in i..j) if (y[idx[t]]) rankSumPos += avgRank
            i = j + 1
        }
        val pos = y.count { it }.toDouble(); val neg = n - pos
        if (pos == 0.0 || neg == 0.0) return Double.NaN
        return (rankSumPos - pos * (pos + 1) / 2) / (pos * neg)
    }

    fun brier(p: DoubleArray, y: BooleanArray): Double = p.indices.sumOf { val d = p[it] - (if (y[it]) 1.0 else 0.0); d * d } / max(1, p.size)

    fun logloss(p: DoubleArray, y: BooleanArray): Double = p.indices.sumOf {
        val q = min(max(p[it], 1e-6), 1 - 1e-6); -(if (y[it]) ln(q) else ln(1 - q))
    } / max(1, p.size)

    fun quantile(v: DoubleArray, q: Double): Double {
        if (v.isEmpty()) return Double.NaN
        val s = v.sortedArray()
        val pos = q * (s.size - 1)
        val lo = pos.toInt(); val hi = min(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }
}
