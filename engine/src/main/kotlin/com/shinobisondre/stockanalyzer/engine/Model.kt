package com.shinobisondre.stockanalyzer.engine

import com.shinobisondre.stockanalyzer.engine.MiniJson.doubles
import com.shinobisondre.stockanalyzer.engine.MiniJson.list
import com.shinobisondre.stockanalyzer.engine.MiniJson.num
import com.shinobisondre.stockanalyzer.engine.MiniJson.obj
import com.shinobisondre.stockanalyzer.engine.MiniJson.str
import kotlin.math.exp
import kotlin.math.sqrt

/** The call shown to the user. AVOID = expected to lag the market, but not measured to fall. */
enum class Call(val text: String, val tone: Tone, val bullish: Boolean = false, val bearish: Boolean = false) {
    STRONG_BUY("STRONG BUY", Tone.STRONG_BUY, bullish = true),
    BUY("BUY", Tone.BUY, bullish = true),
    WEAK_BUY("WEAK BUY", Tone.BUY, bullish = true),
    HOLD("HOLD", Tone.NEUTRAL),
    AVOID("AVOID", Tone.SELL, bearish = true),
    WEAK_SELL("WEAK SELL", Tone.SELL, bearish = true),
    SELL("SELL", Tone.STRONG_SELL, bearish = true);

    companion object { fun of(text: String?): Call? = entries.firstOrNull { it.text == text || it.name == text } }
}

/** Out-of-sample outcome of every past call with this label (from the trainer). */
data class LabelStat(val n: Int, val upRate: Double, val meanRetPct: Double, val excessPct: Double)

data class Thresholds(val strong: Double, val buy: Double, val weak: Double, val weakSell: Double, val sell: Double)

/** One regression tree, flattened. A node is a leaf when feat[n] < 0. Goes left when x[feat] <= thr. */
class Tree(val feat: IntArray, val thr: DoubleArray, val left: IntArray, val right: IntArray, val value: DoubleArray) {
    fun predict(x: DoubleArray): Double {
        var n = 0
        while (feat[n] >= 0) n = if (x[feat[n]] <= thr[n]) left[n] else right[n]
        return value[n]
    }

    /** Saabas attribution: each split credits its feature with the change in node value. */
    fun attribute(x: DoubleArray, out: DoubleArray) {
        var n = 0
        while (feat[n] >= 0) {
            val c = if (x[feat[n]] <= thr[n]) left[n] else right[n]
            out[feat[n]] += value[c] - value[n]
            n = c
        }
    }

    fun toMap(): Map<String, Any?> = linkedMapOf("f" to feat.toList(), "t" to thr.toList(), "l" to left.toList(), "r" to right.toList(), "v" to value.toList())

    companion object {
        fun fromMap(m: Map<String, Any?>) = Tree(
            m["f"].doubles().map { it.toInt() }.toIntArray(), m["t"].doubles(),
            m["l"].doubles().map { it.toInt() }.toIntArray(), m["r"].doubles().map { it.toInt() }.toIntArray(), m["v"].doubles(),
        )
    }
}

/**
 * One calibrated model for P(close is higher [days] sessions from now).
 *
 * kind = "gbdt"     gradient-boosted trees on the feature vector
 *        "logistic" linear log-odds on standardised features
 *        "rules"    the classic weighted-indicator score
 *
 * Whatever the kind, the raw score goes through [calX]→[calY], a monotone
 * map fitted on walk-forward out-of-sample predictions, so the number the
 * app shows is a measured frequency, not a heuristic "confidence".
 */
