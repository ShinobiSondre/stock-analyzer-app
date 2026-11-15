package com.shinobisondre.stockanalyzer.engine

/**
 * All indicator arrays for one price history, computed once. The app builds
 * one of these for the ~2 years it downloads; the trainer builds one for the
 * full 20-year history and reads it at every bar. Same code, same numbers.
 */
class Series(val candles: List<Candle>) {
    val n = candles.size
    val dates = Array(n) { candles[it].date }
    val open = DoubleArray(n) { candles[it].open }
    val high = DoubleArray(n) { candles[it].high }
    val low = DoubleArray(n) { candles[it].low }
    val close = DoubleArray(n) { candles[it].close }
    val adj = DoubleArray(n) { candles[it].adjClose }
    val volume = DoubleArray(n) { candles[it].volume }

    val sma20 = Indicators.sma(close, 20)
    val sma50 = Indicators.sma(close, 50)
    val sma200 = Indicators.sma(close, 200)
    val ema12 = Indicators.ema(close, 12)
    val ema26 = Indicators.ema(close, 26)
    val rsi = Indicators.rsi(close, 14)
    val macd = Indicators.macd(close)
    val bb = Indicators.bollinger(close, 20, 2.0)
    val stoch = Indicators.stochastic(high, low, close, 14, 3)
    val adx = Indicators.adx(high, low, close, 14)
    val atr = Indicators.atr(high, low, close, 14)
    val obv = Indicators.obv(close, volume)
    val obvSma20 = Indicators.sma(obv, 20)
    val willR = Indicators.williamsR(high, low, close, 14)
    val volSma20 = Indicators.sma(volume, 20)
    val vol20 = Indicators.logReturnVol(close, 20)
    val ewmaVol = Indicators.ewmaVol(close)
    val max252 = Indicators.rollingMax(close, 252)

    val last: Int get() = n - 1

    fun indexOfDate(date: String): Int {
        var lo = 0; var hi = n - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = dates[mid].compareTo(date)
            if (c == 0) return mid
            if (c < 0) lo = mid + 1 else hi = mid - 1
        }
        return -1
    }

    /** 12-1 month return: 252 sessions ago → 21 sessions ago (skips the latest month). */
    fun ret12_1(i: Int): Double? =
        if (i >= 252 && close[i - 252] > 0) close[i - 21] / close[i - 252] - 1.0 else null

    fun ret(i: Int, back: Int): Double? =
        if (i - back >= 0 && close[i - back] > 0) close[i] / close[i - back] - 1.0 else null
}
