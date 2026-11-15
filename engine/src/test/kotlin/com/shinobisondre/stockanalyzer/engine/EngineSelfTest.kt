package com.shinobisondre.stockanalyzer.engine

import com.shinobisondre.stockanalyzer.engine.MiniJson.doubles
import com.shinobisondre.stockanalyzer.engine.MiniJson.num
import com.shinobisondre.stockanalyzer.engine.MiniJson.obj
import kotlin.math.abs
import kotlin.math.sin

/**
 * Engine checks that run both locally (plain `main`, no JUnit needed) and in
 * CI (wrapped by EngineTest). Reference values for the indicators come from
 * the `technicalindicators` npm package the web app used.
 */
object EngineSelfTest {
    private var passed = 0
    private val failures = mutableListOf<String>()

    private fun check(name: String, cond: Boolean, detail: () -> String = { "" }) {
        if (cond) passed++ else failures += "$name ${detail()}"
    }
    private fun near(name: String, a: Double?, b: Double, tol: Double) =
        check(name, a != null && abs(a - b) <= tol) { "got $a expected $b (tol $tol)" }

    fun runAll(referenceJson: String): List<String> {
        passed = 0; failures.clear()
        indicatorsMatchReference(referenceJson)
        tradingDays()
        featuresAndModel()
        portfolioMath()
        holdingsText()
        trackRecord()
        rulesAndAnalyzer()
        jsonRoundTrip()
        println("EngineSelfTest: $passed checks passed, ${failures.size} failed")
        failures.forEach { println("  FAIL $it") }
        return failures.toList()
    }

    private fun candlesFrom(ref: Map<String, Any?>): List<Candle> {
        val c = ref["close"].doubles(); val h = ref["high"].doubles(); val l = ref["low"].doubles()
        val o = ref["open"].doubles(); val v = ref["volume"].doubles()
        var date = "2020-01-01"
        return c.indices.map { i ->
            if (i > 0) { date = TradingDays.addTradingDays(date, 1) }
            Candle(date, o[i], h[i], l[i], c[i], v[i])
        }
    }

    private fun indicatorsMatchReference(json: String) {
        val ref = MiniJson.parse(json).obj()
        val s = Series(candlesFrom(ref))
        val i = s.last
        near("sma20", s.sma20.at(i), ref["sma20"].num()!!, 1e-9)
        near("ema12", s.ema12.at(i), ref["ema12"].num()!!, 1e-9)
        near("rsi", s.rsi.at(i), ref["rsi"].num()!!, 0.006) // TI rounds RSI to 2 dp
        near("rsiFirst", s.rsi.at(14), ref["rsiFirst"].num()!!, 0.006)
        val m = ref["macd"].obj()
        near("macd", s.macd.line.at(i), m["MACD"].num()!!, 1e-9)
        near("macdSignal", s.macd.signal.at(i), m["signal"].num()!!, 1e-9)
        val bb = ref["bb"].obj()
        near("bbUpper", s.bb.upper.at(i), bb["upper"].num()!!, 1e-9)
        near("bbLower", s.bb.lower.at(i), bb["lower"].num()!!, 1e-9)
        val st = ref["stoch"].obj()
        near("stochK", s.stoch.k.at(i), st["k"].num()!!, 1e-9)
        near("stochD", s.stoch.d.at(i), st["d"].num()!!, 1e-9)
        val adx = ref["adx"].obj()
        near("adx", s.adx.adx.at(i), adx["adx"].num()!!, 0.5)
        near("pdi", s.adx.pdi.at(i), adx["pdi"].num()!!, 0.5)
        near("mdi", s.adx.mdi.at(i), adx["mdi"].num()!!, 0.5)
        near("atr", s.atr.at(i), ref["atr"].num()!!, 0.02)
        near("obv", s.obv[i], ref["obv"].num()!!, 1.0)
        near("williamsR", s.willR.at(i), ref["wr"].num()!!, 1e-9)
    }

