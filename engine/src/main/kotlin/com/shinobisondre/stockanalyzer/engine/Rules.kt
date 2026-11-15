package com.shinobisondre.stockanalyzer.engine

import kotlin.math.roundToInt

/**
 * The web app's hand-weighted rules engine, kept as (a) a transparent
 * "classic indicator" read and (b) the fallback when no trained model is
 * available. Bugs fixed relative to models/predictor.js:
 *
 *  • Signals described as "shown, not scored" (golden/death cross, suppressed
 *    fear) used to carry weight 0.5 with score 0 — they still dragged every
 *    score toward zero. They now have weight 0.
 *  • News sentiment voted with weight 0.75 although the app's own learning
 *    loop measured headline sentiment at 26% directional accuracy. It is now
 *    context only.
 *  • The catalyst nudge had market mood (VIX / Fear & Greed) added into it,
 *    double-counting the fear signal. Catalysts are context only now
 *    (untestable — no history).
 *  • The bearish quality gate stopped at −1.2 while WEAK SELL runs to −1.3.
 *  • predictTimeframes() skipped the quality gate and the STRONG BUY
 *    definition, so the 1-week call could say BUY while the headline said
 *    HOLD. One function now produces one call.
 */
data class WeightedSignal(val name: String, val label: String, val score: Double, val weight: Double) {
    val scored get() = weight > 0
}

enum class SetupKind { LEADER_PULLBACK, FEAR_SPIKE, FEAR_DIP, INDEX_PULLBACK, FALLING_KNIFE, FEAR_SUPPRESSED }

data class Setup(val kind: SetupKind, val title: String, val detail: String, val tone: Tone, val evidence: String)

data class RelStrength(val ret12_1: Double, val benchmark: Double?, val leader: Boolean, val laggard: Boolean) {
    val label: String get() {
        val vs = benchmark?.let { " vs S&P 500 ${pct(it * 100, 0)}" } ?: ""
        val tag = if (leader) "relative-strength leader" else if (laggard) "laggard" else "in line with the market"
        return "12-1 month return ${pct(ret12_1 * 100, 0)}$vs — $tag"
    }
}

data class RulesResult(
    val rawScore: Double,
    val call: Call,
    val description: String,
    val signals: List<WeightedSignal>,
    val veto: String?,
    val setups: List<Setup>,
    val relStrength: RelStrength?,
) {
    val displayScore: Int get() = (rawScore * 5).coerceIn(-10.0, 10.0).roundToInt()
}

object Rules {

    fun relStrength(ind: IndicatorSnapshot, benchmark: Double?): RelStrength? {
        val r = ind.ret12_1 ?: return null
        return if (benchmark != null) RelStrength(r, benchmark, r - benchmark > 0.10, r - benchmark < -0.15)
        else RelStrength(r, null, r > 0.20, r < -0.05)
    }

