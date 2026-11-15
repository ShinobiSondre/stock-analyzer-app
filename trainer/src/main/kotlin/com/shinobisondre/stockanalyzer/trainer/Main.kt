package com.shinobisondre.stockanalyzer.trainer

import com.shinobisondre.stockanalyzer.engine.Call
import com.shinobisondre.stockanalyzer.engine.Candle
import com.shinobisondre.stockanalyzer.engine.Features
import com.shinobisondre.stockanalyzer.engine.HorizonModel
import com.shinobisondre.stockanalyzer.engine.LabelStat
import com.shinobisondre.stockanalyzer.engine.MarketPoint
import com.shinobisondre.stockanalyzer.engine.MarketSeries
import com.shinobisondre.stockanalyzer.engine.ModelBundle
import com.shinobisondre.stockanalyzer.engine.Rules
import com.shinobisondre.stockanalyzer.engine.Series
import com.shinobisondre.stockanalyzer.engine.StackVerdict
import com.shinobisondre.stockanalyzer.engine.Technical
import com.shinobisondre.stockanalyzer.engine.Thresholds
import com.shinobisondre.stockanalyzer.engine.TradingDays
import com.shinobisondre.stockanalyzer.engine.Tyler
import com.shinobisondre.stockanalyzer.engine.TylerResult
import java.io.File
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Walk-forward trainer for the app's probability model.
 *
 *   1. Download daily bars 2004→today for ~200 tickers + SPY + VIX.
 *   2. At every bar, build the exact feature vector the app builds.
 *   3. For each year Y from 2010: fit on DISCOVERY tickers using only bars
 *      whose label ended before Y, predict every ticker in year Y. That gives
 *      ~16 years of genuinely out-of-sample predictions, plus HOLDOUT tickers
 *      the fitting never saw and Oslo names as an out-of-domain check.
 *   4. Fit the shipped model on all DISCOVERY data; attach the out-of-sample
 *      stats so the app can say "calls like this rose X% of the time".
 *
 * Usage: trainer --cache DIR --out DIR [--synthetic]
 */
