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
        brokerScreenshots()
        dayAfter()
        tyler()
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

    /** The Nordnet app's Holdings tab (English UI, NOK account), laid out as in a real screenshot. */
    private fun nordnetHoldings(): List<OcrLine> {
        val out = mutableListOf(
            OcrLine("22:25", 100, 35, 200, 75), OcrLine("IKZ", 640, 150, 690, 185), OcrLine("Investeringskonto Zero", 710, 145, 1045, 190),
            OcrLine("Holdings", 38, 278, 170, 318), OcrLine("Details", 226, 278, 328, 318), OcrLine("Transactions", 386, 278, 574, 318),
            OcrLine("Stocks", 76, 370, 180, 405),
        )
        val rows = listOf(
            listOf("Alphabet A", "28,293 kr", "-21.75%", "0%", "295.77 USD"),
            listOf("Apple", "13,632 kr", "+9.64%", "0%", "356.27 USD"),
            listOf("Eli Lilly", "13,822 kr", "+15.81%", "0%", "1,445 USD"),
            listOf("Kongsberg Maritime", "29,250 kr", "+3.54%", "0%", "58.50 NOK"),
            listOf("Mobileye Global A", "5,744 kr", "-42.64%", "0%", "5.56 USD"),
            listOf("NVIDIA", "66,984 kr", "-12.12%", "0%", "162.85 USD"),
        )
        rows.forEachIndexed { k, r ->
            val y = 460 + k * 240
            out += OcrLine(r[0], 181, y, 181 + r[0].length * 22, y + 40)
            if (r[0] == "Eli Lilly") out += OcrLine("Lilly", 90, y + 5, 140, y + 30) // text inside the logo
            out += listOf(OcrLine("Value", 76, y + 80, 145, y + 112), OcrLine("Return", 476, y + 80, 557, y + 112),
                OcrLine("Today", 876, y + 80, 949, y + 112), OcrLine("Latest", 1517, y + 80, 1593, y + 112))
            out += listOf(OcrLine(r[1], 76, y + 125, 199, y + 160), OcrLine(r[2], 476, y + 125, 584, y + 160),
                OcrLine(r[3], 876, y + 125, 916, y + 160), OcrLine(r[4], 1593 - r[4].length * 16, y + 125, 1593, y + 160))
        }
        out += listOf(OcrLine("Sort", 123, 1900, 186, 1935), OcrLine("wan Semiconductor Manufacturing ADR", 224, 1895, 812, 1940),
            OcrLine("Overview", 111, 2105, 224, 2135), OcrLine("Watchlists", 438, 2105, 564, 2135), OcrLine("Discover", 783, 2105, 889, 2135),
            OcrLine("Feed", 1140, 2105, 1199, 2135), OcrLine("Market", 1460, 2105, 1544, 2135))
        return out.shuffled(java.util.Random(3))
    }

    private fun brokerScreenshots() {
        near("num nordic", BrokerScreenshot.parseNumber("1 234,56", ','), 1234.56, 1e-9)
        near("num english", BrokerScreenshot.parseNumber("28,293", '.'), 28293.0, 1e-9)
        near("num english dec", BrokerScreenshot.parseNumber("295.77", '.'), 295.77, 1e-9)
        near("num minus", BrokerScreenshot.parseNumber("−3,2", ','), -3.2, 1e-9)
        near("num mixed", BrokerScreenshot.parseNumber("1.234,5", ','), 1234.5, 1e-9)
        check("decimal mark", BrokerScreenshot.decimalMarkOf(listOf("28,293 kr", "295.77 USD", "-21.75%")) == '.')
        check("decimal mark nordic", BrokerScreenshot.decimalMarkOf(listOf("28 293 kr", "295,77 USD", "-21,75 %")) == ',')

        val h = BrokerScreenshot.parse(nordnetHoldings())
        check("nordnet count", h.size == 6) { h.joinToString("\n") }
        val g = h.firstOrNull { it.name == "Alphabet A" }
        check("nordnet alphabet", g != null && g.marketValue == 28293.0 && g.lastPrice == 295.77 && g.lastCurrency == "USD" && g.valueCurrency == "NOK" && g.returnPct == -21.75) { "$g" }
        check("nordnet shares", g?.shares(9.566) == 10.0) { "${g?.shares(9.566)}" }
        near("nordnet implied cost", g?.impliedAvgCost(), 295.77 / (1 - 0.2175), 1e-9)
        val km = h.firstOrNull { it.name == "Kongsberg Maritime" }
        check("nordnet nok listing", km?.shares(1.0) == 500.0 && km.lastCurrency == "NOK") { "$km" }
        check("nordnet logo merged", h.any { it.name == "Eli Lilly" } && h.none { it.name == "Lilly" })
        check("nordnet lilly 1445", h.firstOrNull { it.name == "Eli Lilly" }?.lastPrice == 1445.0)
        check("nordnet mobileye", h.firstOrNull { it.name == "Mobileye Global A" }?.shares(9.566) == 108.0) { "${h.firstOrNull { it.name == "Mobileye Global A" }}" }
        check("nordnet no noise", h.none { it.name in setOf("IKZ", "Sort", "Overview", "Stocks") || it.name.contains("Semiconductor") }) { h.map { it.name }.toString() }

        // Norwegian table layout with a header row and explicit quantity / GAV.
        val table = listOf(
            OcrLine("Navn", 40, 200, 120, 230), OcrLine("Antall", 420, 200, 520, 230), OcrLine("GAV", 560, 200, 620, 230),
            OcrLine("Siste", 700, 200, 780, 230), OcrLine("Markedsverdi", 860, 200, 1040, 230),
            OcrLine("Micron Technology", 40, 260, 330, 290), OcrLine("13", 450, 260, 480, 290), OcrLine("984,00", 560, 260, 650, 290),
            OcrLine("1 012,40", 690, 260, 800, 290), OcrLine("13 161,20", 900, 260, 1040, 290),
            OcrLine("Kongsberg Maritime", 40, 320, 340, 350), OcrLine("500", 440, 320, 490, 350), OcrLine("80,10", 700, 320, 790, 350),
            OcrLine("40 050,00", 900, 320, 1040, 350),
        )
        val t = BrokerScreenshot.parse(table)
        val mu = t.firstOrNull { it.name == "Micron Technology" }
        check("table micron", mu != null && mu.quantity == 13.0 && mu.avgCost == 984.0 && mu.lastPrice == 1012.4 && mu.marketValue == 13161.2) { "$t" }
        check("table kongsberg", t.firstOrNull { it.name == "Kongsberg Maritime" }?.quantity == 500.0) { "$t" }

        // Stacked app layout with "stk" and inline GAV.
        val stacked = listOf(
            OcrLine("NVIDIA", 40, 450, 220, 495), OcrLine("31 210,00 NOK", 700, 450, 1040, 495),
            OcrLine("21 stk · GAV 120,50", 40, 500, 420, 535), OcrLine("−4,1 %", 880, 500, 1040, 535),
        )
        val st = BrokerScreenshot.parse(stacked).firstOrNull()
        check("stacked", st != null && st.quantity == 21.0 && st.avgCost == 120.5 && st.returnPct == -4.1) { "$st" }
        check("search queries", BrokerScreenshot.searchQueries("Airbnb, Inc. - Class A").contains("Airbnb")) { BrokerScreenshot.searchQueries("Airbnb, Inc. - Class A").toString() }
    }

    private fun dayAfter() {
        val base = DayStat(100000, 0.52, 0.05, 0.53, 0.02, 0.55, 0.3)
        val fade = DayStat(900, 0.45, -0.40, 0.48, -0.10, 0.50, -0.2)
        val run = DayStat(800, 0.57, 0.45, 0.62, 0.30, 0.60, 1.1)
        val coarse = DayStat(5000, 0.51, 0.02, 0.55, 0.05, 0.55, 0.4)
        val t = DayAfterTable(base, mapOf(
            DayAfter.key(1, 2, 0, 1) to fade,          // +5–8%, 3× volume, faded, above 200-day
            DayAfter.key(1, null, 2, null) to run,     // +5–8%, closed near the high
            DayAfter.key(1, null, null, null) to coarse,
            DayAfter.key(0, 0, 1, 0) to DayStat(50, 0.9, 3.0, 0.9, 1.0, 0.9, 5.0), // too small — must be skipped
        ))
        val a = DayAfter.advise(t, 6.0, 3.5, 0.3, true, 0.6)
        check("eod sell", a?.lean == EodLean.SELL_BY_CLOSE && a.stat == fade) { "$a" }
        val b = DayAfter.advise(t, 6.0, 1.0, 0.9, false, 0.6)
        check("eod hold", b?.lean == EodLean.HOLD_OVERNIGHT && b.stat == run) { "$b" }
        val c = DayAfter.advise(t, 6.0, 1.0, 0.6, false, 0.6)
        check("eod coarse", c?.lean == EodLean.NO_EDGE && c.stat == coarse) { "$c" }
        check("eod small n skipped", DayAfter.lookup(t, 4.0, 1.0, 0.6, false) == null)
        check("eod below threshold", DayAfter.advise(t, 2.0, 1.0, 0.5, true, 0.6) == null)
        check("eod keys", DayAfter.keysFor(1, 2, 0, 1).size == 16 && DayAfter.keysFor(1, 2, 0, 1).contains("*|*|*|*"))
        val book = DayAfterBook("now", "2026-11-13", t, null)
        val back = DayAfterBook.fromJson(book.toJson())
        check("eod json", back.us.buckets[DayAfter.key(1, null, 2, null)] == run && back.oslo == null && back.table("EQNR.OL") === back.us)
        near("clv", DayAfter.clv(105.0, 100.0, 110.0), 0.5, 1e-12)
    }

    private fun tyler() {
        val feed = """<?xml version="1.0"?><feed><title>Tyler Hill Stocks</title>
            <entry><id>yt:video:abcDEF12345</id><yt:videoId>abcDEF12345</yt:videoId><title>I&#39;m Selling NVDA &amp; Buying This</title>
            <link rel="alternate" href="https://www.youtube.com/watch?v=abcDEF12345"/><published>2026-11-14T15:00:12+00:00</published>
            <media:group><media:description>My 4 sell signals…</media:description></media:group></entry>
            <entry><yt:videoId>short000001</yt:videoId><title>quick one</title><link rel="alternate" href="https://www.youtube.com/shorts/short000001"/><published>2026-11-13T15:00:00+00:00</published></entry>
            </feed>"""
        val v = YouTubeParse.feed(feed)
        check("yt feed", v.size == 2 && v[0].id == "abcDEF12345" && v[0].title == "I'm Selling NVDA & Buying This" && !v[0].short && v[1].short) { "$v" }
        check("yt feed desc", v[0].description.startsWith("My 4 sell"))

        val player = """{"playabilityStatus":{"status":"OK"},"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
            {"baseUrl":"https://www.youtube.com/api/timedtext?v=x&lang=en&kind=asr","languageCode":"en","kind":"asr"},
            {"baseUrl":"https://www.youtube.com/api/timedtext?v=x&lang=de","languageCode":"de"}]}},
            "videoDetails":{"title":"T","shortDescription":"D","channelId":"UC-FwZq0rYfc2SzYkpoNBJGg"},
            "microformat":{"playerMicroformatRenderer":{"publishDate":"2026-11-14"}}}"""
        val pi = YouTubeParse.player(player, plain = true)
        check("yt player", pi != null && pi.tracks.size == 2 && pi.status == "OK" && pi.publishDate == "2026-11-14")
        check("yt pick asr en", YouTubeParse.pickTrack(pi!!.tracks)?.let { it.first.lang == "en" && it.second == null } == true)
        check("yt pick translate", YouTubeParse.pickTrack(listOf(CaptionTrack("u", "es", true, true)))?.second == "en")

        val xml = """<?xml version="1.0"?><transcript><text start="0.1" dur="2">so I think NVIDIA is going</text><text start="2.1" dur="2">to &amp;amp;#39;rip&amp;amp;#39; to 200</text></transcript>"""
        check("yt xml captions", YouTubeParse.captions(xml) == listOf("so I think NVIDIA is going", "to 'rip' to 200")) { YouTubeParse.captions(xml).toString() }
        val json3 = """{"events":[{"tStartMs":0,"segs":[{"utf8":"hello"},{"utf8":" world"}]},{"tStartMs":10},{"tStartMs":20,"segs":[{"utf8":"\n"}]}]}"""
        check("yt json3", YouTubeParse.captions(json3) == listOf("hello world"))
        check("yt page key", YouTubeParse.apiKey("""ytcfg.set({"INNERTUBE_API_KEY": "AIzaXYZ_123"})""") == "AIzaXYZ_123")
        check("yt extract", YouTubeParse.extractJson("""var ytInitialPlayerResponse = {"a":{"b":"}"}};var x""", "ytInitialPlayerResponse") == """{"a":{"b":"}"}}""")

        check("tickers alias", TylerTalk.tickersIn("I think Nvidia and Taiwan Semi go higher, \$PLTR too").containsAll(listOf("NVDA", "TSM", "PLTR")))
        check("tickers caps", TylerTalk.tickersIn("Why I'm Selling NVDA Now", caps = true) == listOf("NVDA"))
        check("clean", TylerTalk.clean(listOf("[Music]", "so", "so", "I think  this")) == "so I think this")
        val talk = "welcome back guys so today we are going to look at the market " +
            "I think Nvidia is going to pull back to around 160 dollars where the 50 day sits and that is my buy zone " +
            "make sure to subscribe and check the link in the description for the sponsor " +
            "the S&P 500 RSI is above 70 with bearish divergence so I'm trimming 10 percent of my Palantir position this week"
        val key = TylerTalk.keyLines(talk)
        check("key lines", key.any { it.contains("160") } && key.none { it.contains("subscribe") }) { key.toString() }

        val answer = "Sure! ```json\n{\"headline\":\"He expects a pullback before year end\",\"stance\":\"Bearish\",\"market\":\"S&P 500 to pull back 5-8%\"," +
            "\"calls\":[{\"ticker\":\"\",\"name\":\"Nvidia\",\"view\":\"bullish\",\"what\":\"buy at 160\",\"levels\":\"160\",\"timeframe\":\"weeks\",\"action\":\"watching\"}," +
            "{\"ticker\":\"PLTR\",\"name\":\"Palantir\",\"view\":\"bearish\",\"what\":\"overextended\",\"levels\":\"empty\",\"timeframe\":\"\",\"action\":\"trimming\"}]," +
            "\"moves\":\"Trimmed 10% of PLTR\",\"watch\":\"RSI divergence\",\"caveats\":\"\"}\n```"
        val take = TylerTalk.parseAnswer(answer, v[0], key, "ai:test", 120, 1L)
        check("take parse", take != null && take.stance == "bearish" && take.calls.size == 2 && take.calls[0].ticker == "NVDA" && take.calls[1].levels == "" && take.calls[1].action == "trimming") { "$take" }
        val back = TylerTake.fromMap(MiniJson.parse(MiniJson.write(take!!.toMap())).let { @Suppress("UNCHECKED_CAST") (it as Map<String, Any?>) })
        check("take json", back == take)
        check("take latestOn", TylerTalk.latestOn(listOf(take), "NVDA")?.second?.view == "bullish" && TylerTalk.latestOn(listOf(take), "AAPL") == null)
        check("take bad answer", TylerTalk.parseAnswer("I can't help with that", v[0], key, "ai:test", 1, 1L) == null)
        val q = TylerTalk.quotesOnly(v[0], talk, 1L)
        check("quotes only", q.source == "quotes" && q.calls.any { it.ticker == "NVDA" } && q.keyLines.isNotEmpty())
        check("fit short", TylerTalk.fit(talk) == talk)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val path = args.firstOrNull() ?: "engine/src/test/resources/ti_reference.json"
        val fails = runAll(java.io.File(path).readText())
        if (fails.isNotEmpty()) kotlin.system.exitProcess(1)
    }
}