    private fun tradingDays() {
        check("dow", TradingDays.dayOfWeek("2026-11-15") == 7) { "got ${TradingDays.dayOfWeek("2026-11-15")}" } // Sunday
        check("dowMon", TradingDays.dayOfWeek("2026-11-16") == 1)
        check("addTrading", TradingDays.addTradingDays("2026-11-13", 1) == "2026-11-16") { TradingDays.addTradingDays("2026-11-13", 1) }
        check("addTrading10", TradingDays.addTradingDays("2026-11-16", 10) == "2026-11-30") { TradingDays.addTradingDays("2026-11-16", 10) }
        check("leap", TradingDays.addDays("2028-02-28", 1) == "2028-02-29")
        check("weekly", Tyler.toWeekly(synthetic(30)).size in 6..7) { "${Tyler.toWeekly(synthetic(30)).size}" }
    }

    fun synthetic(n: Int, drift: Double = 0.0004, start: String = "2018-01-01", seed: Int = 7): List<Candle> {
        var x = seed.toLong()
        fun r(): Double { x = (x * 6364136223846793005L + 1442695040888963407L); return ((x ushr 11) % 1_000_000).toDouble() / 1_000_000 }
        var p = 50.0
        var d = start
        return (0 until n).map { i ->
            if (i > 0) d = TradingDays.addTradingDays(d, 1)
            val o = p
            p *= 1 + drift + (r() - 0.5) * 0.035 + 0.004 * sin(i / 9.0)
            val hi = maxOf(o, p) * (1 + r() * 0.01); val lo = minOf(o, p) * (1 - r() * 0.01)
            Candle(d, o, hi, lo, p, 1e6 * (0.5 + r()), p)
        }
    }

    private fun market(n: Int): MarketSeries {
        val spy = synthetic(n, 0.0003, seed = 11)
        val vix = spy.mapIndexed { i, c -> Candle(c.date, 20.0, 21.0, 19.0, 15 + 12 * (0.5 + 0.5 * sin(i / 40.0)), 0.0) }
        return MarketSeries(spy, vix)
    }

    fun dummyModel(kind: String = "logistic"): ModelBundle {
        val k = Features.SIZE
        val hs = listOf(5, 10, 22).map { d ->
            // One stump on leader_pb plus one on ret5, so gbdt attribution can be checked.
            val lp = Features.NAMES.indexOf("leader_pb"); val r5 = Features.NAMES.indexOf("ret5")
            val trees = listOf(
                Tree(intArrayOf(lp, -1, -1), doubleArrayOf(0.5, 0.0, 0.0), intArrayOf(1, -1, -1), intArrayOf(2, -1, -1), doubleArrayOf(0.0, -0.01, 0.3)),
                Tree(intArrayOf(r5, -1, -1), doubleArrayOf(0.0, 0.0, 0.0), intArrayOf(1, -1, -1), intArrayOf(2, -1, -1), doubleArrayOf(0.0, 0.05, -0.05)),
            )
            HorizonModel(d, kind, Features.NAMES, DoubleArray(k), DoubleArray(k) { 1.0 },
                DoubleArray(k) { if (Features.NAMES[it] == "leader_pb") 0.3 else if (Features.NAMES[it] == "ret5") -0.05 else 0.0 },
                0.2, trees, 0.2,
                calX = if (kind == "rules") doubleArrayOf(-1.0, 0.0, 1.0) else doubleArrayOf(-0.5, 0.0, 0.5, 1.0),
                calY = if (kind == "rules") doubleArrayOf(0.50, 0.55, 0.62) else doubleArrayOf(0.45, 0.53, 0.58, 0.64),
                baseRate = 0.55, thresholds = Thresholds(0.66, 0.61, 0.59, 0.51, 0.48), sellReal = false, weakSellReal = false, intervalK = 1.05,
                stats = mapOf("BUY" to LabelStat(1200, 0.61, 1.2, 0.5)), metrics = mapOf("auc" to 0.55), calibration = listOf(doubleArrayOf(0.5, 0.51, 100.0)))
        }
        return ModelBundle("test", "2026-01-01", "2005-01-01", "2026-01-01", 1, 1, hs)
    }

