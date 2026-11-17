package com.shinobisondre.stockanalyzer.engine

/** A call the app made, logged once per symbol per trading day. */
data class LoggedCall(
    val symbol: String,
    val date: String,
    val close: Double,
    val call: String,
    val p5: Double?,
    val p10: Double?,
    val p22: Double?,
    val base10: Double?,
    val modelVersion: String?,
)

data class GradedCall(val logged: LoggedCall, val ret5: Double?, val ret10: Double?, val ret22: Double?)

data class TrackBucket(val n: Int, val up: Int, val meanRetPct: Double) {
    val upRate: Double get() = if (n > 0) up.toDouble() / n else Double.NaN
}

data class TrackSummary(
    val logged: Int,
    val matured10: Int,
    val byCall: Map<String, TrackBucket>,
    val all10: TrackBucket,
    val brier10: Double?,
    val brierBase10: Double?,
)

/**
 * Honest grading of the app's own past calls. Fixes vs backtest.js:
 *  • horizons are counted in TRADING sessions (the walk-forward's unit), not
 *    calendar days — "22 days" used to mean ~15 sessions;
 *  • a horizon that has not matured yet is NOT scored — backtest.js fell back
 *    to the latest price, grading 10- and 22-day calls after a few days;
 *  • every call is graded on the same yardstick as training: was the close
 *    higher h sessions later (no ±1% "flat" band that discarded misses).
 */
object TrackRecord {
    fun grade(c: LoggedCall, s: Series): GradedCall {
        val i = s.indexOfDate(c.date)
        if (i < 0) return GradedCall(c, null, null, null)
        fun r(h: Int): Double? = if (i + h <= s.last && s.close[i] > 0) s.close[i + h] / s.close[i] - 1 else null
        return GradedCall(c, r(5), r(10), r(22))
    }

    fun summarize(graded: List<GradedCall>): TrackSummary {
        val mature = graded.filter { it.ret10 != null }
        fun bucket(list: List<GradedCall>): TrackBucket {
            val rets = list.mapNotNull { it.ret10 }
            return TrackBucket(rets.size, rets.count { it > 0 }, if (rets.isEmpty()) 0.0 else rets.average() * 100)
        }
        val byCall = mature.groupBy { it.logged.call }.mapValues { bucket(it.value) }
        val withP = mature.filter { it.logged.p10 != null }
        val brier = if (withP.isEmpty()) null else withP.map { val y = if (it.ret10!! > 0) 1.0 else 0.0; (it.logged.p10!! - y).let { d -> d * d } }.average()
        val baseRate = if (withP.isEmpty()) null else withP.count { it.ret10!! > 0 }.toDouble() / withP.size
        val brierBase = baseRate?.let { b -> withP.map { val y = if (it.ret10!! > 0) 1.0 else 0.0; (b - y) * (b - y) }.average() }
        return TrackSummary(graded.size, mature.size, byCall, bucket(mature), brier, brierBase)
    }
}

/** Currency codes Yahoo quotes in minor units (pence, cents, agorot). */
object Money {
    fun normalize(code: String?): Pair<String, Double> = when (code) {
        null -> "USD" to 1.0
        "GBp", "GBX" -> "GBP" to 0.01
        "ZAc" -> "ZAR" to 0.01
        "ILA" -> "ILS" to 0.01
        else -> code.uppercase() to 1.0
    }
}

data class PositionInput(
    val symbol: String,
    val shares: Double,
    /** Average cost per share in the instrument's quote currency (null = unknown). */
    val avgCost: Double?,
    val price: Double?,
    val prevClose: Double?,
    val currency: String?,
    /** Units of base currency per one unit of the (normalised) quote currency. */
    val fxToBase: Double?,
    val sector: String?,
    /** What one share actually cost in the base currency (exchange rate on the day + fees), from imported trades. */
    val costPerShareBase: Double? = null,
)

data class PositionValue(
    val input: PositionInput,
    val valueBase: Double?,
    val costBase: Double?,
    val pnlBase: Double?,
    val pnlPct: Double?,
    val dayChangeBase: Double?,
    var weightPct: Double? = null,
)

data class SectorSlice(val name: String, val valueBase: Double, val pct: Double)

data class PortfolioTotals(
    val valueBase: Double,
    val dayChangeBase: Double,
    val dayChangePct: Double?,
    /** P/L is only computed over positions that have a cost basis. */
    val costedCount: Int,
    val costBase: Double,
    val costedValueBase: Double,
    val pnlBase: Double?,
    val pnlPct: Double?,
    val unpriced: List<String>,
    val sectors: List<SectorSlice>,
    val concentrated: List<SectorSlice>,
)