class HorizonModel(
    val days: Int,
    val kind: String,
    val featureNames: List<String>,
    val mean: DoubleArray,
    val std: DoubleArray,
    val coef: DoubleArray,
    val intercept: Double,
    val trees: List<Tree>,
    val baseScore: Double,
    val calX: DoubleArray,
    val calY: DoubleArray,
    val baseRate: Double,
    val thresholds: Thresholds,
    val sellReal: Boolean,
    val weakSellReal: Boolean,
    val intervalK: Double,
    val stats: Map<String, LabelStat>,
    val metrics: Map<String, Double>,
    /** Reliability table: [mean predicted p, realised up-rate, n]. */
    val calibration: List<DoubleArray>,
    /** "up" = P(price higher after [days]); "beat" = P(return beats the S&P 500 over [days]). */
    val target: String = "up",
) {
    val needsFeatures: Boolean get() = kind != "rules"

    /** Raw score before calibration: log-odds for gbdt/logistic, the rules score for "rules". */
    fun raw(x: DoubleArray?, rulesScore: Double): Double? = when (kind) {
        "rules" -> rulesScore
        "gbdt" -> x?.let { v -> var z = baseScore; for (t in trees) z += t.predict(v); z }
        else -> x?.let { v -> var z = intercept; for (j in coef.indices) if (std[j] > 0) z += coef[j] * (v[j] - mean[j]) / std[j]; z }
    }

    fun calibrate(raw: Double): Double {
        if (calX.isEmpty()) return 1.0 / (1.0 + exp(-raw))
        if (raw <= calX[0]) return calY[0]
        if (raw >= calX[calX.size - 1]) return calY[calY.size - 1]
        var lo = 0; var hi = calX.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (calX[mid] <= raw) lo = mid else hi = mid }
        val t = (raw - calX[lo]) / (calX[hi] - calX[lo]).coerceAtLeast(1e-12)
        return calY[lo] + t * (calY[hi] - calY[lo])
    }

    fun probability(x: DoubleArray?, rulesScore: Double): Double? = raw(x, rulesScore)?.let { calibrate(it) }

    /** Signed contribution of each feature to the raw score (relative to an average bar). */
    fun contributions(x: DoubleArray): DoubleArray = when (kind) {
        "gbdt" -> DoubleArray(featureNames.size).also { out -> trees.forEach { it.attribute(x, out) } }
        "logistic" -> DoubleArray(coef.size) { j -> if (std[j] > 0) coef[j] * (x[j] - mean[j]) / std[j] else 0.0 }
        else -> DoubleArray(featureNames.size)
    }

    fun call(p: Double): Call = when {
        p >= thresholds.strong -> Call.STRONG_BUY
        p >= thresholds.buy -> Call.BUY
        p >= thresholds.weak -> Call.WEAK_BUY
        p <= thresholds.sell -> if (sellReal) Call.SELL else Call.AVOID
        p <= thresholds.weakSell -> if (weakSellReal) Call.WEAK_SELL else Call.AVOID
        else -> Call.HOLD
    }

    /** 80% price band for this horizon, calibrated out-of-sample by the trainer. */
    fun band(price: Double, dailyVol: Double): Pair<Double, Double> {
        val w = 1.2816 * intervalK * dailyVol * sqrt(days.toDouble())
        return price * exp(-w) to price * exp(w)
    }

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "days" to days, "target" to target, "kind" to kind, "featureNames" to featureNames, "mean" to mean.toList(), "std" to std.toList(),
        "coef" to coef.toList(), "intercept" to intercept, "baseScore" to baseScore, "trees" to trees.map { it.toMap() },
        "calX" to calX.toList(), "calY" to calY.toList(), "baseRate" to baseRate,
        "thresholds" to linkedMapOf("strong" to thresholds.strong, "buy" to thresholds.buy, "weak" to thresholds.weak,
            "weakSell" to thresholds.weakSell, "sell" to thresholds.sell),
        "sellReal" to sellReal, "weakSellReal" to weakSellReal, "intervalK" to intervalK,
        "stats" to stats.mapValues { (_, s) -> linkedMapOf("n" to s.n, "upRate" to s.upRate, "meanRetPct" to s.meanRetPct, "excessPct" to s.excessPct) },
        "metrics" to metrics,
        "calibration" to calibration.map { it.toList() },
    )

    companion object {
        fun fromMap(m: Map<String, Any?>): HorizonModel {
            val t = m["thresholds"].obj()
            return HorizonModel(
                days = m["days"].num()!!.toInt(),
                kind = m["kind"].str() ?: "logistic",
                featureNames = m["featureNames"].list().map { it.str()!! },
                mean = m["mean"].doubles(), std = m["std"].doubles(), coef = m["coef"].doubles(),
                intercept = m["intercept"].num() ?: 0.0,
                trees = m["trees"].list().map { Tree.fromMap(it.obj()) },
                baseScore = m["baseScore"].num() ?: 0.0,
                calX = m["calX"].doubles(), calY = m["calY"].doubles(),
                baseRate = m["baseRate"].num()!!,
                thresholds = Thresholds(t["strong"].num()!!, t["buy"].num()!!, t["weak"].num()!!, t["weakSell"].num()!!, t["sell"].num()!!),
                sellReal = m["sellReal"] as? Boolean ?: false,
                weakSellReal = m["weakSellReal"] as? Boolean ?: false,
                intervalK = m["intervalK"].num() ?: 1.0,
                stats = m["stats"].obj().mapValues { (_, v) -> val o = v.obj(); LabelStat(o["n"].num()!!.toInt(), o["upRate"].num()!!, o["meanRetPct"].num() ?: 0.0, o["excessPct"].num() ?: 0.0) },
                metrics = m["metrics"].obj().mapValues { it.value.num() ?: Double.NaN },
                calibration = m["calibration"].list().map { it.doubles() },
                target = m["target"].str() ?: "up",
            )
        }
    }
}

class ModelBundle(
    val version: String,
    val trainedAt: String,
    val dataFrom: String,
    val dataTo: String,
    val trainTickers: Int,
    val holdoutTickers: Int,
    val horizons: List<HorizonModel>,
    val notes: String = "",
) {
    fun horizon(days: Int, target: String = "up") = horizons.firstOrNull { it.days == days && it.target == target }

    /** A bundle only applies if it was trained on exactly this engine's features. */
    val compatible: Boolean get() = horizons.isNotEmpty() && horizons.all { h ->
        !h.needsFeatures || (h.featureNames == Features.NAMES && (h.kind != "logistic" || h.coef.size == Features.SIZE))
    }

    fun toJson(): String = MiniJson.pretty(linkedMapOf(
        "version" to version, "trainedAt" to trainedAt, "dataFrom" to dataFrom, "dataTo" to dataTo,
        "trainTickers" to trainTickers, "holdoutTickers" to holdoutTickers, "notes" to notes,
        "horizons" to horizons.map { it.toMap() },
    ))

    companion object {
        fun fromJson(text: String): ModelBundle {
            val m = MiniJson.parse(text).obj()
            return ModelBundle(
                version = m["version"].str() ?: "?", trainedAt = m["trainedAt"].str() ?: "?",
                dataFrom = m["dataFrom"].str() ?: "?", dataTo = m["dataTo"].str() ?: "?",
                trainTickers = m["trainTickers"].num()?.toInt() ?: 0, holdoutTickers = m["holdoutTickers"].num()?.toInt() ?: 0,
                horizons = m["horizons"].list().map { HorizonModel.fromMap(it.obj()) },
                notes = m["notes"].str() ?: "",
            )
        }
    }
}
