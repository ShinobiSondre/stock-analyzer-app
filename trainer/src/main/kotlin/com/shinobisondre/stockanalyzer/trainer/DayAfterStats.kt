package com.shinobisondre.stockanalyzer.trainer

import com.shinobisondre.stockanalyzer.engine.Candle
import com.shinobisondre.stockanalyzer.engine.DayAfter
import com.shinobisondre.stockanalyzer.engine.DayAfterTable
import com.shinobisondre.stockanalyzer.engine.DayStat
import com.shinobisondre.stockanalyzer.engine.Series
import kotlin.math.abs

/**
 * Next-day behaviour after big up-days (≥ +3% close-to-close), by move size,
 * volume, where it closed in the day's range and the long-term trend. This is
 * what the app's Quick wins tab uses to say "sell by the close" or "hold".
 */
object DayAfterStats {
    private class Acc {
        var n = 0; var upNext = 0; var sumNext = 0.0; var upGap = 0; var sumGap = 0.0; var n5 = 0; var up5 = 0; var sum5 = 0.0
        fun add(next: Double, gap: Double, r5: Double) {
            n++; if (next > 0) upNext++; sumNext += next; if (gap > 0) upGap++; sumGap += gap
            if (!r5.isNaN()) { n5++; if (r5 > 0) up5++; sum5 += r5 }
        }
        fun stat() = DayStat(n, upNext.toDouble() / n, sumNext / n * 100, upGap.toDouble() / n, sumGap / n * 100,
            if (n5 > 0) up5.toDouble() / n5 else 0.5, if (n5 > 0) sum5 / n5 * 100 else 0.0)
    }

    fun build(bars: Map<String, List<Candle>>, symbols: List<String>, from: String = "2005-01-01", byEra: MutableMap<String, MutableMap<String, DayStat>>? = null): DayAfterTable {
        val base = Acc()
        val buckets = HashMap<String, Acc>()
        val eras = listOf("2005-12" to ("2005-01-01" to "2012-12-31"), "2013-19" to ("2013-01-01" to "2019-12-31"), "2020-26" to ("2020-01-01" to "2099-12-31"))
        val eraAcc = HashMap<String, Acc>()
        for (sym in symbols) {
            val c = bars[sym] ?: continue
            if (c.size < 260) continue
            val s = Series(c)
            for (i in 200 until s.n - 1) {
                if (s.dates[i] < from) continue
                val prev = s.close[i - 1]
                if (prev <= 0 || s.adj[i] <= 0 || s.close[i] <= 0) continue
                val next = s.adj[i + 1] / s.adj[i] - 1
                val gap = s.open[i + 1] / s.close[i] - 1
                if (abs(next) > 0.6 || abs(gap) > 0.6) continue // split glitches
                val r5 = if (i + 5 <= s.last) s.adj[i + 5] / s.adj[i] - 1 else Double.NaN
                base.add(next, gap, r5)
                val ret = s.close[i] / prev - 1
                val m = DayAfter.moveBucket(ret * 100) ?: continue
                var vs = 0.0; var vn = 0
                for (j in i - 20 until i) if (j >= 0 && s.volume[j] > 0) { vs += s.volume[j]; vn++ }
                val rvol = if (vn >= 10 && s.volume[i] > 0) s.volume[i] / (vs / vn) else null
                val clv = DayAfter.clv(s.close[i], s.low[i], s.high[i])
                val sma = s.sma200[i]
                val t = if (sma.isNaN()) 0 else if (s.close[i] > sma) 1 else 0
                for (k in DayAfter.keysFor(m, DayAfter.volBucket(rvol), DayAfter.clvBucket(clv), t)) buckets.getOrPut(k) { Acc() }.add(next, gap, r5)
                val era = eras.firstOrNull { s.dates[i] >= it.second.first && s.dates[i] <= it.second.second }?.first
                if (era != null) eraAcc.getOrPut("$era|${DayAfter.key(m, null, null, null)}") { Acc() }.add(next, gap, r5)
            }
        }
        byEra?.let { out -> eraAcc.forEach { (k, a) -> out.getOrPut(k.substringBefore('|')) { mutableMapOf() }[k.substringAfter('|')] = a.stat() } }
        return DayAfterTable(base.stat(), buckets.filterValues { it.n >= 100 }.mapValues { it.value.stat() })
    }

