package com.shinobisondre.stockanalyzer.engine

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How a company is connected to the one in the middle of the tree. */
enum class Link(val title: String, val plain: String) {
    MAKERS("Makes its products", "builds or supplies what it sells"),
    BUYERS("Buys from it", "its big customers"),
    RIDERS("Rides the same wave", "grows when its market grows"),
    RIVALS("Competes with it", "fights for the same customers"),
    PEERS("Similar companies", "same kind of business"),
}

data class RippleNode(val symbol: String, val link: Link)

/** How closely one stock has moved with the leader (last ~2 years of daily closes). */
data class RippleStat(
    val symbol: String,
    val days: Int,
    /** "When the leader rises 1%, this rises about [beta]% the same day." */
    val beta: Double,
    val corr: Double,
    /** The leader's best days (top 10%): how often this one was up too, and by how much on average. */
    val bigDays: Int,
    val upOnBigDays: Double,
    val avgOnBigDaysPct: Double,
    /** The 5 sessions after those best days: average move of this one (small sample — context only). */
    val next5AfterBigPct: Double?,
) {
    val strength: Strength get() = Strength.of(corr)
    /** Ordering: how much of the leader's move tends to carry over. */
    val score: Double get() = corr * beta.coerceIn(-3.0, 3.0)
}

enum class Strength(val label: String, val tone: Tone, val bars: Int) {
    TIGHT("Moves closely with it", Tone.STRONG_BUY, 4),
    OFTEN("Often moves with it", Tone.BUY, 3),
    LOOSE("Loosely linked", Tone.NEUTRAL, 2),
    OWN("Goes its own way", Tone.NEUTRAL, 1),
    OPPOSITE("Tends to move the other way", Tone.SELL, 1);

    companion object {
        fun of(corr: Double) = when {
            corr >= 0.6 -> TIGHT; corr >= 0.4 -> OFTEN; corr >= 0.2 -> LOOSE; corr > -0.2 -> OWN; else -> OPPOSITE
        }
    }
}

/**
 * "If NVIDIA keeps doing well, what else tends to go up?" — a map of who supplies,
 * buys from, rides with or competes with a company, plus measured co-movement so
 * the tree shows what actually moved together, not just a story.
 */
object Ripple {
    private fun m(vararg pairs: Pair<Link, List<String>>): List<RippleNode> = pairs.flatMap { (l, s) -> s.map { RippleNode(it, l) } }

