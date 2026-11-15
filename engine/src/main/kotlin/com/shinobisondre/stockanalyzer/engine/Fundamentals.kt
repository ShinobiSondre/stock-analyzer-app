package com.shinobisondre.stockanalyzer.engine

/** Normalised fundamentals (Yahoo quoteSummary). Every field optional. */
data class Fundamentals(
    val price: Double? = null,
    val targetMeanPrice: Double? = null,
    val recommendationKey: String? = null,
    val recommendationMean: Double? = null,
    val numberOfAnalysts: Int? = null,
    val trailingPE: Double? = null,
    val forwardPE: Double? = null,
    val pegRatio: Double? = null,
    val priceToBook: Double? = null,
    val revenueGrowth: Double? = null,
    val earningsGrowth: Double? = null,
    val profitMargins: Double? = null,
    val returnOnEquity: Double? = null,
    val debtToEquity: Double? = null,
    val freeCashflow: Double? = null,
    val marketCap: Double? = null,
    val avgVolume: Double? = null,
    val fiftyTwoWeekLow: Double? = null,
    val fiftyTwoWeekHigh: Double? = null,
    val fiftyDayAverage: Double? = null,
    val twoHundredDayAverage: Double? = null,
    val beta: Double? = null,
    val dividendYield: Double? = null,
    val sector: String? = null,
    val industry: String? = null,
    val description: String? = null,
)

data class QualityScore(
    val score: Double, val tier: String, val verdict: String,
    val reasons: List<String>, val riskFlags: List<String>, val upsidePct: Double?, val hardAvoid: Boolean,
)

/**
 * The web app's medium-term "quality" screen (topPotential.scorePotential).
 * It is a heuristic, not a measured signal — the web app's own test of
 * earnings growth found no standalone forward edge — so the app shows it as
 * context and never feeds it into the probability model.
 */