    private fun featuresAndModel() {
        val c = synthetic(600)
        val s = Series(c)
        val ms = market(700)
        val x = Features.build(s, s.last, ms.at(s.dates[s.last]))
        check("featuresSize", x != null && x.size == Features.SIZE) { "size=${x?.size}" }
        check("featuresFinite", x != null && x.all { it.isOk() }) { x?.toList().toString() }
        check("featuresNullEarly", Features.build(s, 100, null) == null)
        val m = dummyModel()
        check("modelCompatible", m.compatible)
        val p = m.horizon(10)!!.probability(x!!, 0.0)!!
        check("probRange", p in 0.0..1.0) { "$p" }
        val h = m.horizon(10)!!
        near("calibInterp", h.calibrate(0.25), 0.555, 1e-12)
        near("calibClampLo", h.calibrate(-9.0), 0.45, 1e-12)
        near("calibClampHi", h.calibrate(9.0), 0.64, 1e-12)
        val g = dummyModel("gbdt").horizon(10)!!
        val xx = DoubleArray(Features.SIZE); xx[Features.NAMES.indexOf("leader_pb")] = 1.0; xx[Features.NAMES.indexOf("ret5")] = -0.02
        near("gbdtRaw", g.raw(xx, 0.0), 0.2 + 0.3 + 0.05, 1e-12)
        val attr = g.contributions(xx)
        near("gbdtAttr", attr[Features.NAMES.indexOf("leader_pb")], 0.3, 1e-12)
        val rm = dummyModel("rules").horizon(10)!!
        near("rulesKind", rm.probability(null, 1.0), 0.62, 1e-12)
        check("rulesNoFeatures", !rm.needsFeatures)
        check("call", m.horizon(10)!!.call(0.70) == Call.STRONG_BUY && m.horizon(10)!!.call(0.40) == Call.AVOID && m.horizon(10)!!.call(0.55) == Call.HOLD)
        val band = m.horizon(10)!!.band(100.0, 0.02)
        check("band", band.first < 100 && band.second > 100 && abs(band.first * band.second - 10000) < 1e-6) { "$band" }
    }

    private fun portfolioMath() {
        val nok = PositionInput("KMAR.OL", 500.0, null, 80.0, 79.0, "NOK", 1.0, "Industrials")
        val usd = PositionInput("MU", 13.0, 984.0, 1000.0, 990.0, "USD", 10.0, "Technology")
        val gbp = PositionInput("STBO.XC", 335.0, null, 250.0, 250.0, "GBp", 13.5, null)
        val vals = listOf(nok, usd, gbp).map { Portfolio.value(it) }
        near("gbpMinor", vals[2].valueBase, 335 * 2.5 * 13.5, 1e-6)
        val t = Portfolio.totals(vals)
        near("total", t.valueBase, 500 * 80.0 + 13 * 1000.0 * 10 + 335 * 2.5 * 13.5, 1e-6)
        check("costedOnly", t.costedCount == 1)
        near("pnl", t.pnlBase, 13 * (1000.0 - 984.0) * 10, 1e-6)
        near("pnlPct", t.pnlPct, (1000.0 - 984.0) / 984.0 * 100, 1e-9)
        val missing = Portfolio.totals(listOf(Portfolio.value(usd.copy(fxToBase = null))))
        check("unpriced", missing.unpriced == listOf("MU") && missing.valueBase == 0.0)
    }

    private fun holdingsText() {
        val (ok, bad) = HoldingsText.parse("GOOGL 10, NVDA 21\nMU 13 @ 984\nKMAR.OL 500;bogus line here x;STBO.XC 10,5")
        check("parseCount", ok.size == 5) { "$ok / $bad" }
        check("parseCost", ok.firstOrNull { it.symbol == "MU" }?.avgCost == 984.0)
        check("parseDecimalComma", ok.firstOrNull { it.symbol == "STBO.XC" }?.shares == 10.5) { "$ok" }
        check("parseBad", bad.size == 1)
        check("symbols", HoldingsText.symbols("tsla, msft aapl") == listOf("TSLA", "MSFT", "AAPL")) { "${HoldingsText.symbols("tsla, msft aapl")}" }
    }

