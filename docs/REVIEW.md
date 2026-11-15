# StockAnalyzer web app — code review

Review of the original Node/Express app (`ShinobiSondre/StockAnalyzer`, commit `236b22f`) done while porting it to Android. Every item below is fixed in this repo; the "Fix" column says where.

## High — wrong numbers shown to the user

| # | Where | Problem | Impact | Fix |
|---|---|---|---|---|
| 1 | `models/portfolio.js` `analyzePortfolio` | Market values in **different currencies are summed as if they were the same** (KMAR.OL in NOK, STBO.XC in GBp, US stocks in USD). Sector percentages use the same mixed sum. | Total value, sector mix and "Defensive/Bullish" stance are meaningless for a multi-currency portfolio. | `engine/Tracking.kt` `Portfolio`: every position is converted to one base currency (NOK by default) at live FX; minor units (GBp, ZAc, ILA) divided by 100. |
| 2 | `models/portfolio.js` totals | `totalPnl = totalValue − totalCost`, where `totalValue` covers **all** holdings but `totalCost` only those with an average cost. | With 1 of 11 holdings costed (the stored portfolio), P/L showed the whole portfolio's value as profit. | P/L is computed only over holdings with a cost basis, and the UI says how many that is. |
| 3 | `models/backtest.js` `priceAfter` | When the horizon date hasn't arrived yet it falls back to the **latest price**. | 10- and 22-day calls were graded after 6 days — the "Actual backtest" hit rates mixed immature outcomes in. | `engine/Tracking.kt` `TrackRecord.grade`: an immature horizon is `null` (pending), never scored. |
| 4 | `models/backtest.js` | Horizons counted in **calendar days** (`offsetDays * 86400000`); the walk-forward that set the weights used **trading sessions**. "22 days" was ~15 sessions. | Live accuracy and the quoted walk-forward numbers weren't comparable. | Grading uses bar indices (sessions). |
| 5 | `models/predictor.js` | Signals labelled "shown, not scored" (golden cross, death cross, suppressed fear) carried **weight 0.5 with score 0**. | Every time one appeared it pulled the weighted score toward 0, silently weakening real signals. | `engine/Rules.kt`: weight 0. Unit test `noDilution`. |
| 6 | `models/predictor.js` | News sentiment still voted (weight 0.75, 1.2 at one month) although the app's own learning loop measured headline sentiment at **26% directional accuracy** (see `newsAnalyzer.js` comment). | A signal worse than a coin flip moved the call. | Context only (shown, not scored). |
| 7 | `controllers/stockController.js` | `catalysts.netScore + marketMood.nudge`: the VIX/Fear-&-Greed nudge was added into the catalyst vote **in addition to** the first-class market-fear signal. | Market fear counted twice; Fear & Greed thresholds were never validated. | Catalysts are context only; fear enters once, as the measured setup / model feature. |
| 8 | `models/predictionStore.js` `normalizeSymbols` | `split(/[,\\s]+/)` in a regex **literal** means "comma, backslash or the letter s". `"tsla, msft"` → `t`, `la`, `m`, `ft`; `"AAPL MSFT"` is not split at all and then fails validation. | Adding several tickers to the tracked watchlist silently dropped or mangled them. | `HoldingsText.symbols` splits on commas/whitespace; unit-tested with exactly this input. |
| 9 | `models/livePoller.js` | Signal-flip alerts computed on the **live intraday bar**, no hysteresis. Stored alerts: 44 flips, **17 reversed within 24 h**. | Alert noise; "BUY → HOLD → BUY" the same afternoon. | `work/AlertWorker.kt`: judged on completed daily bars only; alerts on a change of group (bullish/hold/bearish) or an upgrade to STRONG BUY. |
| 10 | `controllers/stockController.js` `analysis` (track record) | Past calls graded as bullish when `rawScore ≥ 0.1`, but the label threshold is 0.6 — a **HOLD** with score 0.3 was scored as a BUY call. HOLD counted as correct if the move stayed within ±3%. | The "Track record on this stock" card graded calls the user never saw. | Calls are logged with the label shown and graded on one rule (higher after h sessions). |

## Medium — inconsistent logic

