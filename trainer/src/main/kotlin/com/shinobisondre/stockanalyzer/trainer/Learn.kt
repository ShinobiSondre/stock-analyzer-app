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

/** Quantile binning of every feature (codes are what the trees split on). */
class Binner(x: FloatArray, val n: Int, val k: Int, edgeRows: IntArray, bins: Int = 32, seed: Long = 7) {
    val edges: Array<DoubleArray>
    val codes: ByteArray

    init {
        val rnd = java.util.Random(seed)
        val sample = if (edgeRows.size <= 200_000) edgeRows else IntArray(200_000) { edgeRows[rnd.nextInt(edgeRows.size)] }
        edges = Array(k) { f ->
            val v = DoubleArray(sample.size) { x[sample[it] * k + f].toDouble() }
            v.sort()
            val e = sortedSetOf<Double>()
            for (j in 1 until bins) {
                val q = v[((j.toDouble() / bins) * (v.size - 1)).toInt()]
                if (q < v[v.size - 1]) e.add(q)
            }
            e.toDoubleArray()
        }
        codes = ByteArray(n * k)
        for (r in 0 until n) for (f in 0 until k) codes[r * k + f] = code(f, x[r * k + f].toDouble()).toByte()
    }

    fun code(f: Int, v: Double): Int {
        val e = edges[f]
        var lo = 0; var hi = e.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (v <= e[mid]) hi = mid else lo = mid + 1 }
        return lo
    }
}

data class GbdtParams(
    val trees: Int = 200, val depth: Int = 3, val lr: Double = 0.05, val lambda: Double = 50.0,
    val minLeaf: Int = 1500, val subsample: Double = 0.5, val colsample: Double = 0.8, val seed: Long = 1,
)

class GbdtModel(val base: Double, val trees: List<com.shinobisondre.stockanalyzer.engine.Tree>, val gain: DoubleArray) {
    fun raw(x: DoubleArray): Double { var z = base; for (t in trees) z += t.predict(x); return z }
}

/** Histogram gradient boosting for log-loss, small and deterministic. */
object Gbdt {
    fun fit(b: Binner, y: BooleanArray, rows: IntArray, p: GbdtParams = GbdtParams()): GbdtModel {
        val k = b.k
        val n = rows.size
        val bins = b.edges.maxOf { it.size } + 1
        val pos = rows.count { y[it] }.toDouble()
        val base = ln(max(pos, 1.0) / max(n - pos, 1.0))
        val f = DoubleArray(n) { base }
        val g = DoubleArray(n); val h = DoubleArray(n)
        val rnd = java.util.Random(p.seed)
        val sample = IntArray(n)
        val trees = ArrayList<com.shinobisondre.stockanalyzer.engine.Tree>(p.trees)
        val gain = DoubleArray(k)
        val maxNodes = (1 shl (p.depth + 1)) - 1
        val hg = DoubleArray(k * bins); val hh = DoubleArray(k * bins); val hc = IntArray(k * bins)
        val tmp = IntArray(n)
        repeat(p.trees) {
            for (i in 0 until n) {
                val pr = 1.0 / (1.0 + exp(-f[i]))
                g[i] = pr - (if (y[rows[i]]) 1.0 else 0.0)
                h[i] = max(pr * (1 - pr), 1e-6)
            }
            var m = 0
            for (i in 0 until n) if (rnd.nextDouble() < p.subsample) sample[m++] = i
            val feats = (0 until k).filter { rnd.nextDouble() < p.colsample }.ifEmpty { listOf(rnd.nextInt(k)) }.toIntArray()

            val feat = IntArray(maxNodes) { -1 }; val thrBin = IntArray(maxNodes); val left = IntArray(maxNodes) { -1 }; val right = IntArray(maxNodes) { -1 }
            val value = DoubleArray(maxNodes)
            val start = IntArray(maxNodes); val end = IntArray(maxNodes); val depth = IntArray(maxNodes)
            var nodes = 1
            start[0] = 0; end[0] = m
            run { var G = 0.0; var H = 0.0; for (t in 0 until m) { G += g[sample[t]]; H += h[sample[t]] }; value[0] = -G / (H + p.lambda) * p.lr }
            var cur = 0
            while (cur < nodes) {
                val s0 = start[cur]; val e0 = end[cur]; val cnt = e0 - s0
                if (depth[cur] >= p.depth || cnt < 2 * p.minLeaf) { cur++; continue }
                java.util.Arrays.fill(hg, 0.0); java.util.Arrays.fill(hh, 0.0); java.util.Arrays.fill(hc, 0)
                var G = 0.0; var H = 0.0
                for (t in s0 until e0) {
                    val i = sample[t]; val gi = g[i]; val hi = h[i]; G += gi; H += hi
                    val base0 = rows[i] * k
                    for (ff in feats) { val idx = ff * bins + b.codes[base0 + ff]; hg[idx] += gi; hh[idx] += hi; hc[idx]++ }
                }
                val parent = G * G / (H + p.lambda)
                var bestGain = 1e-9; var bestF = -1; var bestB = -1
                for (ff in feats) {
                    var gl = 0.0; var hl = 0.0; var cl = 0
                    val nb = b.edges[ff].size
                    for (bb in 0 until nb) {
                        val idx = ff * bins + bb
                        gl += hg[idx]; hl += hh[idx]; cl += hc[idx]
                        val cr = cnt - cl
                        if (cl < p.minLeaf) continue
                        if (cr < p.minLeaf) break
                        val gr = G - gl; val hr = H - hl
                        val gn = gl * gl / (hl + p.lambda) + gr * gr / (hr + p.lambda) - parent
                        if (gn > bestGain) { bestGain = gn; bestF = ff; bestB = bb }
                    }
                }
                if (bestF < 0 || nodes + 2 > maxNodes) { cur++; continue }
                // Partition [s0, e0) so rows going left come first.
                var lN = 0; var rN = 0
                for (t in s0 until e0) { val i = sample[t]; if (b.codes[rows[i] * k + bestF] <= bestB) sample[s0 + lN++] = i else tmp[rN++] = i }
                System.arraycopy(tmp, 0, sample, s0 + lN, rN)
                val l = nodes++; val r = nodes++
                feat[cur] = bestF; thrBin[cur] = bestB; left[cur] = l; right[cur] = r
                gain[bestF] += bestGain
                for ((child, a, z) in listOf(Triple(l, s0, s0 + lN), Triple(r, s0 + lN, e0))) {
                    start[child] = a; end[child] = z; depth[child] = depth[cur] + 1
                    var gg = 0.0; var hh2 = 0.0
                    for (t in a until z) { gg += g[sample[t]]; hh2 += h[sample[t]] }
                    value[child] = -gg / (hh2 + p.lambda) * p.lr
                }
                cur++
            }
            val tree = com.shinobisondre.stockanalyzer.engine.Tree(
                feat.copyOf(nodes), DoubleArray(nodes) { if (feat[it] >= 0) b.edges[feat[it]][thrBin[it]] else 0.0 },
                left.copyOf(nodes), right.copyOf(nodes), value.copyOf(nodes),
            )
            trees += tree
            // Update scores for every training row (not just the subsample).
            for (i in 0 until n) {
                val base0 = rows[i] * k
                var nd = 0
                while (feat[nd] >= 0) nd = if (b.codes[base0 + feat[nd]] <= thrBin[nd]) left[nd] else right[nd]
                f[i] += value[nd]
            }
        }
        return GbdtModel(base, trees, gain)
    }
}

