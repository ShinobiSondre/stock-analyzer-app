package com.shinobisondre.stockanalyzer.ui

/** Plain-English explanations behind the "?" on each card (keyed by the card's title). */
object Glossary {
    val cards: Map<String, String> = mapOf(
        "Verdict" to "The app's overall call on this stock.\n\nThe percentage is how often stocks in a situation like this one were higher after the next few weeks, measured on 20 years of history. Compare it with the \"normal\" number: above it means better-than-usual odds, below it means worse.\n\nIt's odds, not a promise — a 60% call is still wrong 4 times out of 10.",
        "Chart" to "Each bar (\"candle\") is one day. Green: it closed higher than it opened. Red: lower.\n\nThe smooth lines are averages of the last 20, 50 and 200 days. Price above the 200-day line usually means the long-term trend is up.\n\nTap or drag on the chart to see any day.",
        "What's driving the odds" to "The things that pushed the odds up (green bars, to the right) or down (red bars, to the left), compared with an average stock on an average day. Longer bar = bigger effect.",
        "Measured setups" to "Patterns the app has tested on 20+ years of prices, shown with how often they actually worked. \"Falling knife\" = a stock dropping hard — catching it early has usually hurt.",
        "How long to hold" to "For situations like this in the past, which holding time (1 week, 2 weeks or 1 month) had the best odds.",
        "In plain English" to "A short summary of everything on this page, without the jargon.",
        "Fundamentals" to "Facts about the business itself: how much money it makes, how fast it's growing, and how expensive the stock is compared with its profits.\n\nP/E = price ÷ yearly profit per share. A P/E of 30 means you pay 30 years of today's profit. High P/E = investors expect fast growth.\n\nGood background, but it doesn't predict next week's price.",
        "Upcoming agenda" to "Dates that can move the price a lot. Earnings = the day the company reports its results; the stock often jumps or drops 5–10% the day after.",
        "News" to "Recent headlines about the company. Green/red shows whether a headline sounds good or bad. News explains moves after they happen; it rarely helps predict the next one.",
        "Technical indicators" to "Maths on past prices.\n\nRSI: above 70 = it rose very fast lately (often rests for a while), below 30 = it fell very fast.\nMACD: whether the price's speed is picking up or slowing down.\nBollinger bands: a channel around the price; touching the top edge means it's stretched upwards.\nVolume: how many shares changed hands — big moves on big volume are taken more seriously.",
        "Tyler 4-stack" to "Tyler Hill's checklist. He sells when 4 things line up: the price is stretched to the top of its range, momentum is fading, fewer shares are traded on the way up, and everyone is very excited. 3 of 4 = trim a bit, 4 of 4 = take real profit. The mirror image (everything washed out and fearful) is his buy signal.",
        "Support, resistance & patterns" to "Support: a price where the stock has often stopped falling (buyers step in). Resistance: a price where it has often stopped rising (sellers step in). Breaking through either is often a big deal.",
        "Classic indicator score" to "The original web app's score: many indicators added up into a number from 0 to 10. Kept for reference — the odds at the top of the page are the better guide.",
        "Track record on this stock" to "Calls this app has shown you on this stock before, and whether they turned out right. Honest bookkeeping.",
        "Market mood" to "How the whole stock market is doing. Most stocks rise when the market rises and fall when it falls, so this matters for everything else.\n\nVIX = the \"fear gauge\": above 25 means investors are scared. Historically, buying good companies slowly when people are scared (but the market's long-term trend is still up) has worked well.",
        "Total value" to "What everything you own is worth right now in your currency, and how much it moved today. P/L (profit/loss) compares today's value with what you paid.",
        "Sector mix" to "How your money is split across industries. If most of it sits in one industry (say chips), one piece of bad news for that industry hits almost everything you own.",
        "Ripple effect" to "Companies are connected: suppliers, customers and rivals. When one does well, connected ones often move too. This shows which ones have actually moved together over the last 2 years.",
        "Today for you" to "A 30-second summary of the market, your stocks and anything worth a look today. Tap a line to jump there.",
        "If you had invested" to "What a sum put into this stock in the past would be worth today (dividends included, currency changes not). The past doesn't predict the future, but it shows how bumpy the ride has been.",
        "His latest view" to "The newest of Tyler Hill's videos, summarised: whether he's positive (bullish) or negative (bearish) on the market, and what he says he's doing with his own money.",
        "Stocks he's talked about" to "Tyler's most recent call on each stock he has mentioned. ↑ = he expects it to rise, ↓ = fall.",
        "How it decides" to "Quick wins looks at every time a stock jumped 3%+ in a day since 2005 and what it did the next day, then tells you what usually happened.",
    )
}
