package com.shinobisondre.stockanalyzer.engine

import kotlin.math.abs
import kotlin.math.roundToInt

enum class PickTier(val title: String, val tone: Tone, val headline: String) {
    BUY("Good to buy", Tone.BUY, "More reasons for than against — a sensible buy to hold for a few months."),
    WATCH("Keep an eye on it", Tone.NEUTRAL, "Some good, some bad — wait for a better moment."),
    AVOID("Stay away for now", Tone.SELL, "More reasons against than for right now."),
}

/** One reason in plain words. [good] true = speaks for buying, false = against, null = just so you know. */
data class PlainReason(val text: String, val good: Boolean?)

/** A headline with its tone (+1 good / −1 bad / 0) and how many hours old it is. */
data class NewsHeadline(val title: String, val sentiment: Int, val ageHours: Double?)

/** Analyst & earnings info that isn't in the fundamentals (optional). */
data class PickExtras(val daysToEarnings: Int? = null, val upgrades30d: Int = 0, val downgrades30d: Int = 0)

data class Pick(
    val symbol: String,
    val name: String,
    val tier: PickTier,
    val score: Double,
    val reasons: List<PlainReason>,
    /** The newest strong headline from the last 6 hours, if any. */
    val breaking: NewsHeadline?,
    val quality: QualityScore?,
    val prob22: Double?,
    val base22: Double?,
)

/**
 * "Which stocks look good to buy?" — the original web app's Top Potential scoring
 * (analysts, profits, growth, price vs profits, trend, debt, cash) plus this app's
 * measured odds, Tyler's checklists, the tested chart setups and the news, all
 * turned into plain sentences someone new to stocks can follow.
 */
object Picks {
    private fun pct(v: Double) = "${v.roundToInt()}%"