object Quality {
    fun score(f: Fundamentals): QualityScore {
        var score = 0.0
        val reasons = mutableListOf<String>()
        val flags = mutableListOf<String>()
        val price = f.price
        val rec = f.recommendationMean
        val profitable = f.profitMargins != null && f.profitMargins > 0
        val upside = if (price != null && price > 0 && f.targetMeanPrice != null) (f.targetMeanPrice - price) / price * 100 else null
        val fcfNeg = f.freeCashflow != null && f.freeCashflow < 0
        val highLev = f.debtToEquity != null && f.debtToEquity > 150
        val cashBurn = fcfNeg && !profitable
        val micro = f.marketCap != null && f.marketCap < 2e9
        val illiquid = f.avgVolume != null && f.avgVolume < 300_000
        val analystBear = rec != null && rec >= 3.5
        val vs50 = if (price != null && f.fiftyDayAverage != null && f.fiftyDayAverage > 0) (price - f.fiftyDayAverage) / f.fiftyDayAverage * 100 else null
        val vs200 = if (price != null && f.twoHundredDayAverage != null && f.twoHundredDayAverage > 0) (price - f.twoHundredDayAverage) / f.twoHundredDayAverage * 100 else null

        rec?.let {
            when {
                it <= 1.8 -> { score += 2.5; reasons += "Analysts: strong buy (${fmt(it, 1)})" }
                it <= 2.4 -> { score += 1.5; reasons += "Analysts: buy (${fmt(it, 1)})" }
                it <= 2.9 -> { score += 0.5; reasons += "Analysts: modest buy (${fmt(it, 1)})" }
                it <= 3.4 -> { score -= 0.5; reasons += "Analysts: hold (${fmt(it, 1)})" }
                else -> { score -= 2; flags += "Analyst consensus: sell" }
            }
        }
        upside?.let { u ->
            val credible = profitable || (rec != null && rec <= 2.6)
            when {
                u > 25 && credible -> { score += 2; reasons += "${u.toInt()}% upside to analyst target" }
                u > 10 && credible -> { score += 1; reasons += "${u.toInt()}% upside to analyst target" }
                u > 25 -> { score += 0.3; flags += "${u.toInt()}% target upside but unprofitable" }
                u < -5 -> { score -= 1; reasons += "Trading above analyst target" }
            }
        }
        f.profitMargins?.let {
            when {
                it > 0.20 -> { score += 1.5; reasons += "Strong margins (${(it * 100).toInt()}%)" }
                it > 0.05 -> { score += 0.7; reasons += "Profitable (${(it * 100).toInt()}% margin)" }
                it <= 0 -> { score -= 1.5; flags += "Unprofitable" }
            }
        }
        if (f.returnOnEquity != null && f.returnOnEquity > 0.15) { score += 0.5; reasons += "High ROE (${(f.returnOnEquity * 100).toInt()}%)" }
        f.earningsGrowth?.let {
            if (it > 0.15) { score += 1; reasons += "Earnings growing ${(it * 100).toInt()}%" }
            else if (it < -0.10) { score -= 1; flags += "Earnings shrinking" }
        }
        if (f.revenueGrowth != null && f.revenueGrowth > 0.10) { score += 0.5; reasons += "Revenue +${(f.revenueGrowth * 100).toInt()}%" }
        val pe = f.trailingPE?.takeIf { it > 0 } ?: f.forwardPE?.takeIf { it > 0 }
        pe?.let {
            when {
                it < 18 && profitable -> { score += 1.2; reasons += "Attractive P/E (${fmt(it, 1)})" }
                it < 18 -> {}
                it < 30 -> { score += 0.5; reasons += "Fair P/E (${fmt(it, 1)})" }
                it > 80 -> { score -= 0.7; flags += "Rich valuation (P/E ${it.toInt()})" }
            }
        }
        if (f.pegRatio != null && f.pegRatio > 0 && f.pegRatio < 1.5 && profitable) { score += 0.5; reasons += "Reasonable PEG (${fmt(f.pegRatio)})" }
        if (price != null && f.fiftyTwoWeekLow != null && f.fiftyTwoWeekHigh != null && f.fiftyTwoWeekHigh > f.fiftyTwoWeekLow) {
            val pos = (price - f.fiftyTwoWeekLow) / (f.fiftyTwoWeekHigh - f.fiftyTwoWeekLow)
            val stabilising = vs50 != null && vs50 > -3
            when {
                pos < 0.35 && profitable && !analystBear && stabilising -> { score += 1.5; reasons += "Beaten-down quality name stabilising" }
                pos < 0.35 -> flags += "Near 52-week low and still weak — possible value trap"
                pos > 0.90 -> { score -= 0.3; reasons += "Extended near 52-week high" }
            }
        }
        vs200?.let { if (it > 5) { score += 0.8; reasons += "Above 200-day average" } else if (it < -15) { score -= 1; flags += "Well below 200-day average" } }
        if (vs50 != null && vs50 < -12) { score -= 0.8; flags += "Sharp drop below 50-day average" }
        if (cashBurn) { score -= 1.5; flags += "Burning cash (negative FCF, unprofitable)" }
        else if (fcfNeg) { score -= 0.5; flags += "Negative free cash flow" }
        if (highLev) { score -= 0.7; flags += "High leverage (D/E ${f.debtToEquity!!.toInt()})" }
        if (illiquid) { score -= 1; flags += "Thin liquidity" }
        if (micro) flags += "Small-cap — higher risk"

        val hardAvoid = analystBear || cashBurn || (illiquid && micro)
        val (tier, verdict) = when {
            hardAvoid -> "Avoid" to (if (analystBear) "Avoid — analysts negative" else if (cashBurn) "Avoid — cash-burning business" else "Avoid — illiquid small-cap")
            score >= 5 && profitable -> "Quality" to "Quality compounder"
            score >= 3 && profitable -> "Solid" to "Solid risk/reward"
            score >= 3 -> "Speculative" to "Higher-risk opportunity — size small"
            score >= 1 -> "Watch" to "Marginal — watch, don't chase"
            else -> "Avoid" to "Weak fundamentals"
        }
        return QualityScore(round(score, 1) ?: 0.0, tier, verdict, reasons.take(5), flags.take(4), upside, hardAvoid)
    }
}
