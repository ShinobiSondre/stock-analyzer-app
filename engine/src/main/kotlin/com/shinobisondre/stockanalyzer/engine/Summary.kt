package com.shinobisondre.stockanalyzer.engine

import kotlin.math.abs
import kotlin.math.roundToInt

data class Bullet(val category: String, val title: String, val text: String, val tone: Tone)
data class Summary(val bullets: List<Bullet>, val opportunities: List<String>, val risks: List<String>)

/** Plain-English read of the analysis for non-experts (port of summaryGenerator.js, corrected). */
object Summaries {

    fun build(
        symbol: String,
        ind: IndicatorSnapshot,
        patterns: List<Pattern>,
        levels: List<Level>,
        tyler: TylerResult,
        rules: RulesResult,
        m: MarketPoint?,
        primary: HorizonForecast,
    ): Summary {
        val b = mutableListOf<Bullet>()
        val opp = mutableListOf<String>()
        val risk = mutableListOf<String>()
        val p = ind.lastClose

        // Trend
        val a20 = ind.sma20?.let { p > it }; val a50 = ind.sma50?.let { p > it }; val a200 = ind.sma200?.let { p > it }
        val flags = listOfNotNull(a20, a50, a200)
        when {
            flags.isNotEmpty() && flags.all { it } -> b += Bullet("TREND", "Uptrend",
                "Price is above its 20-, 50-${if (a200 != null) " and 200-" else ""}day averages — buyers have been in control." +
                    if (ind.goldenCross) " A golden cross just formed; widely watched, but it showed no edge over 5–22 days across 21 years." else "", Tone.BUY)
            flags.isNotEmpty() && flags.none { it } -> b += Bullet("TREND", "Downtrend",
                "Price is below its key moving averages — more selling than buying pressure." +
                    if (ind.deathCross) " A death cross just formed; historically it often marked capitulation lows rather than tops." else "", Tone.SELL)
            else -> {
                val names = listOfNotNull("20-day".takeIf { a20 == true }, "50-day".takeIf { a50 == true }, "200-day".takeIf { a200 == true })
                b += Bullet("TREND", "Mixed trend", "Price is above ${if (names.isEmpty()) "none" else names.joinToString(" and ")} of its averages — no dominant direction yet.", Tone.NEUTRAL)
            }
        }
        if (a200 == true) opp += "Still above the 200-day average — the long-term trend is intact."
        if (a200 == false) risk += "Below the 200-day average — long-term trend is down."

        // Momentum
        ind.rsi?.let { r ->
            val txt = when {
                r < 30 -> "RSI ${r.roundToInt()} — oversold. Pullbacks this deep have tended to bounce, especially in stocks that were leading the market."
                r < 45 -> "RSI ${r.roundToInt()} — a pullback zone, not overbought."
                r < 55 -> "RSI ${r.roundToInt()} — neutral; buying and selling pressure are roughly balanced."
                r < 70 -> "RSI ${r.roundToInt()} — firm momentum, not yet stretched."
                else -> "RSI ${r.roundToInt()} — overbought. Note: in 21 years of data this did not predict declines; momentum often persists."
            }
            b += Bullet("MOMENTUM", "Momentum", txt + " MACD: ${ind.macdRead.label.lowercase()}.", ind.rsiRead.tone)
        }

        // Relative strength
        rules.relStrength?.let { rs ->
            b += Bullet("STRENGTH", if (rs.leader) "Market leader" else if (rs.laggard) "Lagging the market" else "In line with the market", rs.label + ".",
                if (rs.leader) Tone.BUY else if (rs.laggard) Tone.SELL else Tone.NEUTRAL)
            if (rs.leader) opp += "Out-performing the S&P 500 over the past year — pullbacks in leaders have the best record."
            if (rs.laggard) risk += "Lagging the S&P 500 — dips in laggards have tended to keep falling."
        }

        // Volatility
        ind.atrPct?.let { atr ->
            val t = when {
                atr > 5 -> "Very volatile: a typical day moves about ${fmt(atr, 1)}%. Size positions small."
                atr > 2.5 -> "Moderately volatile: a typical day moves about ${fmt(atr, 1)}%."
                else -> "Calm: a typical day moves about ${fmt(atr, 1)}%."
            }
            b += Bullet("RISK", "Volatility", t, if (atr > 5) Tone.SELL else Tone.NEUTRAL)
            if (atr > 5) risk += "High daily volatility (${fmt(atr, 1)}% average range)."
        }

        // Levels
        val sup = levels.filter { it.type == LevelType.SUPPORT }.maxByOrNull { it.price }
        val res = levels.filter { it.type == LevelType.RESISTANCE }.minByOrNull { it.price }
        if (sup != null || res != null) {
            val parts = mutableListOf<String>()
            sup?.let { parts += "nearest support ${fmt(it.price)} (${pct(it.distancePct)})" }
            res?.let { parts += "nearest resistance ${fmt(it.price)} (${pct(it.distancePct)})" }
            b += Bullet("LEVELS", "Key levels", parts.joinToString("; ").replaceFirstChar { it.uppercase() } + ".", Tone.NEUTRAL)
            if (sup != null && abs(sup.distancePct) < 2) opp += "Trading just above support at ${fmt(sup.price)}."
            if (res != null && abs(res.distancePct) < 2) risk += "Running into resistance at ${fmt(res.price)}."
        }

        // Volume
        ind.volumeRatio?.let { v ->
            if (v > 1.5 || v < 0.5) b += Bullet("VOLUME", "Volume", "Today's volume is ${fmt(v, 1)}× the 20-day average — ${ind.volumeRead.label.lowercase()}.", ind.volumeRead.tone)
        }

        // Patterns (latest bar only)
        patterns.filter { it.barsAgo == 0 && it.type != Tone.NEUTRAL }.take(1).forEach {
            b += Bullet("PATTERN", it.name, it.description + ". Single-candle patterns are weak evidence on their own.", it.type)
        }

        // Setups
        rules.setups.forEach { s ->
            b += Bullet("SETUP", s.title, s.detail + " " + s.evidence, s.tone)
            if (s.tone.sign > 0) opp += s.title + ": " + s.detail else if (s.tone.sign < 0) risk += s.title + ": " + s.detail
        }

        // Tyler
        if (tyler.verdict != StackVerdict.NEUTRAL) b += Bullet("TYLER", "Tyler ${tyler.verdict.label}", tyler.verdict.message, tyler.verdict.tone)

        // Market
        m?.let { mp ->
            b += Bullet("MARKET", "Market: ${mp.regime.label}", mp.regimeNote(), when (mp.regime) { Regime.BULL -> Tone.BUY; Regime.BEAR -> Tone.SELL; else -> Tone.NEUTRAL })
            if (mp.regime == Regime.BEAR || mp.regime == Regime.HIGH_VOL) risk += "Market regime is ${mp.regime.label.lowercase()} — base rates for every stock are lower."
        }

        // Forecast
        if (primary.probability != null) {
            b += Bullet("FORECAST", "Odds over ${primary.label}",
                "${(primary.probability * 100).roundToInt()}% chance the price is higher in ${primary.days} trading days, versus ${((primary.baseRate ?: 0.5) * 100).roundToInt()}% for a typical stock. " +
                    (primary.low?.let { lo -> "80% of outcomes historically landed between ${fmt(lo)} and ${fmt(primary.high)}." } ?: ""),
                primary.call.tone)
        }
        return Summary(b, opp.distinct().take(5), risk.distinct().take(5))
    }
}