/** Monotone (PAV) calibration over equal-count bins of a raw score. */
object Calib {
    fun fit(raw: DoubleArray, y: BooleanArray, bins: Int = 20): Pair<DoubleArray, DoubleArray> {
        val idx = raw.indices.sortedBy { raw[it] }
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val ws = ArrayList<Double>()
        for (bIdx in 0 until bins) {
            val a = bIdx * idx.size / bins; val z = (bIdx + 1) * idx.size / bins
            if (z <= a) continue
            var sx = 0.0; var sy = 0.0
            for (t in a until z) { sx += raw[idx[t]]; if (y[idx[t]]) sy += 1.0 }
            xs += sx / (z - a); ys += sy / (z - a); ws += (z - a).toDouble()
        }
        // Pool adjacent violators so the map is non-decreasing.
        val vy = ys.toMutableList(); val vw = ws.toMutableList(); val members = xs.indices.map { mutableListOf(it) }.toMutableList()
        var i = 0
        while (i < vy.size - 1) {
            if (vy[i] > vy[i + 1]) {
                val w = vw[i] + vw[i + 1]
                vy[i] = (vy[i] * vw[i] + vy[i + 1] * vw[i + 1]) / w; vw[i] = w
                members[i].addAll(members[i + 1]); vy.removeAt(i + 1); vw.removeAt(i + 1); members.removeAt(i + 1)
                if (i > 0) i--
            } else i++
        }
        val outY = DoubleArray(xs.size)
        members.forEachIndexed { b, ms -> ms.forEach { outY[it] = vy[b] } }
        return xs.toDoubleArray() to outY
    }

    fun apply(cx: DoubleArray, cy: DoubleArray, v: Double): Double {
        if (cx.isEmpty()) return 0.5
        if (v <= cx[0]) return cy[0]
        if (v >= cx[cx.size - 1]) return cy[cy.size - 1]
        var lo = 0; var hi = cx.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (cx[mid] <= v) lo = mid else hi = mid }
        val t = (v - cx[lo]) / max(cx[hi] - cx[lo], 1e-12)
        return cy[lo] + t * (cy[hi] - cy[lo])
    }
}