    /** Hand-made map for the stocks people ask about most (US tickers; Oslo with .OL). */
    val MAP: Map<String, List<RippleNode>> = mapOf(
        "NVDA" to m(Link.MAKERS to listOf("TSM", "MU", "ASML", "AMAT", "LRCX"), Link.BUYERS to listOf("MSFT", "META", "GOOGL", "AMZN", "ORCL"),
            Link.RIDERS to listOf("SMCI", "DELL", "VRT", "ANET", "CEG"), Link.RIVALS to listOf("AMD", "AVGO", "INTC", "MRVL")),
        "AMD" to m(Link.MAKERS to listOf("TSM", "MU", "ASML"), Link.BUYERS to listOf("MSFT", "META", "ORCL", "DELL"),
            Link.RIDERS to listOf("SMCI", "VRT", "ANET"), Link.RIVALS to listOf("NVDA", "INTC", "AVGO", "ARM")),
        "TSM" to m(Link.MAKERS to listOf("ASML", "AMAT", "LRCX", "KLAC"), Link.BUYERS to listOf("NVDA", "AAPL", "AMD", "AVGO", "QCOM"),
            Link.RIVALS to listOf("INTC", "UMC", "GFS")),
        "MU" to m(Link.MAKERS to listOf("AMAT", "LRCX", "KLAC"), Link.BUYERS to listOf("NVDA", "AMD", "DELL", "AAPL"),
            Link.RIDERS to listOf("WDC", "STX", "SNDK"), Link.RIVALS to listOf("INTC")),
        "AVGO" to m(Link.MAKERS to listOf("TSM"), Link.BUYERS to listOf("GOOGL", "META", "AAPL"), Link.RIDERS to listOf("ANET", "MRVL", "VRT"),
            Link.RIVALS to listOf("NVDA", "AMD", "QCOM")),
        "AAPL" to m(Link.MAKERS to listOf("TSM", "AVGO", "QCOM", "SWKS", "GLW", "CRUS"), Link.RIVALS to listOf("GOOGL", "MSFT"),
            Link.RIDERS to listOf("SPOT", "NFLX")),
        "MSFT" to m(Link.MAKERS to listOf("NVDA", "AMD", "MU"), Link.RIVALS to listOf("GOOGL", "AMZN", "ORCL", "CRM"),
            Link.RIDERS to listOf("NOW", "SNOW", "CRWD", "VRT")),
        "GOOGL" to m(Link.MAKERS to listOf("AVGO", "TSM", "NVDA"), Link.RIVALS to listOf("META", "MSFT", "AMZN", "AAPL"),
            Link.RIDERS to listOf("TTD", "RDDT", "PINS")),
        "GOOG" to m(Link.MAKERS to listOf("AVGO", "TSM", "NVDA"), Link.RIVALS to listOf("META", "MSFT", "AMZN", "AAPL"),
            Link.RIDERS to listOf("TTD", "RDDT", "PINS")),
        "META" to m(Link.MAKERS to listOf("NVDA", "AVGO", "AMD"), Link.RIVALS to listOf("GOOGL", "SNAP", "PINS", "RDDT"),
            Link.RIDERS to listOf("TTD", "APP")),
        "AMZN" to m(Link.MAKERS to listOf("NVDA", "AVGO"), Link.RIVALS to listOf("WMT", "SHOP", "MSFT", "GOOGL", "COST"),
            Link.RIDERS to listOf("UPS", "FDX")),
        "TSLA" to m(Link.MAKERS to listOf("ALB", "ON", "NXPI", "PCRFY"), Link.RIVALS to listOf("RIVN", "LCID", "NIO", "BYDDY", "GM", "F"),
            Link.RIDERS to listOf("CHPT", "FSLR", "ENPH")),
        "MBLY" to m(Link.MAKERS to listOf("INTC", "TSM"), Link.BUYERS to listOf("GM", "F", "STLA", "VWAGY"),
            Link.RIVALS to listOf("NVDA", "QCOM", "AMBA"), Link.RIDERS to listOf("TSLA", "UBER")),
        "PLTR" to m(Link.BUYERS to listOf("LMT", "NOC", "GD"), Link.RIVALS to listOf("SNOW", "AI", "MSFT"), Link.RIDERS to listOf("CRWD", "NOW", "DDOG")),
        "LLY" to m(Link.RIVALS to listOf("NVO", "AMGN", "VKTX", "PFE"), Link.MAKERS to listOf("TMO", "DHR"), Link.RIDERS to listOf("HIMS", "WW")),
        "NVO" to m(Link.RIVALS to listOf("LLY", "AMGN", "VKTX"), Link.MAKERS to listOf("TMO", "DHR"), Link.RIDERS to listOf("HIMS")),
        "JPM" to m(Link.PEERS to listOf("BAC", "WFC", "C", "GS", "MS"), Link.RIDERS to listOf("V", "MA", "AXP")),
        "UNH" to m(Link.PEERS to listOf("CVS", "CI", "ELV", "HUM", "CNC"), Link.MAKERS to listOf("HCA", "LLY")),
        "SPY" to m(Link.PEERS to listOf("QQQ", "DIA", "IWM"), Link.RIDERS to listOf("AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META")),
        "EQNR.OL" to m(Link.PEERS to listOf("AKRBP.OL", "VAR.OL", "OKEA.OL", "SHEL", "XOM"), Link.MAKERS to listOf("SUBC.OL", "TGS.OL", "AKSO.OL", "DOFG.OL"),
            Link.RIDERS to listOf("BZ=F")),
        "KOG.OL" to m(Link.PEERS to listOf("RHM.DE", "SAAB-B.ST", "LMT", "NOC", "RTX"), Link.RIDERS to listOf("NAS.OL", "KMAR.OL")),
        "KMAR.OL" to m(Link.PEERS to listOf("WRT1V.HE", "KOG.OL"), Link.BUYERS to listOf("FRO.OL", "HAFNI.OL", "WAWI.OL", "MPCC.OL")),
        "NHY.OL" to m(Link.PEERS to listOf("AA", "RIO", "CENX"), Link.RIDERS to listOf("ALI=F")),
        "MOWI.OL" to m(Link.PEERS to listOf("SALM.OL", "LSG.OL", "GSF.OL", "BAKKA.OL")),
        "DNB.OL" to m(Link.PEERS to listOf("SB1NO.OL", "NONG.OL", "MING.OL", "SWED-A.ST", "NDA-FI.HE")),
    )