    fun evaluate(symbol: String, name: String, f: Fundamentals?, a: Analysis, news: List<NewsHeadline>, extras: PickExtras = PickExtras()): Pick {
        val good = mutableListOf<PlainReason>(); val bad = mutableListOf<PlainReason>(); val info = mutableListOf<PlainReason>()
        var score = 0.0

        // 1) The original Top Potential score (fundamentals).
        val q = f?.let { Quality.score(it.copy(price = it.price ?: a.lastClose)) }
        if (q != null) {
            score += q.score
            f!!
            val price = f.price ?: a.lastClose
            val profitable = f.profitMargins != null && f.profitMargins > 0
            f.recommendationMean?.let {
                when {
                    it <= 1.8 -> good += PlainReason("Almost all the professional analysts who follow it say buy.", true)
                    it <= 2.4 -> good += PlainReason("Most professional analysts who follow it say buy.", true)
                    it >= 3.5 -> bad += PlainReason("Most professional analysts say sell.", false)
                    else -> {}
                }
            }
            if (f.targetMeanPrice != null && price > 0) {
                val up = (f.targetMeanPrice - price) / price * 100
                when {
                    up > 10 && (profitable || (f.recommendationMean ?: 9.0) <= 2.6) -> good += PlainReason("Analysts think it's worth about ${pct(up)} more than today's price.", true)
                    up < -5 -> bad += PlainReason("It already costs more than analysts think it's worth.", false)
                }
            }
            f.profitMargins?.let {
                when {
                    it > 0.20 -> good += PlainReason("Very profitable: it keeps about ${(it * 100).roundToInt()} kr of every 100 kr it sells.", true)
                    it > 0.05 -> good += PlainReason("It makes a profit (${(it * 100).roundToInt()} kr of every 100 kr it sells).", true)
                    it <= 0 -> bad += PlainReason("It's losing money.", false)
                    else -> {}
                }
            }
            f.earningsGrowth?.let {
                if (it > 0.15) good += PlainReason("Its profits grew ${pct(it * 100)} over the last year.", true)
                else if (it < -0.10) bad += PlainReason("Its profits are shrinking.", false)
            }
            f.revenueGrowth?.let { if (it > 0.10) good += PlainReason("Its sales are growing ${pct(it * 100)} a year.", true) }
            val pe = f.trailingPE?.takeIf { it > 0 } ?: f.forwardPE?.takeIf { it > 0 }
            pe?.let {
                when {
                    it < 18 && profitable -> good += PlainReason("Cheap compared with its profits: you pay about ${it.roundToInt()} years of today's profit for it.", true)
                    it > 80 -> bad += PlainReason("Very expensive compared with its profits — a lot of future growth is already in the price.", false)
                    else -> {}
                }
            }
            if (f.freeCashflow != null && f.freeCashflow < 0 && !profitable) bad += PlainReason("It spends more cash than it brings in.", false)
            if (f.debtToEquity != null && f.debtToEquity > 150) bad += PlainReason("It has a lot of debt.", false)
            if (f.marketCap != null && f.marketCap < 2e9) info += PlainReason("A small company — its price can swing a lot.", null)
            if (f.fiftyTwoWeekLow != null && f.fiftyTwoWeekHigh != null && f.fiftyTwoWeekHigh > f.fiftyTwoWeekLow) {
                val pos = (price - f.fiftyTwoWeekLow) / (f.fiftyTwoWeekHigh - f.fiftyTwoWeekLow)
                val vs50 = f.fiftyDayAverage?.let { (price - it) / it * 100 }
                when {
                    pos < 0.35 && profitable && (vs50 ?: 0.0) > -3 -> good += PlainReason("It's well down from its high but has stopped falling — a possible bargain.", true)
                    pos < 0.35 -> bad += PlainReason("It's near its lowest price of the year and still falling — cheap can get cheaper.", false)
                    pos > 0.9 -> info += PlainReason("It's close to its highest price of the year.", null)
                }
            }
        } else info += PlainReason("Company facts (profits, analysts) couldn't be loaded — judged on price action only.", null)

        // 2) The measured odds (1 month), from 20 years of data.
        val f22 = a.forecast(22)
        val p = f22?.probability; val base = f22?.baseRate
        if (p != null && base != null) {
            val edge = (p - base) * 100
            score += (edge * 0.4).coerceIn(-3.0, 3.0)
            val txt = "Of stocks in a situation like this, ${(p * 100).roundToInt()} out of 100 were higher a month later (normal: ${(base * 100).roundToInt()})."
            when {
                edge >= 2 -> good += PlainReason(txt, true)
                edge <= -2 -> bad += PlainReason(txt, false)
                else -> info += PlainReason(txt, null)
            }
        } else score += (a.rules.rawScore / 3).coerceIn(-2.0, 2.0)
        if (a.verdict.call.bearish) score -= 1.0

        // 3) Long-term trend.
        val sma200 = a.series.sma200.lastOrNull()?.takeIf { !it.isNaN() }
        if (sma200 != null && sma200 > 0) {
            val vs = (a.lastClose - sma200) / sma200 * 100
            if (vs > 5) good += PlainReason("The price has been in an uptrend for months.", true)
            else if (vs < -15) bad += PlainReason("The price has been falling for months.", false)
        }

        // 4) Tyler Hill's checklists and the tested chart setups.
        val t = a.tyler
        if (t.buyHits >= 3) { score += 1.0; good += PlainReason("Tyler Hill's buy checklist is ${t.buyHits}/4 ticked: sold off hard, fear is high, selling is drying up.", true) }
        if (t.sellHits >= 3) { score -= 1.5; bad += PlainReason("Tyler Hill's sell checklist is ${t.sellHits}/4 ticked: it looks overheated.", false) }
        for (s in a.rules.setups) when (s.kind) {
            SetupKind.LEADER_PULLBACK -> { score += 1.0; good += PlainReason("A strong stock that just dipped — in the past these dips were often bought back up.", true) }
            SetupKind.FEAR_SPIKE, SetupKind.FEAR_DIP -> { score += 0.5; good += PlainReason("The whole market is scared right now — historically a decent time to buy good companies slowly.", true) }
            SetupKind.FALLING_KNIFE -> { score -= 1.5; bad += PlainReason("It's dropping hard. Trying to catch a falling stock has usually lost money.", false) }
            else -> {}
        }

        // 5) Analysts changing their minds, earnings coming up.
        if (extras.upgrades30d > extras.downgrades30d && extras.upgrades30d >= 2) { score += 0.5; good += PlainReason("${extras.upgrades30d} analysts raised their rating this month.", true) }
        if (extras.downgrades30d > extras.upgrades30d && extras.downgrades30d >= 2) { score -= 0.5; bad += PlainReason("${extras.downgrades30d} analysts cut their rating this month.", false) }
        extras.daysToEarnings?.let { if (it in 0..10) info += PlainReason("It reports results in $it day${if (it == 1) "" else "s"} — the price often jumps or drops 5–10% the day after.", null) }

        // 6) News. Headlines move prices within minutes; the app can only say what's out there.
        val recent = news.filter { (it.ageHours ?: 99.0) <= 48 }
        val net = recent.sumOf { it.sentiment }
        if (recent.size >= 2 && net >= 2) { score += 0.5; good += PlainReason("Recent headlines are mostly positive.", true) }
        if (recent.size >= 2 && net <= -2) { score -= 0.5; bad += PlainReason("Recent headlines are mostly negative.", false) }
        val breaking = news.filter { (it.ageHours ?: 99.0) <= 6 && it.sentiment != 0 }.minByOrNull { it.ageHours ?: 99.0 }
        breaking?.let {
            val age = it.ageHours?.let { h -> if (h < 1) "under an hour ago" else "${h.roundToInt()} h ago" } ?: "just now"
            info += PlainReason("Fresh news ($age): “${it.title}” — sounds ${if (it.sentiment > 0) "good" else "bad"}. Prices usually react within minutes, so part of the move may already have happened.", null)
        }

        val hardAvoid = q?.hardAvoid == true
        val tier = when {
            hardAvoid || score < 1.0 || a.verdict.call == Call.SELL -> PickTier.AVOID
            score >= 4.5 && !a.verdict.call.bearish && (q == null || q.tier == "Quality" || q.tier == "Solid") -> PickTier.BUY
            else -> PickTier.WATCH
        }
        if (hardAvoid) bad.add(0, PlainReason(when {
            (f?.recommendationMean ?: 0.0) >= 3.5 -> "Analysts are negative on it."
            else -> "The business is losing money and burning cash."
        }, false))
        // Strongest reasons first (the ones that decided the verdict), then up to 3 "good to know" lines.
        val main = (if (tier == PickTier.AVOID) bad + good else good + bad).distinctBy { it.text }
        val ordered = (if (tier == PickTier.WATCH) (good.take(3) + bad.take(3) + main).distinctBy { it.text } else main).take(7) + info.take(3)
        return Pick(symbol, name, tier, (score * 10).roundToInt() / 10.0, ordered, breaking, q, p, base)
    }