fun main(args: Array<String>) {
    val opt = args.toList().windowed(2, 1, true).associate { it[0] to it.getOrNull(1) }
    val cache = File(opt["--cache"] ?: "build/wf-cache")
    val out = File(opt["--out"] ?: "build/model-out").apply { mkdirs() }
    val synthetic = args.contains("--synthetic")
    val t0 = System.currentTimeMillis()

    val groups = linkedMapOf("discovery" to Universe.DISCOVERY, "holdout" to Universe.HOLDOUT, "oslo" to Universe.OSLO)
    val bars = HashMap<String, List<Candle>>()
    val failures = mutableMapOf<String, String>()
    if (synthetic) {
        val syn = Synthetic()
        bars[Universe.SPY] = syn.series(5600, 0.0003, 1)
        bars[Universe.VIX] = syn.vix(bars[Universe.SPY]!!)
        groups.replaceAll { _, list -> list.take(12) }
        groups.values.flatten().forEachIndexed { i, s -> bars[s] = syn.series(3000 + (i * 97) % 2600, 0.0002, i + 5) }
    } else {
        val store = BarStore(File(cache, "bars"))
        val all = listOf(Universe.SPY, Universe.VIX) + groups.values.flatten()
        println("Fetching ${all.size} symbols…")
        failures += store.fetchAll(all)
        for (s in all) store.load(s)?.let { bars[s] = it }
        println("Loaded ${bars.size} series; ${failures.size} failures: ${failures.keys.joinToString(" ")}")
    }
    val spy = bars[Universe.SPY] ?: error("SPY missing — cannot build market features")
    val vix = bars[Universe.VIX] ?: emptyList()
    val market = MarketSeries(spy, vix)

    val ds = DataBuilder(market).apply {
        groups.entries.forEachIndexed { gi, (_, syms) -> for (s in syms) bars[s]?.let { add(s, gi, it) } }
    }
    println("Dataset: ${ds.rows} rows from ${ds.symbols.size} symbols in ${(System.currentTimeMillis() - t0) / 1000}s")

    val report = StringBuilder()
    val horizons = mutableListOf<HorizonModel>()
    val dates = (0 until ds.rows).map { ds.date[it] }
    val dataFrom = intToIso(dates.minOrNull() ?: 0)
    val dataTo = intToIso(dates.maxOrNull() ?: 0)

    report.appendLine("# StockAnalyzer probability model — validation report")
    report.appendLine()
    report.appendLine("Generated ${LocalDate.now()} by the trainer in `trainer/`. Data: daily bars $dataFrom → $dataTo" +
        (if (synthetic) " (**SYNTHETIC SMOKE TEST — not real data**)" else " from Yahoo Finance") + ".")
    report.appendLine()
    report.appendLine("* Fitting set: ${ds.countSymbols(0)} US large caps (the web app's DISCOVERY list).")
    report.appendLine("* Holdout: ${ds.countSymbols(1)} US tickers never used for fitting.")
    report.appendLine("* Out-of-domain: ${ds.countSymbols(2)} Oslo Børs tickers (model uses US market features — expect weaker results).")
    report.appendLine("* Rows: ${"%,d".format(ds.rows)} stock-days. Every number below is **out of sample**: year Y is predicted by a model fitted only on data whose outcome was known before Y.")
    if (failures.isNotEmpty()) report.appendLine("* Download failures (skipped): ${failures.keys.sorted().joinToString(", ")}")
    report.appendLine()

    for (h in listOf(5, 10, 22)) {
        println("── Horizon $h ──")
        val y = BooleanArray(ds.rows) { ds.ret(h, it).let { r -> !r.isNaN() && r > 0 } }
        val has = BooleanArray(ds.rows) { !ds.ret(h, it).isNaN() }
        val oos = DoubleArray(ds.rows) { Double.NaN }
        val years = dates.map { it / 10000 }.distinct().sorted()
        val firstTestYear = (years.firstOrNull() ?: 2010) + 5
        for (yr in years.filter { it >= firstTestYear }) {
            val cutoff = (yr - 1) * 10000 + 1115 // label must end before Jan 1 (22 sessions ≈ 6 weeks)
            val train = (0 until ds.rows).filter { ds.group[it] == 0 && has[it] && ds.date[it] < cutoff }.toIntArray()
            val test = (0 until ds.rows).filter { has[it] && ds.date[it] / 10000 == yr }
            if (train.size < 5000 || test.isEmpty()) continue
            val m = Logistic(Features.SIZE).fit(ds.x, y, train)
            for (r in test) oos[r] = m.prob(ds.x, r)
            println("  $yr: train ${train.size}, test ${test.size}")
        }
        val finalRows = (0 until ds.rows).filter { ds.group[it] == 0 && has[it] }.toIntArray()
        val fin = Logistic(Features.SIZE).fit(ds.x, y, finalRows)

        // Rows with an out-of-sample prediction, by group.
        val oosRows = (0 until ds.rows).filter { !oos[it].isNaN() }
        val usRows = oosRows.filter { ds.group[it] <= 1 }
        val baseRate = usRows.count { y[it] }.toDouble() / usRows.size.coerceAtLeast(1)
        val meanRetAll = usRows.map { ds.ret(h, it) }.average()

        val levels = doubleArrayOf(0.97, 0.90, 0.80, 0.10, 0.03)
        val oosP = usRows.map { oos[it] }.toDoubleArray()
        val qOos = levels.map { Metrics.quantile(oosP, it) }
        fun labelOf(p: Double, q: List<Double>): Int = when {
            p >= q[0] -> 0; p >= q[1] -> 1; p >= q[2] -> 2; p <= q[4] -> 6; p <= q[3] -> 5; else -> 3
        }
        val names = listOf("STRONG_BUY", "BUY", "WEAK_BUY", "HOLD", "?", "WEAK_SELL", "SELL")
        val buckets = usRows.groupBy { labelOf(oos[it], qOos) }
        fun statOf(rs: List<Int>): LabelStat {
            val rets = rs.map { ds.ret(h, it) }
            return LabelStat(rs.size, rs.count { y[it] }.toDouble() / rs.size.coerceAtLeast(1), rets.average() * 100, (rets.average() - meanRetAll) * 100)
        }
        val sellReal = (buckets[6]?.let { statOf(it).upRate } ?: 1.0) < 0.5
        val weakSellReal = (buckets[5]?.let { statOf(it).upRate } ?: 1.0) < 0.5
        val stats = LinkedHashMap<String, LabelStat>()
        buckets.toSortedMap().forEach { (lab, rs) ->
            val key = when (lab) { 5 -> if (weakSellReal) "WEAK_SELL" else "AVOID_WEAK"; 6 -> if (sellReal) "SELL" else "AVOID"; else -> names[lab] }
            stats[key] = statOf(rs)
        }
        // AVOID as a whole (both bottom buckets when neither is a real sell)
        if (!sellReal || !weakSellReal) {
            val avoidRows = (if (!sellReal) buckets[6].orEmpty() else emptyList()) + (if (!weakSellReal) buckets[5].orEmpty() else emptyList())
            if (avoidRows.isNotEmpty()) stats["AVOID"] = statOf(avoidRows)
            stats.remove("AVOID_WEAK")
        }

        // Shipped thresholds: same quantile levels, on the final model's own predictions.
        val finP = (0 until ds.rows).filter { ds.group[it] <= 1 }.map { fin.prob(ds.x, it) }.toDoubleArray()
        val qFin = levels.map { Metrics.quantile(finP, it) }

        // 80% band calibration: |log return| / (σ·√h).
        val zs = usRows.mapNotNull { r -> val v = ds.vol[r]; if (v > 0) abs(ln(1 + ds.ret(h, r))) / (v * sqrt(h.toDouble())) else null }.toDoubleArray()
        val intervalK = Metrics.quantile(zs, 0.80) / 1.2816
        val inside = zs.count { it <= 1.2816 * intervalK }.toDouble() / zs.size.coerceAtLeast(1)

        // Metrics by group + legacy comparison.
        fun groupMetrics(rows: List<Int>): Map<String, Double> {
            if (rows.isEmpty()) return emptyMap()
            val p = rows.map { oos[it] }.toDoubleArray(); val yy = BooleanArray(rows.size) { y[rows[it]] }
            val base = DoubleArray(rows.size) { baseRate }
            val legacy = rows.map { ds.rules[it].toDouble() }.toDoubleArray()
            return linkedMapOf("n" to rows.size.toDouble(), "auc" to Metrics.auc(p, yy), "aucRules" to Metrics.auc(legacy, yy),
                "brier" to Metrics.brier(p, yy), "brierBase" to Metrics.brier(base, yy), "logloss" to Metrics.logloss(p, yy), "loglossBase" to Metrics.logloss(base, yy),
                "upRate" to yy.count { it }.toDouble() / rows.size)
        }
        val mDisc = groupMetrics(oosRows.filter { ds.group[it] == 0 })
        val mHold = groupMetrics(oosRows.filter { ds.group[it] == 1 })
        val mOslo = groupMetrics(oosRows.filter { ds.group[it] == 2 })
        val mUs = groupMetrics(usRows)

        // Calibration deciles (US OOS).
        val sorted = usRows.sortedBy { oos[it] }
        val calib = (0 until 10).mapNotNull { d ->
            val part = sorted.subList(d * sorted.size / 10, (d + 1) * sorted.size / 10)
            if (part.isEmpty()) null else doubleArrayOf(part.map { oos[it] }.average(), part.count { y[it] }.toDouble() / part.size, part.size.toDouble())
        }

        val metrics = linkedMapOf(
            "auc" to (mUs["auc"] ?: Double.NaN), "aucHoldout" to (mHold["auc"] ?: Double.NaN), "aucOslo" to (mOslo["auc"] ?: Double.NaN),
            "aucRules" to (mUs["aucRules"] ?: Double.NaN), "brier" to (mUs["brier"] ?: Double.NaN), "brierBase" to (mUs["brierBase"] ?: Double.NaN),
            "intervalCoverage" to inside, "oosRows" to usRows.size.toDouble(),
        )
        horizons += HorizonModel(
            days = h, featureNames = Features.NAMES, mean = fin.mean, std = fin.std,
            coef = fin.beta.copyOfRange(1, fin.beta.size), intercept = fin.beta[0], baseRate = baseRate,
            thresholds = Thresholds(qFin[0], qFin[1], qFin[2], qFin[3], qFin[4]), sellReal = sellReal, weakSellReal = weakSellReal,
            intervalK = intervalK, stats = stats, metrics = metrics, calibration = calib,
        )

        // ── Report section ──
        report.appendLine("## ${h} trading days (${mapOf(5 to "1 week", 10 to "2 weeks", 22 to "1 month")[h]})")
        report.appendLine()
        report.appendLine("Base rate (share of all US stock-days higher after $h sessions): **${pc(baseRate)}**.")
        report.appendLine()
        report.appendLine("| Set | rows | AUC model | AUC old rules score | Brier model | Brier base rate | up-rate |")
        report.appendLine("|---|---:|---:|---:|---:|---:|---:|")
        for ((name, mm) in listOf("Discovery (time OOS)" to mDisc, "Holdout tickers" to mHold, "Oslo (out of domain)" to mOslo)) {
            if (mm.isEmpty()) continue
            report.appendLine("| $name | ${"%,d".format(mm["n"]!!.toInt())} | ${f3(mm["auc"])} | ${f3(mm["aucRules"])} | ${f4(mm["brier"])} | ${f4(mm["brierBase"])} | ${pc(mm["upRate"]!!)} |")
        }
        report.appendLine()
        report.appendLine("AUC 0.5 = coin flip. Brier below the base-rate Brier = the probabilities carry information beyond \"stocks usually go up\".")
        report.appendLine()
        report.appendLine("**Calls (US, out of sample):**")
        report.appendLine()
        report.appendLine("| Call | share | n | % higher after $h d | avg return | vs all stocks |")
        report.appendLine("|---|---:|---:|---:|---:|---:|")
        for ((k, s) in stats) report.appendLine("| ${k.replace('_', ' ')} | ${pc(s.n.toDouble() / usRows.size)} | ${"%,d".format(s.n)} | ${pc(s.upRate)} | ${f2(s.meanRetPct)}% | ${if (s.excessPct >= 0) "+" else ""}${f2(s.excessPct)} pts |")
        report.appendLine()
        // Legacy rules engine comparison on the same rows.
        val legacyBull = usRows.filter { ds.rulesCall[it] <= 2 }
        val newBull = usRows.filter { labelOf(oos[it], qOos) <= 2 }
        report.appendLine("Old rules engine, same rows: bullish calls ${pc(legacyBull.size.toDouble() / usRows.size)} of days, ${pc(legacyBull.count { y[it] }.toDouble() / legacyBull.size.coerceAtLeast(1))} higher after $h d. " +
            "New model: bullish calls ${pc(newBull.size.toDouble() / usRows.size)} of days, ${pc(newBull.count { y[it] }.toDouble() / newBull.size.coerceAtLeast(1))} higher.")
        report.appendLine()
        report.appendLine("**Calibration** (predicted vs realised, deciles):")
        report.appendLine()
        report.appendLine("| decile | mean predicted | realised | n |")
        report.appendLine("|---:|---:|---:|---:|")
        calib.forEachIndexed { i, c -> report.appendLine("| ${i + 1} | ${pc(c[0])} | ${pc(c[1])} | ${"%,d".format(c[2].toInt())} |") }
        report.appendLine()
        report.appendLine("**By period** (bullish calls vs that period's base rate):")
        report.appendLine()
        report.appendLine("| period | base up-rate | new bullish: n / % up | old bullish: n / % up |")
        report.appendLine("|---|---:|---:|---:|")
        for ((era, from, to) in ERAS) {
            val rs = usRows.filter { ds.date[it] in from..to }
            if (rs.size < 500) continue
            val nb = rs.filter { labelOf(oos[it], qOos) <= 2 }; val ob = rs.filter { ds.rulesCall[it] <= 2 }
            report.appendLine("| $era | ${pc(rs.count { y[it] }.toDouble() / rs.size)} | ${"%,d".format(nb.size)} / ${pc(nb.count { y[it] }.toDouble() / nb.size.coerceAtLeast(1))} | ${"%,d".format(ob.size)} / ${pc(ob.count { y[it] }.toDouble() / ob.size.coerceAtLeast(1))} |")
        }
        report.appendLine()
        report.appendLine("80% price band: width multiplier ${f3(intervalK)} × σ√h covers ${pc(inside)} of outcomes.")
        report.appendLine()
        report.appendLine("<details><summary>Coefficients (standardised log-odds)</summary>")
        report.appendLine()
        report.appendLine("| feature | coef |")
        report.appendLine("|---|---:|")
        Features.DEFS.indices.sortedByDescending { abs(fin.beta[it + 1]) }.forEach { report.appendLine("| ${Features.DEFS[it].label} (`${Features.DEFS[it].key}`) | ${f3(fin.beta[it + 1])} |") }
        report.appendLine()
        report.appendLine("</details>")
        report.appendLine()
    }

    report.appendLine("## Caveats")
    report.appendLine()
    report.appendLine("* Survivorship: tickers were chosen in 2026, so companies that failed are under-represented. Absolute probabilities may be a little optimistic; the *ranking* is what the holdout tests.")
    report.appendLine("* Overlapping windows: consecutive days share most of their forward window, so row counts overstate the number of independent observations.")
    report.appendLine("* News, analyst and catalyst inputs have no point-in-time history and are not part of the model — the app shows them as context only.")
    report.appendLine("* Not investment advice. A 60% call is wrong 40% of the time.")

    val bundle = ModelBundle(
        version = "m" + LocalDate.now().toString().replace("-", "") + (if (synthetic) "-synthetic" else ""),
        trainedAt = java.time.Instant.now().toString(), dataFrom = dataFrom, dataTo = dataTo,
        trainTickers = ds.countSymbols(0), holdoutTickers = ds.countSymbols(1), horizons = horizons,
        notes = "Logistic model per horizon on ${Features.SIZE} features; thresholds at the 97/90/80/10/3 percentiles of predictions.",
    )
    File(out, "model.json").writeText(bundle.toJson())
    File(out, "MODEL_REPORT.md").writeText(report.toString())
    File(out, "fetch_failures.txt").writeText(failures.entries.joinToString("\n") { "${it.key}: ${it.value}" })
    println(report)
    println("Done in ${(System.currentTimeMillis() - t0) / 1000}s → ${out.absolutePath}")
}