    fun setups(ind: IndicatorSnapshot, m: MarketPoint?, rs: RelStrength?): List<Setup> {
        val out = mutableListOf<Setup>()
        val rsi = ind.rsi
        val above = ind.aboveSma200 == true
        val below = ind.aboveSma200 == false
        val vix = m?.vix
        val idc = m?.indexDeathCross == true
        if (above && rs?.leader == true && rsi != null && rsi < 40)
            out += Setup(SetupKind.LEADER_PULLBACK, "Leader pullback",
                "Pullback (RSI ${rsi.roundToInt()}) in a relative-strength leader that is still above its 200-day.", Tone.BUY,
                "61.8% higher after 10 sessions vs 55.1% base (n≈10,000, 2005–2026, web app walk-forward).")
        if (vix != null && vix > 25 && !idc && above && rsi != null && rsi < 50)
            out += Setup(SetupKind.FEAR_SPIKE, "Fear spike, trend intact",
                "VIX ${fmt(vix, 1)}, this stock pulled back (RSI ${rsi.roundToInt()}) but holds its 200-day.", Tone.STRONG_BUY,
                "~64% higher after 10 sessions, ~67% after 22 (n≈6,800; held up on 83 unseen tickers).")
        else if (vix != null && vix in 20.0..25.0 && !idc && above && rsi != null && rsi < 40)
            out += Setup(SetupKind.FEAR_DIP, "Elevated VIX + dip",
                "VIX ${fmt(vix, 1)} with this name pulled back inside its uptrend.", Tone.BUY,
                "Fear-buying strengthens with time (61.8% @5d → 67.5% @22d for VIX > 25).")
        else if (m?.fearTier == FearTier.PULLBACK && !idc && above && rsi != null && rsi < 45)
            out += Setup(SetupKind.INDEX_PULLBACK, "Index pullback",
                "S&P 500 down ${fmt(m.spyDrawdownPct, 1)}% from its high while this name holds its 200-day.", Tone.BUY,
                "Measured +2% excess at 10 days in the web app's sample (smaller n).")
        if (vix != null && vix > 25 && idc)
            out += Setup(SetupKind.FEAR_SUPPRESSED, "Fear without a floor",
                "VIX ${fmt(vix, 1)} but the S&P 500 is in a death cross — fear-buying had no edge in that tape (2008, 2022).", Tone.NEUTRAL,
                "Suppressed: 45.9% hit rate in the 2008 bear.")
        if (below && rsi != null && rsi < 40 && (rs == null || rs.laggard))
            out += Setup(SetupKind.FALLING_KNIFE, "Falling knife",
                "A laggard below its 200-day with RSI under 40 — historically not a reversal.", Tone.SELL,
                "53.6% @10d, below the 55.1% base rate.")
        return out
    }