    /** Hours since a unix time. */
    fun ageHours(timeSec: Long?, nowSec: Long): Double? = timeSec?.let { abs(nowSec - it) / 3600.0 }
}

/**
 * "Sell now, buy back later" for stocks you own. A sell signal is remembered with its
 * price; a buy-back signal only comes after one. Judged on completed daily bars.
 */
object Swing {
    /** Why to sell now (plain words), or null when there's no sell signal. */
    fun sellNow(a: Analysis): String? {
        val t = a.tyler
        val f = a.forecast(10)
        val p = f?.probability; val base = f?.baseRate
        return when {
            t.sellHits >= 3 -> "Tyler Hill's sell checklist is ${t.sellHits}/4 ticked — it looks overheated, and he would take profit here."
            a.verdict.call == Call.SELL && p != null && base != null && p <= base - 0.04 ->
                "The odds have turned against it: only ${(p * 100).roundToInt()} of 100 stocks in this spot were higher 2 weeks later (normal: ${(base * 100).roundToInt()})."
            a.rules.setups.any { it.kind == SetupKind.FALLING_KNIFE } -> "It's dropping hard — stocks falling like this have usually kept falling for a while."
            else -> null
        }
    }

    /** Why to buy back after an earlier sell signal at [soldAt], or null. */
    fun buyBack(a: Analysis, soldAt: Double): String? {
        val t = a.tyler
        val price = a.lastClose
        val below = (1 - price / soldAt) * 100
        val f = a.forecast(10)
        val p = f?.probability; val base = f?.baseRate
        val knife = a.rules.setups.any { it.kind == SetupKind.FALLING_KNIFE }
        return when {
            knife || a.verdict.call.bearish -> null
            t.buyHits >= 3 -> "Tyler Hill's buy checklist is ${t.buyHits}/4 ticked — washed out and fearful, his buy zone." + if (below > 0) " It's ${below.roundToInt()}% below where the sell signal came." else ""
            p != null && base != null && p >= base + 0.03 && price <= soldAt * 1.02 ->
                "The odds look good again (${(p * 100).roundToInt()} of 100 higher in 2 weeks, normal ${(base * 100).roundToInt()})" + if (below > 0) ", and it's ${below.roundToInt()}% cheaper than when the sell signal came." else "."
            below >= 10 -> "It's ${below.roundToInt()}% below where the sell signal came and has stopped falling."
            else -> null
        }
    }
}