    /** Leaders worth a quick tap in the picker. */
    val POPULAR = listOf("NVDA", "AAPL", "MSFT", "TSLA", "AMD", "TSM", "META", "AMZN", "GOOGL", "LLY", "EQNR.OL", "KOG.OL")

    fun nodesFor(symbol: String): List<RippleNode>? = MAP[symbol.uppercase()]

    /** Daily returns on the dates both stocks traded, oldest first. */
    fun alignedReturns(leader: List<Candle>, follower: List<Candle>, lookback: Int = 504): Pair<DoubleArray, DoubleArray> {
        val f = HashMap<String, Double>(follower.size * 2)
        for (c in follower) if (c.adjClose > 0) f[c.date] = c.adjClose
        val common = leader.filter { it.adjClose > 0 && f.containsKey(it.date) }.takeLast(lookback + 1)
        val n = common.size - 1
        if (n < 2) return DoubleArray(0) to DoubleArray(0)
        val l = DoubleArray(n); val r = DoubleArray(n)
        for (i in 0 until n) {
            l[i] = common[i + 1].adjClose / common[i].adjClose - 1
            r[i] = f[common[i + 1].date]!! / f[common[i].date]!! - 1
        }
        return l to r
    }

    fun stats(symbol: String, leader: List<Candle>, follower: List<Candle>, lookback: Int = 504): RippleStat? {
        val (l, r) = alignedReturns(leader, follower, lookback)
        val n = l.size
        if (n < 120) return null
        // Ignore glitchy prints (splits not adjusted, bad ticks).
        val ok = (0 until n).filter { abs(l[it]) < 0.4 && abs(r[it]) < 0.4 }
        val ml = ok.sumOf { l[it] } / ok.size; val mr = ok.sumOf { r[it] } / ok.size
        var cov = 0.0; var vl = 0.0; var vr = 0.0
        for (i in ok) { cov += (l[i] - ml) * (r[i] - mr); vl += (l[i] - ml) * (l[i] - ml); vr += (r[i] - mr) * (r[i] - mr) }
        if (vl <= 0 || vr <= 0) return null
        val beta = cov / vl
        val corr = cov / sqrt(vl * vr)
        // The leader's best days: top 10% of its daily moves.
        val sorted = ok.map { l[it] }.sorted()
        val cut = sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)]
        val big = ok.filter { l[it] >= cut && l[it] > 0 }
        val up = big.count { r[it] > 0 }.toDouble() / big.size.coerceAtLeast(1)
        val avg = big.sumOf { r[it] } / big.size.coerceAtLeast(1) * 100
        val fwd = big.filter { it + 5 < n }.map { i -> (1..5).fold(1.0) { acc, k -> acc * (1 + r[i + k]) } - 1 }
        return RippleStat(symbol, ok.size, beta, corr, big.size, up, avg, if (fwd.size >= 10) fwd.average() * 100 else null)
    }

    /** One plain sentence for a beginner. */
    fun explain(leader: String, s: RippleStat): String {
        val l = leader.removeSuffix(".OL"); val f = s.symbol.removeSuffix(".OL")
        val times = (s.upOnBigDays * 100).roundToInt()
        return when (s.strength) {
            Strength.OPPOSITE -> "When $l goes up, $f has more often gone down."
            Strength.OWN -> "$f mostly does its own thing — $l's moves say little about it."
            else -> "When $l rises 1%, $f has risen about ${"%.1f".format(s.beta.coerceAtLeast(0.0))}% the same day. On $l's best days, $f was up too $times times out of 100."
        }
    }
}