private val ERAS = listOf(
    Triple("2010-13", 20100101, 20131231), Triple("2014-19", 20140101, 20191231), Triple("2020-21", 20200101, 20211231),
    Triple("2022", 20220101, 20221231), Triple("2023", 20230101, 20231231), Triple("2024-26", 20240101, 20991231),
)

private fun pc(v: Double) = if (v.isNaN()) "—" else "%.1f%%".format(v * 100)
private fun f2(v: Double?) = if (v == null || v.isNaN()) "—" else "%.2f".format(v)
private fun f3(v: Double?) = if (v == null || v.isNaN()) "—" else "%.3f".format(v)
private fun f4(v: Double?) = if (v == null || v.isNaN()) "—" else "%.4f".format(v)
private fun intToIso(d: Int) = "%04d-%02d-%02d".format(d / 10000, d / 100 % 100, d % 100)

/** Flattened stock-day rows: features + forward returns + the legacy rules read. */
class DataBuilder(private val market: MarketSeries) {
    val k = Features.SIZE
    private val xs = FloatList(1 shl 22)
    private val dateL = IntList(); private val groupL = IntList(); private val symL = IntList()
    private val r5 = FloatList(); private val r10 = FloatList(); private val r22 = FloatList()
    private val volL = FloatList(); private val rulesL = FloatList(); private val rulesCallL = IntList()
    val symbols = mutableListOf<String>()
    private val symbolGroup = mutableListOf<Int>()
    private val mcache = HashMap<String, MarketPoint?>()

