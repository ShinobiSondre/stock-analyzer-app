package com.shinobisondre.stockanalyzer

import com.shinobisondre.stockanalyzer.data.AlertItem
import com.shinobisondre.stockanalyzer.data.AppState
import com.shinobisondre.stockanalyzer.data.Holding
import com.shinobisondre.stockanalyzer.data.News
import com.shinobisondre.stockanalyzer.data.NewsItem
import com.shinobisondre.stockanalyzer.data.Settings
import com.shinobisondre.stockanalyzer.data.Store
import com.shinobisondre.stockanalyzer.data.WatchState
import com.shinobisondre.stockanalyzer.data.Yahoo
import com.shinobisondre.stockanalyzer.engine.LoggedCall
import com.shinobisondre.stockanalyzer.engine.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogicTest {

    // Shape of Yahoo's v8 chart response (same paths the trainer used to download 198 tickers).
    private fun chartJson(regularStart: Long, regularEnd: Long, marketTime: Long): String = """
        {"chart":{"result":[{"meta":{"currency":"USD","symbol":"TEST","exchangeName":"NMS","fullExchangeName":"NasdaqGS",
        "instrumentType":"EQUITY","regularMarketPrice":103.0,"regularMarketTime":$marketTime,"gmtoffset":-18000,
        "longName":"Test Corp","fiftyTwoWeekHigh":120.0,"fiftyTwoWeekLow":80.0,
        "currentTradingPeriod":{"regular":{"start":$regularStart,"end":$regularEnd}}},
        "timestamp":[1763130600,1763389800,1763476200,1763476260],
        "indicators":{"quote":[{"open":[100,101,null,102],"high":[101,102,null,104],"low":[99,100,null,101],"close":[100.5,101.5,null,103],"volume":[1000,1100,null,1200]}],
        "adjclose":[{"adjclose":[100.5,101.5,null,103]}]}}],"error":null}}
    """.trimIndent()

    @Test fun parsesChartAndDropsNullBars() {
        val r = Yahoo.parseChart("TEST", chartJson(0, 1, 1763476260))
        // The null bar is skipped and two prints on the same day collapse to one bar.
        assertEquals(3, r.candles.size)
        assertEquals("Test Corp", r.meta.name)
        assertEquals(103.0, r.price!!, 1e-9)
        assertEquals(101.5, r.prevClose!!, 1e-9)
        assertEquals((103.0 - 101.5) / 101.5 * 100, r.changePct!!, 1e-9)
        assertEquals("2025-11-14", r.candles[0].date) // 13:30 UTC − 5 h is still the 14th
    }

    @Test fun completedCandlesDropsTheFormingBarOnlyWhileTheSessionIsOpen() {
        val now = System.currentTimeMillis() / 1000
        val open = Yahoo.parseChart("TEST", chartJson(now - 600, now + 3600, now))
        assertEquals(open.candles.size - 1, open.completedCandles().size)
        val closed = Yahoo.parseChart("TEST", chartJson(now - 7200, now - 600, now - 600))
        assertEquals(closed.candles.size, closed.completedCandles().size)
    }

    @Test fun chartErrorBecomesException() {
        val ex = runCatching { Yahoo.parseChart("NOPE", """{"chart":{"result":null,"error":{"code":"Not Found","description":"No data found, symbol may be delisted"}}}""") }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex!!.message!!.contains("delisted"))
    }

    @Test fun stateRoundTrips() {
        val s = AppState(
            watchlist = listOf("AAPL", "EQNR.OL"),
            holdings = listOf(Holding("MU", 13.0, 984.0, 5L), Holding("KMAR.OL", 500.0, null, 6L)),
            settings = Settings(baseCurrency = "USD", alertIntervalMin = 120, notifyMoves = false),
            calls = listOf(LoggedCall("AAPL", "2026-11-13", 230.5, "BUY", 0.6, 0.61, null, 0.56, "m1")),
            alerts = listOf(AlertItem("id1", "AAPL", "call", "t", "m", 7L, read = true)),
            watch = mapOf("AAPL" to WatchState("bull", "BUY", "2026-11-13", 1, 3, "LEADER_PULLBACK", null)),
            lastRegime = "BULL", lastIndexDeathCross = false, onboarded = true,
        )
        val back = Store.fromMap(MiniJson.parse(MiniJson.write(Store.toMap(s))) as Map<String, Any?>)
        assertEquals(s, back)
    }

    @Test fun emptyWatchlistStaysEmpty() {
        val back = Store.fromMap(MiniJson.parse(MiniJson.write(Store.toMap(AppState(watchlist = emptyList())))) as Map<String, Any?>)
        assertTrue(back.watchlist.isEmpty())
    }

    @Test fun sentimentUsesWordBoundaries() {
        assertEquals(0, News.sentiment("Apple said it will keep the strategy"))
        assertTrue(News.sentiment("Shares surge after earnings beat") > 0)
        assertTrue(News.sentiment("Analyst downgrades Nvidia, stock falls") < 0)
        assertEquals(0, News.sentiment("Higher costs weigh on outlook")) // "high" removed, no collision
    }

    @Test fun whyMovingMatchesTickerAsAWord() {
        val now = System.currentTimeMillis() / 1000
        val items = listOf(
            NewsItem("Much ado about nothing in markets", "X", "", now - 3600),
            NewsItem("MU jumps after record revenue guidance", "Y", "", now - 1800),
        )
        val d = News.digest("MU", "Micron Technology", items, emptyList(), 6.0)
        assertTrue(d.whyMoving!!.contains("record revenue"))
        val quiet = News.digest("MU", "Micron Technology", items, emptyList(), 0.5)
        assertNull(quiet.whyMoving)
        assertFalse(d.items.isEmpty())
    }
}