    fun report(us: DayAfterTable, oslo: DayAfterTable?, usEras: Map<String, Map<String, DayStat>>, dataTo: String): String {
        val sb = StringBuilder()
        fun pc(v: Double) = "%.1f%%".format(v * 100)
        fun sg(v: Double) = "%+.2f%%".format(v)
        sb.appendLine("# Big up-days: what happened next")
        sb.appendLine()
        sb.appendLine("Every close-to-close gain of 3% or more since 2005 (data to $dataTo), followed one session and five sessions forward. Used by the app's **Quick wins** tab to say whether selling by the close or holding overnight has paid.")
        sb.appendLine()
        for ((name, t) in listOf("US (172 tickers)" to us) + (oslo?.let { listOf("Oslo Børs (24 tickers)" to it) } ?: emptyList())) {
            sb.appendLine("## $name")
            sb.appendLine()
            sb.appendLine("Ordinary day: next session higher ${pc(t.base.pUpNext)}, average ${sg(t.base.meanNextPct)} (n=${"%,d".format(t.base.n)}).")
            sb.appendLine()
            sb.appendLine("| Today | n | next day higher | avg next day | opens higher | avg gap | 5 days higher | avg 5 days |")
            sb.appendLine("|---|---:|---:|---:|---:|---:|---:|---:|")
            val rows = mutableListOf<Pair<String, String>>()
            val moves = listOf("+3–5%", "+5–8%", "+8–12%", "+12%+")
            val clv = listOf("faded (<0.5 of range)", "mid-range", "closed near high")
            val vol = listOf("normal volume", "1.5–3× volume", "3×+ volume")
            for (m in 0..3) {
                rows += moves[m] to DayAfter.key(m, null, null, null)
                for (c in 0..2) rows += "  ${moves[m]}, ${clv[c]}" to DayAfter.key(m, null, c, null)
                for (v in 0..2) rows += "  ${moves[m]}, ${vol[v]}" to DayAfter.key(m, v, null, null)
            }
            for ((label, key) in rows) {
                val st = t.buckets[key] ?: continue
                sb.appendLine("| $label | ${"%,d".format(st.n)} | ${pc(st.pUpNext)} | ${sg(st.meanNextPct)} | ${pc(st.pGapUp)} | ${sg(st.meanGapPct)} | ${pc(st.pUp5)} | ${sg(st.mean5Pct)} |")
            }
            sb.appendLine()
        }
        if (usEras.isNotEmpty()) {
            sb.appendLine("## Stability over time (US, next day higher / average)")
            sb.appendLine()
            sb.appendLine("| move | " + usEras.keys.sorted().joinToString(" | ") + " |")
            sb.appendLine("|---|" + usEras.keys.joinToString("") { "---:|" })
            for (m in 0..3) {
                val k = DayAfter.key(m, null, null, null)
                sb.appendLine("| ${listOf("+3–5%", "+5–8%", "+8–12%", "+12%+")[m]} | " + usEras.keys.sorted().joinToString(" | ") { e -> usEras[e]?.get(k)?.let { "${pc(it.pUpNext)} / ${sg(it.meanNextPct)} (n=${it.n})" } ?: "—" } + " |")
            }
            sb.appendLine()
        }
        sb.appendLine("Caveats: survivorship (tickers chosen in 2026), close-to-close daily data (the app applies these to intraday prices near the close), and costs are not included. Not investment advice.")
        return sb.toString()
    }
}
