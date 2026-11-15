package com.shinobisondre.stockanalyzer.engine

import kotlin.math.abs
import kotlin.math.roundToInt

data class Driver(val key: String, val label: String, val logOdds: Double)

data class HorizonForecast(
    val days: Int,
    val label: String,
    /** Calibrated P(higher after [days] sessions); null when only the rules engine ran. */
    val probability: Double?,
    val baseRate: Double?,
    val call: Call,
    /** What happened, out of sample, after past calls with this label. */
    val stat: LabelStat?,
    val low: Double?,
    val high: Double?,
    val drivers: List<Driver>,
    val targetDate: String,
) {
    val edge: Double? get() = if (probability != null && baseRate != null) probability - baseRate else null
}

data class Verdict(val call: Call, val headline: String, val description: String, val horizonDays: Int, val probability: Double?, val baseRate: Double?)

data class HoldPoint(val days: Int, val date: String, val probability: Double?, val isBest: Boolean)
data class HoldingGuidance(val bestDays: Int, val reviewDate: String, val points: List<HoldPoint>, val note: String)

class Analysis(
    val symbol: String,
    val series: Series,
    val indicators: IndicatorSnapshot,
    val patterns: List<Pattern>,
    val levels: List<Level>,
    val tyler: TylerResult,
    val rules: RulesResult,
    val forecasts: List<HorizonForecast>,
    val verdict: Verdict,
    val holding: HoldingGuidance?,
    val market: MarketPoint?,
    val features: DoubleArray?,
    val modelVersion: String?,
    val summary: Summary,
) {
    val lastDate: String get() = indicators.date
    val lastClose: Double get() = indicators.lastClose
    fun forecast(days: Int) = forecasts.firstOrNull { it.days == days }
}

object Analyzer {
    val HORIZONS = listOf(5 to "1 week", 10 to "2 weeks", 22 to "1 month")
    const val PRIMARY = 10

    /** Full analysis at the last bar. Returns null with fewer than 30 bars. */
    fun analyze(
        symbol: String,
        candles: List<Candle>,
        market: MarketSeries?,
        model: ModelBundle?,
        newsScore: Double = 0.0,
    ): Analysis? {
        if (candles.size < 30) return null
        val s = Series(candles)
        val i = s.last
        val ind = Technical.snapshot(s, i)
        val pats = Technical.patterns(s, i)
        val lv = Technical.levels(s, i)
        val tyler = Tyler.full(candles, s, newsScore)
        val m = market?.at(s.dates[i])
        val rules = Rules.evaluate(ind, pats, m, tyler)
        val usable = model?.takeIf { it.compatible }
        // The model always sees news-free Tyler reads — that is how it was trained.
        val x = if (usable != null && usable.horizons.any { it.needsFeatures }) Features.build(s, i, m, Tyler.read(s, i)) else null
        val vol = ind.dailyVol

        val forecasts = HORIZONS.map { (days, label) ->
            val hm = usable?.horizon(days)
            val target = TradingDays.addTradingDays(s.dates[i], days)
            val p = hm?.probability(x, rules.rawScore)
            if (hm != null && p != null) {
                val call = hm.call(p)
                val band = vol?.let { hm.band(ind.lastClose, it) }
                val drivers = if (hm.needsFeatures && x != null) {
                    val contrib = hm.contributions(x)
                    contrib.indices
                        .map { Driver(Features.DEFS[it].key, Features.DEFS[it].label, contrib[it]) }
                        .filter { abs(it.logOdds) > 0.002 }
                        .sortedByDescending { abs(it.logOdds) }
                        .take(6)
                } else {
                    rules.signals.filter { it.scored && it.score != 0.0 }
                        .sortedByDescending { abs(it.score * it.weight) }
                        .take(6)
                        .map { Driver(it.name, "${it.name}: ${it.label}", it.score * it.weight / 10) }
                }
                HorizonForecast(days, label, p, hm.baseRate, call, hm.stats[call.name], band?.first, band?.second, drivers, target)
            } else {
                // Rules fallback: same call for every horizon, ATR-free vol band (±1.28σ√h).
                val band = vol?.let { v -> val w = 1.2816 * v * kotlin.math.sqrt(days.toDouble()); ind.lastClose * kotlin.math.exp(-w) to ind.lastClose * kotlin.math.exp(w) }
                HorizonForecast(days, label, null, null, rules.call, null, band?.first, band?.second, emptyList(), target)
            }
        }

        val fromModel = forecasts.first { it.days == PRIMARY }.probability != null
        val primary = forecasts.first { it.days == PRIMARY }
        val verdict = verdictOf(primary, rules, fromModel)
        val holding = holdingGuidance(forecasts, primary.call)
        val summary = Summaries.build(symbol, ind, pats, lv, tyler, rules, m, primary)
        return Analysis(symbol, s, ind, pats, lv, tyler, rules, forecasts, verdict, holding, m, x, usable?.version, summary)
    }