    val rows get() = dateL.size
    val x: FloatArray get() = xs.a
    val date: IntArray get() = dateL.a
    val group: IntArray get() = groupL.a
    val vol: FloatArray get() = volL.a
    val rules: FloatArray get() = rulesL.a
    val rulesCall: IntArray get() = rulesCallL.a
    fun ret(h: Int, r: Int): Double = when (h) { 5 -> r5[r]; 10 -> r10[r]; else -> r22[r] }.toDouble()
    fun countSymbols(g: Int) = symbolGroup.count { it == g }

    fun add(sym: String, g: Int, candles: List<Candle>) {
        if (candles.size < Features.MIN_BARS + 30) return
        val s = Series(candles)
        val si = symbols.size
        symbols += sym; symbolGroup += g
        var added = 0
        for (i in Features.MIN_BARS - 1..s.last) {
            if (i + 5 > s.last) break
            val d = s.dates[i]
            val m = mcache.getOrPut(d) { market.at(d) } ?: continue
            val tyl = Tyler.read(s, i)
            val fv = Features.build(s, i, m, tyl) ?: continue
            for (v in fv) xs.add(v.toFloat())
            fun fwd(h: Int) = if (i + h <= s.last && s.adj[i] > 0) (s.adj[i + h] / s.adj[i] - 1).toFloat() else Float.NaN
            r5.add(fwd(5)); r10.add(fwd(10)); r22.add(fwd(22))
            volL.add((s.ewmaVol[i].takeIf { !it.isNaN() } ?: s.vol20[i]).toFloat())
            val ind = Technical.snapshot(s, i)
            val verdict = when { (tyl?.sellHits ?: 0) >= 4 -> StackVerdict.FULL_SELL; (tyl?.sellHits ?: 0) == 3 -> StackVerdict.TRIM
                (tyl?.buyHits ?: 0) >= 4 -> StackVerdict.FULL_BUY; (tyl?.buyHits ?: 0) == 3 -> StackVerdict.ACCUMULATE; else -> StackVerdict.NEUTRAL }
            val rr = Rules.evaluate(ind, Technical.patterns(s, i), m, TylerResult(tyl, null, verdict))
            rulesL.add(rr.rawScore.toFloat())
            rulesCallL.add(rr.call.ordinal)
            dateL.add(d.replace("-", "").toInt()); groupL.add(g); symL.add(si)
            added++
        }
        if (symbols.size % 25 == 0) println("  built ${symbols.size} symbols, $rows rows")
    }
}