    private fun trackRecord() {
        val c = synthetic(60)
        val s = Series(c)
        val logged = LoggedCall("X", c[30].date, c[30].close, "BUY", 0.6, 0.6, 0.6, 0.55, "t")
        val g = TrackRecord.grade(logged, s)
        near("ret10", g.ret10, c[40].close / c[30].close - 1, 1e-12)
        check("ret22 pending", g.ret22 != null) // 30 + 22 = 52 < 60
        val late = TrackRecord.grade(logged.copy(date = c[52].date), s)
        check("immature not graded", late.ret5 != null && late.ret10 == null && late.ret22 == null) { "$late" }
        val sum = TrackRecord.summarize(listOf(g, late))
        check("summary", sum.logged == 2 && sum.matured10 == 1)
    }

    private fun rulesAndAnalyzer() {
        val c = synthetic(520)
        val ms = market(600)
        val a = Analyzer.analyze("TEST", c, ms, null)
        check("analysisRules", a != null && a.forecasts.size == 3 && a.forecasts.all { it.probability == null })
        val b = Analyzer.analyze("TEST", c, ms, dummyModel())
        check("analysisModel", b != null && b.forecasts.all { it.probability != null } && b.verdict.probability != null) { "${b?.verdict}" }
        check("summary", b != null && b.summary.bullets.isNotEmpty())
        check("levelsClassified", b != null && b.levels.all { (it.type == LevelType.SUPPORT) == (it.price < b.lastClose) })
        // Unscored info signals must not dilute the score.
        val ind = Technical.snapshot(Series(c))
        val r = Rules.evaluate(ind.copy(goldenCross = true, deathCross = true), emptyList(), ms.latest(), null)
        val r2 = Rules.evaluate(ind.copy(goldenCross = false, deathCross = false), emptyList(), ms.latest(), null)
        near("noDilution", r.rawScore, r2.rawScore, 1e-12)
        check("short history ok", Analyzer.analyze("NEW", c.take(80), ms, dummyModel())?.forecasts?.all { it.probability == null } == true)
        // A rules-kind model works even with short history (no feature vector needed).
        check("rules model short history", Analyzer.analyze("NEW", c.take(80), ms, dummyModel("rules"))?.forecasts?.all { it.probability != null } == true)
        val gb = Analyzer.analyze("TEST", c, ms, dummyModel("gbdt"))
        check("gbdt analysis", gb != null && gb.forecasts.all { it.probability != null })
    }

    private fun jsonRoundTrip() {
        val m = dummyModel()
        val back = ModelBundle.fromJson(m.toJson())
        check("jsonVersion", back.version == "test" && back.horizons.size == 3)
        check("jsonCoef", back.horizon(10)!!.coef.contentEquals(m.horizon(10)!!.coef))
        val gj = ModelBundle.fromJson(dummyModel("gbdt").toJson()).horizon(10)!!
        check("jsonTrees", gj.kind == "gbdt" && gj.trees.size == 2 && gj.trees[0].value.contentEquals(dummyModel("gbdt").horizon(10)!!.trees[0].value))
        check("jsonStats", back.horizon(10)!!.stats["BUY"]?.n == 1200)
        val parsed = MiniJson.parse("""{"a":[1,2.5,-3e2,null,true],"b":{"c":"x\"yå"}}""").obj()
        check("jsonParse", parsed["b"].obj()["c"] == "x\"yå" && (parsed["a"] as List<*>)[2] == -300.0)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val path = args.firstOrNull() ?: "engine/src/test/resources/ti_reference.json"
        val fails = runAll(java.io.File(path).readText())
        if (fails.isNotEmpty()) kotlin.system.exitProcess(1)
    }
}
