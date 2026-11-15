# StockAnalyzer for Android

StockAnalyzer rebuilt as a native Android app for the **Galaxy Z Fold** — cover screen and inner screen — with a re-validated prediction engine.

## Install

1. On the phone, open **[Releases](https://github.com/ShinobiSondre/stock-analyzer-app/releases/latest)** and download `StockAnalyzer-1.0.x.apk`.
2. Open it and allow your browser to install apps when Android asks.
3. After that the app updates itself: every green build of `main` is published as a release, and the app checks on launch and twice a day (Settings → App updates). Tap *Download & install*; the first time Android asks you to allow StockAnalyzer to install updates.

## What it does

| Screen | |
|---|---|
| **Markets** | Search any Yahoo ticker (US, `.OL` for Oslo Børs, funds, ETFs). Market mood: S&P 500, VIX, fear tier, regime, S&P death cross, CNN Fear & Greed. Watchlist with live price, sparkline and the 2-week call. |
| **Analysis** | Calibrated odds for 1 week / 2 weeks / 1 month: probability the price is higher, probability it beats the S&P 500, the base rate for comparison, an 80% price range, and what past calls with the same label actually did. Interactive candlestick chart (SMA 20/50/200, Bollinger, support/resistance, RSI, MACD, tap/drag to inspect a day). What's driving the odds, measured setups (leader pullback, fear spike, falling knife…), how long to hold, a plain-English summary, fundamentals & quality screen, analyst/earnings agenda, news with "why is it moving", all indicators, Tyler 4-stack (daily + weekly), classic indicator score, and this app's own track record on the stock. |
| **Scanner** | Run the full analysis over a list (US core 30, US large caps, Oslo Børs, or your own lists) and rank by odds. |
| **Portfolio** | Holdings valued in one currency (NOK by default, live FX, GBp handled), P/L on positions with a cost basis, per-holding call for the chosen horizon, sector mix and concentration warning. Paste all holdings at once: `GOOGL 10, NVDA 21, MU 13 @ 984`. |
| **Alerts** | Background watcher (WorkManager) for portfolio + watchlist: call changes on completed daily bars, big moves, Tyler 3/4-stacks, new setups, market regime and S&P death-cross changes. Plus the live track record of every call the app has shown you, and the model's validation numbers. |

## Built for the Fold

* **Cover screen** (~430 dp): single pane with bottom navigation.
* **Unfolded** (~880 dp): navigation rail + list on the left, full analysis on the right; the analysis lays its cards out in two columns.
* **Book posture** (half-folded, vertical hinge): the pane divider snaps to the hinge so nothing sits in the crease.
* **Flex mode / tabletop** (half-folded, horizontal hinge): the chart stays on the top half, details scroll on the bottom half.
* Fold/unfold keeps state (configuration changes handled in place, no reload).

## The prediction engine

`engine/` is pure Kotlin and runs identically on the phone and in the trainer. Every indicator is checked against the `technicalindicators` npm package the web app used (`EngineSelfTest`).

`trainer/` downloads 2004→today daily bars for ~200 tickers and runs a walk-forward test: each year from 2010 is predicted by models fitted only on earlier data, on 90 fitting tickers, 82 never-seen US tickers and 24 Oslo tickers. Three predictors compete — the web app's rules score, logistic regression and gradient-boosted trees — each calibrated, and the best by Brier score ships (`app/src/main/assets/model.json`). Labels are only issued when their own out-of-sample record beats the base rate.

Results: **[docs/MODEL_REPORT.md](docs/MODEL_REPORT.md)**. Short version: short-horizon direction of single stocks is close to unpredictable from price data; the measurable edge is a few percentage points on a minority of days, and the app now says so with real numbers instead of a heuristic "confidence".

Bugs found in the web app and how they were fixed: **[docs/REVIEW.md](docs/REVIEW.md)**.

## Build

```bash
./gradlew :engine:test :app:testReleaseUnitTest :app:assembleRelease     # APK in app/build/outputs/apk/release/
./gradlew :trainer:installDist && trainer/build/install/trainer/bin/trainer --cache ~/wf-cache --out out   # retrain (needs internet)
```

CI: `.github/workflows/android.yml` (tests, build, release) and `.github/workflows/train.yml` (retrain on engine/trainer changes; results pushed to the `ci-model` branch).

### Signing key

`app/release.jks` is committed so every CI build is signed with the same key (otherwise updates would refuse to install). The repo is public, so anyone could sign an APK with it; only install updates through the app or this repo's Releases. To harden: add the keystore as a GitHub secret, point `KEYSTORE_FILE`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` at it in the workflow, and delete the file — keeping the **same** key so existing installs keep updating.

## Not ported

* **Nordnet / BankID login** — the web app called Swedish BankID's *test* endpoint with TLS verification disabled and Nordnet's discontinued `api/2`; it could not work for a Norwegian account. Use *Paste holdings* instead.
* **E-mail digests** — replaced by phone notifications.
* **Finviz scraping** — fragile HTML scraping; Yahoo's analyst data covers the same ground.
* **Self-learning weights** — measured noise against a 50% yardstick in a 55%-up market; replaced by an honest live track record.

Not investment advice.
