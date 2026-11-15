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
import kotlin.math.max
import kotlin.math.min
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

    // ── Big up-day statistics for the Quick wins tab (fast; runs first) ──
    run {
        val eras = mutableMapOf<String, MutableMap<String, com.shinobisondre.stockanalyzer.engine.DayStat>>()
        val usT = DayAfterStats.build(bars, groups["discovery"]!! + groups["holdout"]!!, byEra = eras)
        val osloT = DayAfterStats.build(bars, groups["oslo"]!!)
        val to = spy.lastOrNull()?.date ?: "?"
        val book = com.shinobisondre.stockanalyzer.engine.DayAfterBook(java.time.Instant.now().toString(), to, usT, osloT)
        File(out, "dayafter.json").writeText(book.toJson())
        val rep = DayAfterStats.report(usT, osloT, eras, to)
        File(out, "DAYAFTER_REPORT.md").writeText(rep)
        println(rep)
    }
    if (args.contains("--dayafter-only")) { println("Done (day-after stats only) in ${(System.currentTimeMillis() - t0) / 1000}s"); return }
    val vix = bars[Universe.VIX] ?: emptyList()
    val market = MarketSeries(spy, vix)

    val ds = DataBuilder(market, spy).apply {
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

    val years = IntArray(ds.rows) { ds.date[it] / 10000 }
    val distinctYears = years.distinct().sorted()
    val firstTestYear = (distinctYears.firstOrNull() ?: 2010) + 5
    val testYears = distinctYears.filter { it >= firstTestYear }
    val binner = Binner(ds.x, ds.rows, Features.SIZE, (0 until ds.rows).filter { ds.group[it] == 0 }.toIntArray())
    val gbParams = GbdtParams()
    val kinds = listOf("rules", "logistic", "gbdt")
    report.appendLine("Three predictors compete on identical out-of-sample rows. Each one's raw score is mapped to a probability by a monotone calibration fitted on the *other* years (leave-one-year-out), so all three are judged as calibrated probabilities. The one with the lowest Brier score on US stocks ships in the app.")
    report.appendLine()

    for (target in listOf("up", "beat")) for (h in listOf(5, 10, 22)) {
        println("── Target $target, horizon $h ──")
        val y = BooleanArray(ds.rows) { ds.outcome(h, it, target).let { r -> !r.isNaN() && r > 0 } }
        val has = BooleanArray(ds.rows) { !ds.outcome(h, it, target).isNaN() }
        val raw = kinds.associateWith { DoubleArray(ds.rows) { Double.NaN } }
        for (yr in testYears) {
            val cutoff = (yr - 1) * 10000 + 1115 // label must end before Jan 1 (22 sessions ≈ 6 weeks)
            val train = (0 until ds.rows).filter { ds.group[it] == 0 && has[it] && ds.date[it] < cutoff }.toIntArray()
            val test = (0 until ds.rows).filter { has[it] && years[it] == yr }
            if (train.size < 5000 || test.isEmpty()) continue
            val lm = Logistic(Features.SIZE).fit(ds.x, y, train)
            val gm = Gbdt.fit(binner, y, train, gbParams.copy(seed = yr.toLong()))
            for (r in test) {
                raw["rules"]!![r] = ds.rules[r].toDouble()
                raw["logistic"]!![r] = lm.prob(ds.x, r).let { ln(it / (1 - it)) }
                raw["gbdt"]!![r] = gm.raw(ds.row(r))
            }
            println("  $yr: train ${train.size}, test ${test.size}")
        }
        val oosRows = (0 until ds.rows).filter { !raw["rules"]!![it].isNaN() }
        val usRows = oosRows.filter { ds.group[it] <= 1 }
        val baseRate = usRows.count { y[it] }.toDouble() / usRows.size.coerceAtLeast(1)
        val meanRetAll = usRows.map { ds.outcome(h, it, target) }.average()

        // Leave-one-year-out calibrated probabilities for every kind (US rows fit the maps; Oslo only evaluated).
        val cal = kinds.associateWith { DoubleArray(ds.rows) { Double.NaN } }
        val baseLoyo = DoubleArray(ds.rows) { Double.NaN }
        for (yr in testYears) {
            val fitRows = usRows.filter { years[it] != yr }
            val applyRows = oosRows.filter { years[it] == yr }
            if (fitRows.isEmpty() || applyRows.isEmpty()) continue
            val yy = BooleanArray(fitRows.size) { y[fitRows[it]] }
            val br = yy.count { it }.toDouble() / yy.size
            for (r in applyRows) baseLoyo[r] = br
            for (kd in kinds) {
                val (cx, cy) = Calib.fit(DoubleArray(fitRows.size) { raw[kd]!![fitRows[it]] }, yy)
                for (r in applyRows) cal[kd]!![r] = Calib.apply(cx, cy, raw[kd]!![r])
            }
        }
        fun eval(kd: String, rows: List<Int>): Map<String, Double> {
            if (rows.isEmpty()) return emptyMap()
            val p = DoubleArray(rows.size) { cal[kd]!![rows[it]] }
            val yy = BooleanArray(rows.size) { y[rows[it]] }
            val b = DoubleArray(rows.size) { baseLoyo[rows[it]] }
            val rw = DoubleArray(rows.size) { raw[kd]!![rows[it]] }
            val order = rows.indices.sortedBy { rw[it] }
            val top = order.takeLast(rows.size / 10); val bottom = order.take(rows.size / 10)
            val brier = Metrics.brier(p, yy); val brierBase = Metrics.brier(b, yy)
            return linkedMapOf("n" to rows.size.toDouble(), "auc" to Metrics.auc(rw, yy), "brier" to brier, "brierBase" to brierBase,
                "skill" to 1 - brier / brierBase, "top10" to top.count { yy[it] }.toDouble() / top.size.coerceAtLeast(1),
                "bottom10" to bottom.count { yy[it] }.toDouble() / bottom.size.coerceAtLeast(1), "upRate" to yy.count { it }.toDouble() / rows.size)
        }
        val evalUs = kinds.associateWith { eval(it, usRows) }
        val evalHold = kinds.associateWith { eval(it, oosRows.filter { r -> ds.group[r] == 1 }) }
        val evalOslo = kinds.associateWith { eval(it, oosRows.filter { r -> ds.group[r] == 2 }) }
        // Simplest kind wins unless a more complex one is clearly better.
        var chosen = "rules"
        for (kd in listOf("logistic", "gbdt")) if ((evalUs[kd]!!["brier"] ?: 1.0) < (evalUs[chosen]!!["brier"] ?: 1.0) - 0.00005) chosen = kd
        println("  chosen: $chosen  " + kinds.joinToString { "$it brier=${f4(evalUs[it]!!["brier"])} auc=${f3(evalUs[it]!!["auc"])}" })

        // Shipped calibration: all US out-of-sample rows.
        val (cx, cy) = Calib.fit(DoubleArray(usRows.size) { raw[chosen]!![usRows[it]] }, BooleanArray(usRows.size) { y[usRows[it]] })
        val pcal = cal[chosen]!!
        val usP = usRows.map { pcal[it] }.toDoubleArray()
        // Labels: quantile cut-offs, never closer to the base rate than a minimum edge, and a label is
        // only kept if its own out-of-sample record beats the base rate (and stronger beats weaker).
        var th = Thresholds(
            strong = max(Metrics.quantile(usP, 0.97), baseRate + 0.04),
            buy = max(Metrics.quantile(usP, 0.90), baseRate + 0.02),
            weak = max(Metrics.quantile(usP, 0.80), baseRate + 0.01),
            weakSell = min(Metrics.quantile(usP, 0.10), baseRate - 0.01),
            sell = min(Metrics.quantile(usP, 0.03), baseRate - 0.03),
        )
        fun bucketOf(t: Thresholds, p: Double): Int = when { p >= t.strong -> 0; p >= t.buy -> 1; p >= t.weak -> 2; p <= t.sell -> 6; p <= t.weakSell -> 5; else -> 3 }
        fun statOf(rs: List<Int>): LabelStat {
            val rets = rs.map { ds.outcome(h, it, target) }
            return LabelStat(rs.size, rs.count { y[it] }.toDouble() / rs.size.coerceAtLeast(1), rets.average() * 100, (rets.average() - meanRetAll) * 100)
        }
        val dropped = mutableListOf<String>()
        repeat(6) {
            val bk = usRows.groupBy { bucketOf(th, pcal[it]) }.mapValues { statOf(it.value) }
            val sb = bk[0]; val b = bk[1]; val w = bk[2]; val ws = bk[5]; val se = bk[6]
            when {
                sb != null && (sb.n < 500 || sb.upRate < baseRate + 0.02 || (b != null && sb.upRate < b.upRate)) -> { th = th.copy(strong = 2.0); dropped += "STRONG BUY" }
                b != null && (b.n < 500 || b.upRate < baseRate + 0.01) -> { th = th.copy(buy = th.strong); dropped += "BUY" }
                w != null && (w.n < 500 || w.upRate < baseRate + 0.005) -> { th = th.copy(weak = th.buy); dropped += "WEAK BUY" }
                se != null && (se.n < 500 || se.upRate > baseRate - 0.01) -> { th = th.copy(sell = -1.0); dropped += "SELL/AVOID (bottom 3%)" }
                ws != null && (ws.n < 500 || ws.upRate > baseRate - 0.005) -> { th = th.copy(weakSell = th.sell); dropped += "WEAK SELL/AVOID" }
                else -> return@repeat
            }
        }
        fun bucket(p: Double) = bucketOf(th, p)
        val buckets = usRows.groupBy { bucket(pcal[it]) }
        val sellReal = (buckets[6]?.let { statOf(it).upRate } ?: 1.0) < 0.5
        val weakSellReal = (buckets[5]?.let { statOf(it).upRate } ?: 1.0) < 0.5
        val names = listOf("STRONG_BUY", "BUY", "WEAK_BUY", "HOLD", "?", "WEAK_SELL", "SELL")
        val stats = LinkedHashMap<String, LabelStat>()
        for (lab in listOf(0, 1, 2, 3)) buckets[lab]?.let { stats[names[lab]] = statOf(it) }
        if (weakSellReal) buckets[5]?.let { stats["WEAK_SELL"] = statOf(it) }
        if (sellReal) buckets[6]?.let { stats["SELL"] = statOf(it) }
        val avoidRows = (if (!weakSellReal) buckets[5].orEmpty() else emptyList()) + (if (!sellReal) buckets[6].orEmpty() else emptyList())
        if (avoidRows.isNotEmpty()) stats["AVOID"] = statOf(avoidRows)

        // 80% band calibration: |log return| / (σ·√h).
        val zs = usRows.mapNotNull { r -> val v = ds.vol[r]; if (v > 0) abs(ln(1 + ds.ret(h, r))) / (v * sqrt(h.toDouble())) else null }.toDoubleArray()
        val intervalK = Metrics.quantile(zs, 0.80) / 1.2816
        val inside = zs.count { it <= 1.2816 * intervalK }.toDouble() / zs.size.coerceAtLeast(1)

        // Reliability deciles of the shipped (LOYO-calibrated) probabilities.
        val sorted = usRows.sortedBy { pcal[it] }
        val calib = (0 until 10).mapNotNull { d ->
            val part = sorted.subList(d * sorted.size / 10, (d + 1) * sorted.size / 10)
            if (part.isEmpty()) null else doubleArrayOf(part.map { pcal[it] }.average(), part.count { y[it] }.toDouble() / part.size, part.size.toDouble())
        }

        // Final model of the chosen kind, fitted on all discovery rows.
        val finalRows = (0 until ds.rows).filter { ds.group[it] == 0 && has[it] }.toIntArray()
        var lmF: Logistic? = null; var gmF: GbdtModel? = null
        when (chosen) {
            "logistic" -> lmF = Logistic(Features.SIZE).fit(ds.x, y, finalRows)
            "gbdt" -> gmF = Gbdt.fit(binner, y, finalRows, gbParams)
        }
        val ev = evalUs[chosen]!!
        val metrics = linkedMapOf(
            "auc" to (ev["auc"] ?: Double.NaN), "brier" to (ev["brier"] ?: Double.NaN), "brierBase" to (ev["brierBase"] ?: Double.NaN),
            "skill" to (ev["skill"] ?: Double.NaN), "aucHoldout" to (evalHold[chosen]!!["auc"] ?: Double.NaN),
            "aucOslo" to (evalOslo[chosen]!!["auc"] ?: Double.NaN), "aucRules" to (evalUs["rules"]!!["auc"] ?: Double.NaN),
            "brierRules" to (evalUs["rules"]!!["brier"] ?: Double.NaN), "intervalCoverage" to inside, "oosRows" to usRows.size.toDouble(),
        )
        horizons += HorizonModel(
            days = h, kind = chosen, featureNames = Features.NAMES,
            mean = lmF?.mean ?: DoubleArray(0), std = lmF?.std ?: DoubleArray(0),
            coef = lmF?.beta?.copyOfRange(1, lmF.beta.size) ?: DoubleArray(0), intercept = lmF?.beta?.get(0) ?: 0.0,
            trees = gmF?.trees ?: emptyList(), baseScore = gmF?.base ?: 0.0,
            calX = cx, calY = cy, baseRate = baseRate, thresholds = th, sellReal = sellReal, weakSellReal = weakSellReal,
            intervalK = intervalK, stats = stats, metrics = metrics, calibration = calib, target = target,
        )

        // ── Report section ──
        val what = if (target == "beat") "beat the S&P 500" else "were higher"
        report.appendLine("## ${if (target == "beat") "Beat the S&P 500" else "Price higher"} — ${h} trading days (${mapOf(5 to "1 week", 10 to "2 weeks", 22 to "1 month")[h]})")
        report.appendLine()
        report.appendLine("Base rate (share of US stock-days that $what after $h sessions): **${pc(baseRate)}**. Shipped predictor: **$chosen**." +
            if (dropped.isNotEmpty()) " Labels dropped because their own out-of-sample record did not beat the base rate: ${dropped.distinct().joinToString(", ")}." else "")
        report.appendLine()
        report.appendLine("| Predictor | set | AUC | Brier | Brier (base rate) | skill | top-10% up-rate | bottom-10% up-rate |")
        report.appendLine("|---|---|---:|---:|---:|---:|---:|---:|")
        for ((setName, evals) in listOf("US (all OOS)" to evalUs, "Holdout tickers" to evalHold, "Oslo" to evalOslo)) {
            for (kd in kinds) {
                val e = evals[kd]!!
                if (e.isEmpty()) continue
                report.appendLine("| ${if (kd == chosen) "**$kd**" else kd} | $setName | ${f3(e["auc"])} | ${f4(e["brier"])} | ${f4(e["brierBase"])} | ${pcSigned(e["skill"])} | ${pc(e["top10"]!!)} | ${pc(e["bottom10"]!!)} |")
            }
        }
        report.appendLine()
        report.appendLine("AUC 0.5 = coin flip. Skill = 1 − Brier / Brier(base rate): above 0 means the probabilities beat \"stocks usually go up\".")
        report.appendLine()
        report.appendLine("**Calls with the shipped predictor (US, out of sample):**")
        report.appendLine()
        report.appendLine("| Call | threshold | share | n | % $what after $h d | avg ${if (target == "beat") "excess " else ""}return | vs all stocks |")
        report.appendLine("|---|---:|---:|---:|---:|---:|---:|")
        val thOf = mapOf("STRONG_BUY" to "≥ ${pc(th.strong)}", "BUY" to "≥ ${pc(th.buy)}", "WEAK_BUY" to "≥ ${pc(th.weak)}", "HOLD" to "—",
            "WEAK_SELL" to "≤ ${pc(th.weakSell)}", "SELL" to "≤ ${pc(th.sell)}", "AVOID" to "≤ ${pc(th.weakSell)}")
        for ((k2, st) in stats) report.appendLine("| ${k2.replace('_', ' ')} | ${thOf[k2]} | ${pc(st.n.toDouble() / usRows.size)} | ${"%,d".format(st.n)} | ${pc(st.upRate)} | ${f2(st.meanRetPct)}% | ${if (st.excessPct >= 0) "+" else ""}${f2(st.excessPct)} pts |")
        report.appendLine()
        val legacyBull = usRows.filter { ds.rulesCall[it] <= 2 }
        report.appendLine("For reference, the old rules engine's own BUY labels (unchanged thresholds) fired on ${pc(legacyBull.size.toDouble() / usRows.size)} of days and the share that $what after $h d was ${pc(legacyBull.count { y[it] }.toDouble() / legacyBull.size.coerceAtLeast(1))}.")
        report.appendLine()
        report.appendLine("**Calibration** (shipped predictor, predicted vs realised, deciles):")
        report.appendLine()
        report.appendLine("| decile | mean predicted | realised | n |")
        report.appendLine("|---:|---:|---:|---:|")
        calib.forEachIndexed { i, c -> report.appendLine("| ${i + 1} | ${pc(c[0])} | ${pc(c[1])} | ${"%,d".format(c[2].toInt())} |") }
        report.appendLine()
        report.appendLine("**By period** (bullish calls vs that period's base rate):")
        report.appendLine()
        report.appendLine("| period | base up-rate | shipped bullish: n / % up | old rules bullish: n / % up |")
        report.appendLine("|---|---:|---:|---:|")
        for ((era, from, to) in ERAS) {
            val rs = usRows.filter { ds.date[it] in from..to }
            if (rs.size < 500) continue
            val nb = rs.filter { bucket(pcal[it]) <= 2 }; val ob = rs.filter { ds.rulesCall[it] <= 2 }
            report.appendLine("| $era | ${pc(rs.count { y[it] }.toDouble() / rs.size)} | ${"%,d".format(nb.size)} / ${pc(nb.count { y[it] }.toDouble() / nb.size.coerceAtLeast(1))} | ${"%,d".format(ob.size)} / ${pc(ob.count { y[it] }.toDouble() / ob.size.coerceAtLeast(1))} |")
        }
        report.appendLine()
        report.appendLine("80% price band: width multiplier ${f3(intervalK)} × σ√h covers ${pc(inside)} of outcomes.")
        report.appendLine()
        if (gmF != null) {
            report.appendLine("<details><summary>Feature importance (total split gain)</summary>")
            report.appendLine()
            report.appendLine("| feature | share of gain |")
            report.appendLine("|---|---:|")
            val tg = gmF.gain.sum().coerceAtLeast(1e-12)
            Features.DEFS.indices.sortedByDescending { gmF.gain[it] }.take(20).forEach { report.appendLine("| ${Features.DEFS[it].label} (`${Features.DEFS[it].key}`) | ${pc(gmF.gain[it] / tg)} |") }
            report.appendLine()
            report.appendLine("</details>")
            report.appendLine()
        }
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
        notes = "Per horizon the best of rules / logistic / gradient-boosted trees by out-of-sample Brier score, with a monotone calibration map. Call thresholds at the 97/90/80/10/3 percentiles of calibrated probabilities, never closer than 1-4 points to the base rate.",
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
private fun pcSigned(v: Double?) = if (v == null || v.isNaN()) "—" else "%+.2f%%".format(v * 100)
private fun f2(v: Double?) = if (v == null || v.isNaN()) "—" else "%.2f".format(v)
private fun f3(v: Double?) = if (v == null || v.isNaN()) "—" else "%.3f".format(v)
private fun f4(v: Double?) = if (v == null || v.isNaN()) "—" else "%.4f".format(v)
private fun intToIso(d: Int) = "%04d-%02d-%02d".format(d / 10000, d / 100 % 100, d % 100)

/** Flattened stock-day rows: features + forward returns + the legacy rules read. */
class DataBuilder(private val market: MarketSeries, spy: List<Candle>) {
    private val spyDates = Array(spy.size) { spy[it].date }
    private val spyAdj = DoubleArray(spy.size) { spy[it].adjClose }
    private fun spyFloor(d: String): Int {
        var lo = 0; var hi = spyDates.size - 1; var ans = -1
        while (lo <= hi) { val mid = (lo + hi) ushr 1; if (spyDates[mid] <= d) { ans = mid; lo = mid + 1 } else hi = mid - 1 }
        return ans
    }
    private val e5 = FloatList(); private val e10 = FloatList(); private val e22 = FloatList()
    /** Return minus the S&P 500's return over the same calendar window. */
    fun excess(h: Int, r: Int): Double = when (h) { 5 -> e5[r]; 10 -> e10[r]; else -> e22[r] }.toDouble()
    fun outcome(h: Int, r: Int, target: String) = if (target == "beat") excess(h, r) else ret(h, r)
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
    fun row(r: Int): DoubleArray = DoubleArray(k) { xs.a[r * k + it].toDouble() }
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
            val j0 = spyFloor(d)
            fun ex(h: Int): Float {
                if (i + h > s.last || j0 < 0 || s.adj[i] <= 0) return Float.NaN
                val j1 = spyFloor(s.dates[i + h])
                if (j1 <= j0 || spyAdj[j0] <= 0) return Float.NaN
                return ((s.adj[i + h] / s.adj[i] - 1) - (spyAdj[j1] / spyAdj[j0] - 1)).toFloat()
            }
            e5.add(ex(5)); e10.add(ex(10)); e22.add(ex(22))
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