/**
 * Portfolio arithmetic. Fixes vs portfolio.js:
 *  • values are converted to ONE base currency before summing (the web app
 *    added NOK, GBp and USD positions together as if they were the same);
 *  • minor-unit quotes (GBp) are divided by 100;
 *  • total P/L compares the value of the positions that HAVE a cost basis
 *    with that cost — the web app compared the value of every position with
 *    the cost of only the ones that had one, reporting a huge fake gain.
 */
object Portfolio {
    fun value(p: PositionInput): PositionValue {
        val (_, mult) = Money.normalize(p.currency)
        val fx = p.fxToBase
        if (p.price == null || fx == null) return PositionValue(p, null, null, null, null, null)
        val value = p.shares * p.price * mult * fx
        // Real cost from trades (historical FX and fees) when known; otherwise the average price at today's rate.
        val cost = p.costPerShareBase?.takeIf { it > 0 }?.let { p.shares * it } ?: p.avgCost?.takeIf { it > 0 }?.let { p.shares * it * mult * fx }
        val pnl = cost?.let { value - it }
        val pnlPct = when {
            cost != null && cost > 0 && p.costPerShareBase != null && p.costPerShareBase > 0 -> (value / cost - 1) * 100
            p.avgCost != null && p.avgCost > 0 -> (p.price - p.avgCost) / p.avgCost * 100
            else -> null
        }
        val day = p.prevClose?.let { p.shares * (p.price - it) * mult * fx }
        return PositionValue(p, value, cost, pnl, pnlPct, day)
    }

    fun totals(values: List<PositionValue>, concentrationPct: Double = 40.0): PortfolioTotals {
        val priced = values.filter { it.valueBase != null }
        val total = priced.sumOf { it.valueBase!! }
        priced.forEach { it.weightPct = if (total > 0) it.valueBase!! / total * 100 else null }
        val costed = priced.filter { it.costBase != null }
        val cost = costed.sumOf { it.costBase!! }
        val costedValue = costed.sumOf { it.valueBase!! }
        val pnl = if (costed.isNotEmpty()) costedValue - cost else null
        val day = priced.sumOf { it.dayChangeBase ?: 0.0 }
        val prevTotal = total - day
        val sectors = priced.groupBy { it.input.sector ?: "Unknown" }
            .map { (k, v) -> val sv = v.sumOf { it.valueBase!! }; SectorSlice(k, sv, if (total > 0) sv / total * 100 else 0.0) }
            .sortedByDescending { it.valueBase }
        return PortfolioTotals(
            valueBase = total,
            dayChangeBase = day,
            dayChangePct = if (prevTotal > 0) day / prevTotal * 100 else null,
            costedCount = costed.size,
            costBase = cost,
            costedValueBase = costedValue,
            pnlBase = pnl,
            pnlPct = if (cost > 0 && pnl != null) pnl / cost * 100 else null,
            unpriced = values.filter { it.valueBase == null }.map { it.input.symbol },
            sectors = sectors,
            concentrated = sectors.filter { it.pct > concentrationPct && it.name != "Unknown" },
        )
    }
}

/** Parses "AAPL 10 @ 182.5, MSFT 3" / one-per-line holdings text. */
object HoldingsText {
    data class Parsed(val symbol: String, val shares: Double, val avgCost: Double?)
    private val SYMBOL = Regex("^[A-Z0-9^][A-Z0-9.\\-=^]{0,15}$")

    fun parse(text: String): Pair<List<Parsed>, List<String>> {
        val ok = mutableListOf<Parsed>()
        val bad = mutableListOf<String>()
        // Separators: newline, ';', or a comma that is followed by the next symbol
        // (so a Norwegian decimal comma like "10,5" survives).
        for (raw in text.split(Regex("\\n|;|,(?=\\s*[A-Za-z^])"))) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val parts = line.replace("@", " @ ").split(Regex("\\s+")).filter { it.isNotEmpty() }
            val sym = parts.getOrNull(0)?.uppercase()
            val shares = parts.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
            val at = parts.indexOf("@")
            val cost = if (at >= 0) parts.getOrNull(at + 1)?.replace(',', '.')?.toDoubleOrNull() else null
            if (sym != null && SYMBOL.matches(sym) && shares != null && shares > 0) ok += Parsed(sym, shares, cost)
            else bad += line
        }
        return ok to bad
    }

    /** Splits a symbol list on commas or whitespace (fix for predictionStore.normalizeSymbols, whose regex split on the letter "s"). */
    fun symbols(text: String): List<String> =
        text.split(Regex("[,\\s]+")).map { it.trim().uppercase() }.filter { SYMBOL.matches(it) }.distinct()
}