| # | Where | Problem | Fix |
|---|---|---|---|
| 11 | `predictor.js` `predictTimeframes` | Skips the quality gate and the "STRONG BUY needs the fear setup" rule → the 1-week tile could say BUY while the headline said HOLD (vetoed). | One function, one call; per-horizon calls now come from per-horizon calibrated models. |
| 12 | `predictor.js` | Bearish gate covers `(-1.2, 0)` but WEAK SELL runs to −1.30, so −1.2…−1.3 escaped the gate. | Gate covers the whole WEAK SELL band. |
| 13 | `quickApi`, `topTechnical`, `portfolio.js`, `livePoller.js` | Call `predict()` **without** the market-fear context / index-death-cross gate (and the portfolio and quick API without the S&P benchmark). The same stock got different calls on different screens. | One `Analyzer.analyze` path with the same market context everywhere. |
| 14 | `regimeMonitor.getLastRegime` | The fear gate reads `indexDeathCross` from a state file written only by the daily poller; on a fresh start it's `false` → the gate is open even in a death cross. | Computed directly from SPY every time (`MarketSeries`). |
| 15 | `technicalAnalysis.findSupportResistance` | Levels typed by origin (pivot high = resistance) not position — a broken-through old high below the price was still called "resistance". | Classified by position vs. current price; unit-tested. |
| 16 | `learningWeights.js` | Hit rate compared with 50%, but ~55% of 10-day windows are up → any always-bullish indicator looks skilled; snapshots not throttled (10 visits = 10 correlated samples); scored at whatever age 7–90 days. | Removed. Replaced by an honest, fixed-horizon live track record. |
| 17 | `predictionStore` / `learningWeights` / `modelPortfolio` / `causeTracker` | Five modules do unsynchronised read-modify-write on the same `data/predictions.json`. Two concurrent requests can drop each other's writes. | Single store, mutex + atomic write (`data/Store.kt`). |
| 18 | `predictor.js` `calcConfidence` | "Confidence" is a heuristic (agreement + ADX bonus + …), capped at 88, presented next to hit rates. It is not a probability and was never calibrated. | Replaced with calibrated probabilities measured out of sample (see `MODEL_REPORT.md`). |
| 19 | `predictor.js` price targets | Range = ±k·ATR widened (not shifted) by the score; never checked for coverage. | 80% band on EWMA volatility with a width multiplier calibrated so 80% of out-of-sample outcomes fall inside. |
| 20 | `newsCause.js` `keywordHits` | Substring matching: catalyst word `ai` matches "s**ai**d", ticker `MU` matches "**mu**ch" / "com**mu**nity". Almost every headline got the catalyst bonus. | Word-boundary regex. |
| 21 | `technicalAnalysis.detectPatterns` | Hammer / shooting star only check that the previous candle was red/green — no prior trend. | Requires a 5-bar decline / rise. |

## Low / security

| # | Where | Problem |
|---|---|---|
| 22 | `models/bankid.js` | `rejectUnauthorized: false` when no certificate is configured — TLS verification off for every BankID call. It also targets **Swedish** BankID's *test* endpoint and Nordnet's legacy `nordnet.se/api/2`; it cannot have worked for a Norwegian account. Not ported. |
| 23 | `app.js` | Default session secret `'stockanalyzer-secret-change-in-production'`; `/api/test-email` lets anyone trigger mail to an arbitrary address. |
| 24 | `data/subscriptions.json` | A work e-mail address is committed to the repository. |
| 25 | `modelPortfolio.js` | Assumes every holding is USD (applies USDNOK to all). `entryFx` rounded to 2 decimals. |
| 26 | `topPotential.js` `buildScoreAccuracy` | Old prediction runs scored against today's price regardless of age, mixing horizons in one table. |

## Prediction accuracy — what changed

The web app's comments already did a lot right (it re-fitted on 21 years and two bear markets, kept a holdout set). The port goes further:

1. **One pipeline, tested twice.** Indicators are re-implemented in pure Kotlin and checked against the `technicalindicators` npm package on the same data (`EngineSelfTest`, 64 checks). The trainer and the phone build features with the same code.
2. **Fair competition.** The trainer predicts every year 2010→2026 with models fitted only on earlier data, for 172 US tickers (82 never used for fitting) and 24 Oslo tickers. Three predictors compete on identical rows: the old rules score, a logistic model and gradient-boosted trees. Each is calibrated (leave-one-year-out) and the lowest Brier score ships.
3. **Calibrated output.** The number on screen is "how often stocks in this situation were higher N sessions later", not an agreement heuristic. Labels (BUY etc.) are only issued when the calibrated probability beats the base rate by at least 1–4 points, and each label shows its own out-of-sample hit rate.
4. **Two targets.** "Will it be higher?" is mostly the market moving. "Will it beat the S&P 500?" isolates stock-picking skill and is reported separately.
5. **Honesty about limits.** The first real run showed a plain logistic model did *not* beat the rules engine (AUC ≈ 0.50, Brier worse than the base rate). That result is why the competition exists.

### Result (model m20261115, out of sample 2010–2026, ~660k US stock-days per horizon)

| Target | Horizon | Winner | AUC | What the app's labels did |
|---|---|---|---:|---|
| Price higher | 1 week | rules (calibrated) | 0.508 | BUY: 58.7% higher vs 54.8% base (n=28k) |
| Price higher | 2 weeks | rules (calibrated) | 0.505 | BUY: 60.4% vs 56.4% (n=25k), avg +1.33% vs +0.73% |
| Price higher | 1 month | rules (calibrated) | 0.505 | BUY: 62.6% vs 58.4% (n=27k), avg +2.87% vs +1.62% |
| Beat S&P 500 | 1 week | gradient-boosted trees | 0.515 | STRONG BUY 55.5% beat · BUY 53.0% · SELL 45.7% (base 50.1%) |
| Beat S&P 500 | 2 weeks | rules | 0.504 | BUY 53.6% vs 50.2% |
| Beat S&P 500 | 1 month | rules | 0.502 | WEAK BUY 51.8% vs 50.5% |

What that means in practice:

* The web app's BUY signal was real — about 4 points better than a random stock at 2 weeks — and the port keeps it, now with its measured hit rate on screen.
* Its STRONG BUY, WEAK BUY and SELL/AVOID tiers were **not** better than average out of sample, so the app no longer issues them for "price higher". The old heuristic confidence (often 70–88%) overstated certainty; calibrated probabilities sit within a few points of the base rate because that is what the data supports.
* Logistic regression and boosted trees on 39 features could not beat the rules score for direction — a strong hint that single-stock direction at these horizons is close to unpredictable from price data. They do add a little for **relative** performance at 1 week, which is shown as "beats S&P 500" odds.
* Results held on 82 tickers never used for fitting and roughly held on Oslo Børs (out of domain).