    private fun verdictOf(f: HorizonForecast, rules: RulesResult, fromModel: Boolean): Verdict {
        if (!fromModel || f.probability == null) {
            return Verdict(rules.call, "${rules.call.text} — classic indicator read", rules.description, f.days, null, null)
        }
        val p = (f.probability * 100).roundToInt()
        val base = ((f.baseRate ?: 0.5) * 100).roundToInt()
        val headline = "$p% chance higher in ${f.label} (typical stock: $base%)"
        val parts = mutableListOf<String>()
        when {
            f.call.bullish -> parts += "The model sees better-than-average odds."
            f.call == Call.HOLD -> parts += "No meaningful edge over a typical stock right now."
            f.call == Call.AVOID -> parts += "Odds are below the typical stock's, but historically calls like this still rose about as often as they fell — lag, not crash."
            else -> parts += "Odds of a decline are higher than usual."
        }
        f.stat?.let { st ->
            if (st.n >= 200) parts += "Out of sample, ${st.n.fmtThousands()} past ${f.call.text} calls were higher after ${f.days} sessions ${(st.upRate * 100).roundToInt()}% of the time (avg ${pct(st.meanRetPct)})."
        }
        val up = f.drivers.filter { it.logOdds > 0 }.take(2).joinToString(", ") { it.label.lowercase() }
        val dn = f.drivers.filter { it.logOdds < 0 }.take(2).joinToString(", ") { it.label.lowercase() }
        if (up.isNotEmpty()) parts += "Helping: $up."
        if (dn.isNotEmpty()) parts += "Hurting: $dn."
        rules.setups.firstOrNull { it.kind == SetupKind.FALLING_KNIFE }?.let { parts += "Caution: ${it.detail}" }
        return Verdict(f.call, headline, parts.joinToString(" "), f.days, f.probability, f.baseRate)
    }

    private fun holdingGuidance(fs: List<HorizonForecast>, call: Call): HoldingGuidance? {
        if (!call.bullish) return null
        val withP = fs.filter { it.edge != null }
        // Pick the horizon with the biggest edge over a typical stock; on a tie
        // (within half a point) prefer the shorter one — capital is freed sooner.
        val bestEdge = withP.maxOfOrNull { it.edge!! }
        val best = (if (bestEdge != null) withP.first { it.edge!! >= bestEdge - 0.005 } else null)
            ?: fs.first { it.days == PRIMARY }
        val pts = fs.map { HoldPoint(it.days, it.targetDate, it.probability, it.days == best.days) }
        val note = if (best.edge != null) {
            "The edge is largest around ${best.label} (${pct(best.edge!! * 100)} vs a typical stock). Re-check on ${best.targetDate}; nothing here is a hard exit date."
        } else "Re-check on ${best.targetDate}."
        return HoldingGuidance(best.days, best.targetDate, pts, note)
    }
}

internal fun Int.fmtThousands(): String = "%,d".format(this)