/** Deterministic synthetic markets for a local smoke test of the pipeline (no network). */
class Synthetic {
    private var x = 12345L
    private fun r(): Double { x = x * 6364136223846793005L + 1442695040888963407L; return ((x ushr 11) % 1_000_000) / 1_000_000.0 }
    fun series(n: Int, drift: Double, seed: Int): List<Candle> {
        x = seed * 7919L
        var p = 40.0; var d = "2004-06-01"
        val out = ArrayList<Candle>()
        var prevRet = 0.0
        for (i in 0 until n) {
            if (i > 0) d = TradingDays.addTradingDays(d, 1)
            // Planted short-term mean reversion so the pipeline has something to find.
            val ret = drift - 0.08 * prevRet + (r() - 0.5) * 0.03 + 0.002 * sin(i / 23.0)
            prevRet = ret
            val o = p; p *= 1 + ret
            out += Candle(d, o, maxOf(o, p) * (1 + r() * 0.008), minOf(o, p) * (1 - r() * 0.008), p, 1e6 * (0.6 + r()), p)
        }
        return out
    }
    fun vix(spy: List<Candle>): List<Candle> = spy.mapIndexed { i, c -> val v = 14 + 10 * (0.5 + 0.5 * sin(i / 70.0)) + r() * 3; Candle(c.date, v, v, v, v, 0.0, v) }
}