    fun evaluate(ind: IndicatorSnapshot, patterns: List<Pattern>, m: MarketPoint?, tyler: TylerResult?): RulesResult {
        val sig = mutableListOf<WeightedSignal>()
        val rsi = ind.rsi
        val above = ind.aboveSma200 == true
        val rs = relStrength(ind, m?.spyRet12_1)
        val setups = setups(ind, m, rs)

        // One vote for the collinear oscillator family (RSI, Stochastic, %R).
        val osc = listOfNotNull(ind.rsi?.let { ind.rsiRead.score }, ind.stochK?.let { ind.stochRead.score }, ind.williamsR?.let { ind.willRRead.score })
        if (osc.isNotEmpty()) {
            val s = osc.average()
            val label = when { s > 1 -> "Strongly oversold (bullish)"; s > 0.25 -> "Leaning bullish"; s < -1 -> "Strongly overbought"; s < -0.25 -> "Leaning bearish"; else -> "Neutral" }
            sig += WeightedSignal("Momentum (RSI/Stoch/%R)", label, s, 2.5)
        }
        if (ind.macd != null) sig += WeightedSignal("MACD", ind.macdRead.label, ind.macdRead.score, 0.75)
        if (ind.sma20 != null) sig += WeightedSignal("Moving averages", ind.maRead.label, ind.maRead.score, 0.5)
        if (ind.goldenCross) sig += WeightedSignal("Golden cross", "No measurable edge over 21 years — shown, not scored", 0.0, 0.0)
        if (ind.deathCross) sig += WeightedSignal("Death cross", "Historically a late/contrarian signal — shown, not scored", 0.0, 0.0)
        if (rs != null) sig += WeightedSignal("Relative strength", rs.label, if (rs.leader) 1.0 else if (rs.laggard) -1.5 else 0.0, 1.5)
        if (setups.any { it.kind == SetupKind.LEADER_PULLBACK })
            sig += WeightedSignal("Leader pullback", "Pullback in a relative-strength leader above its 200-day", 2.0, 4.0)
        setups.firstOrNull { it.kind == SetupKind.FEAR_SPIKE }?.let { sig += WeightedSignal("Market fear (high)", it.detail, 2.0, 4.0) }
        setups.firstOrNull { it.kind == SetupKind.FEAR_DIP }?.let { sig += WeightedSignal("Market fear (dip)", it.detail, 2.0, 4.0) }
        setups.firstOrNull { it.kind == SetupKind.INDEX_PULLBACK }?.let { sig += WeightedSignal("Market fear (index pullback)", it.detail, 1.5, 3.0) }
        setups.firstOrNull { it.kind == SetupKind.FEAR_SUPPRESSED }?.let { sig += WeightedSignal("Market fear (suppressed)", it.detail, 0.0, 0.0) }
        if (ind.bbUpper != null) sig += WeightedSignal("Bollinger bands", ind.bbRead.label, ind.bbRead.score, 0.5)
        if (ind.adx != null) sig += WeightedSignal("ADX trend", ind.adxRead.label, ind.adxRead.score, 0.25)
        ind.obvBullish?.let { sig += WeightedSignal("OBV", if (it) "Accumulation" else "Distribution", if (it) 0.8 else -0.8, 0.25) }
        if (ind.volumeRatio != null) sig += WeightedSignal("Volume", ind.volumeRead.label, ind.volumeRead.score, 0.25)
        patterns.filter { it.barsAgo == 0 }.maxByOrNull { kotlin.math.abs(it.score) }?.let {
            sig += WeightedSignal("Pattern: ${it.name}", it.description, it.score.coerceIn(-1.5, 1.5), 0.25)
        }
        when (tyler?.verdict) {
            StackVerdict.FULL_SELL -> sig += WeightedSignal("Tyler 4-stack (sell)", StackVerdict.FULL_SELL.message, -2.5, 2.0)
            StackVerdict.TRIM -> sig += WeightedSignal("Tyler 3-stack (trim)", StackVerdict.TRIM.message, -1.2, 1.0)
            StackVerdict.FULL_BUY -> sig += WeightedSignal("Tyler 4-stack (buy)", StackVerdict.FULL_BUY.message, 2.5, 2.0)
            StackVerdict.ACCUMULATE -> sig += WeightedSignal("Tyler 3-stack (accumulate)", StackVerdict.ACCUMULATE.message, 1.2, 1.0)
            else -> {}
        }

        val tw = sig.sumOf { it.weight }
        val score = if (tw > 0) sig.sumOf { it.score * it.weight } / tw else 0.0

        // Quality gate (asymmetric, as measured): bearish calls need more proof.
        val below = ind.aboveSma200 == false
        val macdBear = ind.macdRead.score < 0
        val oversold = rsi != null && rsi < 40
        val knife = below && oversold && (rs == null || rs.laggard)
        var veto: String? = null
        if (score > 0 && score < 1.2 && knife)
            veto = "Downtrend guard: a relative-strength laggard below its 200-day with RSI under 40 — the falling-knife pattern, not a reversal."
        else if (score < 0 && score > -1.3) {
            if (oversold) veto = "Oversold-rally guard: RSI below 40 — historically the worst place to sell."
            else if (above && !macdBear) veto = "Bearish reading, but price is above its 200-day and MACD has not rolled over — selling into an intact uptrend lost money in testing."
        }

        var call = when {
            score >= 1.2 -> Call.STRONG_BUY
            score >= 0.8 -> Call.BUY
            score >= 0.6 -> Call.WEAK_BUY
            score > -0.85 -> Call.HOLD
            score > -1.30 -> Call.WEAK_SELL
            else -> Call.SELL
        }
        var desc = when (call) {
            Call.STRONG_BUY, Call.BUY -> "Most weighted signals point up."
            Call.WEAK_BUY -> "Bullish lean with limited conviction."
            Call.HOLD -> "Mixed or conflicting signals — no clear directional bias."
            Call.WEAK_SELL -> "Bearish lean — treat as trim rather than exit."
            else -> "Most weighted signals point down."
        }
        if (veto != null && call != Call.HOLD && call != Call.STRONG_BUY) {
            desc = "$veto Original call: ${call.text}."
            call = Call.HOLD
        }
        // STRONG BUY is defined by the measured fear setup, not by a stacked score.
        val conviction = veto == null && score > 0 && setups.any { it.kind == SetupKind.FEAR_SPIKE }
        if (conviction && (call == Call.BUY || call == Call.WEAK_BUY)) call = Call.STRONG_BUY
        else if (!conviction && call == Call.STRONG_BUY) call = Call.BUY
        if (conviction) desc = "Fear spike with the stock pulled back, trend intact — historically ~64% over 10 days. $desc"

        return RulesResult(score, call, desc, sig, veto, setups, rs)
    }
}
