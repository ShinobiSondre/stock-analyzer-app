# StockAnalyzer probability model — validation report

Generated 2026-11-15 by the trainer in `trainer/`. Data: daily bars 2005-05-19 → 2026-11-06 from Yahoo Finance.

* Fitting set: 90 US large caps (the web app's DISCOVERY list).
* Holdout: 82 US tickers never used for fitting.
* Out-of-domain: 24 Oslo Børs tickers (model uses US market features — expect weaker results).
* Rows: 928,068 stock-days. Every number below is **out of sample**: year Y is predicted by a model fitted only on data whose outcome was known before Y.
* Download failures (skipped): EA

Three predictors compete on identical out-of-sample rows. Each one's raw score is mapped to a probability by a monotone calibration fitted on the *other* years (leave-one-year-out), so all three are judged as calibrated probabilities. The one with the lowest Brier score on US stocks ships in the app.

## Price higher — 5 trading days (1 week)

Base rate (share of US stock-days that were higher after 5 sessions): **54.8%**. Shipped predictor: **rules**. Labels dropped because their own out-of-sample record did not beat the base rate: STRONG BUY, WEAK BUY, WEAK SELL/AVOID.

| Predictor | set | AUC | Brier | Brier (base rate) | skill | top-10% up-rate | bottom-10% up-rate |
|---|---|---:|---:|---:|---:|---:|---:|
| **rules** | US (all OOS) | 0.508 | 0.2477 | 0.2478 | +0.03% | 57.0% | 53.2% |
| logistic | US (all OOS) | 0.511 | 0.2477 | 0.2478 | +0.04% | 58.2% | 54.6% |
| gbdt | US (all OOS) | 0.511 | 0.2478 | 0.2478 | -0.01% | 57.3% | 53.4% |
| **rules** | Holdout tickers | 0.508 | 0.2477 | 0.2478 | +0.03% | 57.1% | 52.8% |
| logistic | Holdout tickers | 0.512 | 0.2477 | 0.2478 | +0.05% | 58.3% | 54.6% |
| gbdt | Holdout tickers | 0.511 | 0.2479 | 0.2478 | -0.01% | 57.2% | 53.2% |
| **rules** | Oslo | 0.514 | 0.2493 | 0.2495 | +0.09% | 55.5% | 50.8% |
| logistic | Oslo | 0.513 | 0.2494 | 0.2495 | +0.05% | 55.2% | 50.7% |
| gbdt | Oslo | 0.513 | 0.2494 | 0.2495 | +0.04% | 54.6% | 48.8% |

AUC 0.5 = coin flip. Skill = 1 − Brier / Brier(base rate): above 0 means the probabilities beat "stocks usually go up".

**Calls with the shipped predictor (US, out of sample):**

| Call | threshold | share | n | % were higher after 5 d | avg return | vs all stocks |
|---|---:|---:|---:|---:|---:|---:|
| BUY | ≥ 56.8% | 4.3% | 28,305 | 58.7% | 0.77% | +0.41 pts |
| HOLD | — | 95.7% | 635,699 | 54.6% | 0.35% | -0.02 pts |

For reference, the old rules engine's own BUY labels (unchanged thresholds) fired on 6.3% of days and the share that were higher after 5 d was 57.9%.

**Calibration** (shipped predictor, predicted vs realised, deciles):

| decile | mean predicted | realised | n |
|---:|---:|---:|---:|
| 1 | 53.2% | 54.7% | 66,400 |
| 2 | 54.1% | 54.7% | 66,400 |
| 3 | 54.5% | 58.1% | 66,401 |
| 4 | 54.7% | 56.6% | 66,400 |
| 5 | 54.8% | 55.7% | 66,401 |
| 6 | 54.8% | 53.9% | 66,400 |
| 7 | 54.9% | 53.5% | 66,400 |
| 8 | 55.0% | 52.3% | 66,401 |
| 9 | 55.1% | 51.9% | 66,400 |
| 10 | 56.8% | 56.5% | 66,401 |

**By period** (bullish calls vs that period's base rate):

| period | base up-rate | shipped bullish: n / % up | old rules bullish: n / % up |
|---|---:|---:|---:|
| 2010-13 | 55.7% | 6,998 / 56.6% | 10,404 / 56.7% |
| 2014-19 | 55.9% | 7,255 / 63.3% | 11,832 / 61.6% |
| 2020-21 | 55.5% | 5,491 / 59.2% | 7,216 / 58.3% |
| 2022 | 49.1% | 1,729 / 49.9% | 2,418 / 50.7% |
| 2023 | 53.8% | 1,973 / 56.1% | 2,856 / 54.7% |
| 2024-26 | 53.6% | 4,859 / 58.4% | 7,275 / 56.7% |

80% price band: width multiplier 0.973 × σ√h covers 80.0% of outcomes.

## Price higher — 10 trading days (2 weeks)

Base rate (share of US stock-days that were higher after 10 sessions): **56.4%**. Shipped predictor: **rules**. Labels dropped because their own out-of-sample record did not beat the base rate: STRONG BUY, WEAK BUY, WEAK SELL/AVOID.

| Predictor | set | AUC | Brier | Brier (base rate) | skill | top-10% up-rate | bottom-10% up-rate |
|---|---|---:|---:|---:|---:|---:|---:|
| **rules** | US (all OOS) | 0.505 | 0.2461 | 0.2461 | +0.01% | 58.2% | 55.1% |
| logistic | US (all OOS) | 0.503 | 0.2462 | 0.2461 | -0.04% | 57.6% | 56.7% |
| gbdt | US (all OOS) | 0.504 | 0.2462 | 0.2461 | -0.04% | 58.3% | 57.2% |
| **rules** | Holdout tickers | 0.505 | 0.2461 | 0.2461 | +0.00% | 57.8% | 54.9% |
| logistic | Holdout tickers | 0.502 | 0.2462 | 0.2461 | -0.04% | 57.1% | 56.9% |
| gbdt | Holdout tickers | 0.503 | 0.2462 | 0.2461 | -0.05% | 57.7% | 57.3% |
| **rules** | Oslo | 0.511 | 0.2484 | 0.2485 | +0.05% | 56.6% | 52.4% |
| logistic | Oslo | 0.511 | 0.2485 | 0.2485 | +0.00% | 56.5% | 52.2% |
| gbdt | Oslo | 0.511 | 0.2484 | 0.2485 | +0.02% | 57.5% | 53.7% |

AUC 0.5 = coin flip. Skill = 1 − Brier / Brier(base rate): above 0 means the probabilities beat "stocks usually go up".

**Calls with the shipped predictor (US, out of sample):**

| Call | threshold | share | n | % were higher after 10 d | avg return | vs all stocks |
|---|---:|---:|---:|---:|---:|---:|
| BUY | ≥ 58.4% | 3.8% | 25,303 | 60.4% | 1.33% | +0.60 pts |
| HOLD | — | 96.2% | 637,841 | 56.2% | 0.71% | -0.02 pts |

For reference, the old rules engine's own BUY labels (unchanged thresholds) fired on 6.3% of days and the share that were higher after 10 d was 59.1%.

**Calibration** (shipped predictor, predicted vs realised, deciles):

| decile | mean predicted | realised | n |
|---:|---:|---:|---:|
| 1 | 55.2% | 56.9% | 66,314 |
| 2 | 55.9% | 61.5% | 66,314 |
| 3 | 56.0% | 60.6% | 66,315 |
| 4 | 56.2% | 57.8% | 66,314 |
| 5 | 56.3% | 57.5% | 66,315 |
| 6 | 56.4% | 56.3% | 66,314 |
| 7 | 56.5% | 54.2% | 66,314 |
| 8 | 56.6% | 52.7% | 66,315 |
| 9 | 56.7% | 50.8% | 66,314 |
| 10 | 58.1% | 55.8% | 66,315 |

**By period** (bullish calls vs that period's base rate):

| period | base up-rate | shipped bullish: n / % up | old rules bullish: n / % up |
|---|---:|---:|---:|
| 2010-13 | 58.2% | 6,420 / 56.6% | 10,404 / 57.2% |
| 2014-19 | 57.6% | 6,630 / 65.3% | 11,832 / 63.1% |
| 2020-21 | 57.4% | 4,934 / 60.6% | 7,216 / 59.7% |
| 2022 | 48.8% | 1,465 / 54.7% | 2,418 / 53.7% |
| 2023 | 54.7% | 1,614 / 59.9% | 2,856 / 56.8% |
| 2024-26 | 54.5% | 4,240 / 60.6% | 7,169 / 57.4% |

80% price band: width multiplier 0.994 × σ√h covers 80.0% of outcomes.

## Price higher — 22 trading days (1 month)

Base rate (share of US stock-days that were higher after 22 sessions): **58.4%**. Shipped predictor: **rules**. Labels dropped because their own out-of-sample record did not beat the base rate: STRONG BUY, WEAK BUY, WEAK SELL/AVOID.

| Predictor | set | AUC | Brier | Brier (base rate) | skill | top-10% up-rate | bottom-10% up-rate |
|---|---|---:|---:|---:|---:|---:|---:|
| **rules** | US (all OOS) | 0.505 | 0.2434 | 0.2434 | +0.02% | 60.4% | 57.1% |
| logistic | US (all OOS) | 0.490 | 0.2435 | 0.2434 | -0.02% | 59.5% | 60.8% |
| gbdt | US (all OOS) | 0.497 | 0.2434 | 0.2434 | -0.00% | 61.0% | 61.6% |
| **rules** | Holdout tickers | 0.504 | 0.2435 | 0.2436 | +0.01% | 60.2% | 56.8% |
| logistic | Holdout tickers | 0.492 | 0.2436 | 0.2436 | -0.02% | 59.7% | 60.9% |
| gbdt | Holdout tickers | 0.496 | 0.2436 | 0.2436 | -0.01% | 60.3% | 61.7% |
| **rules** | Oslo | 0.508 | 0.2461 | 0.2462 | +0.06% | 59.4% | 56.2% |
| logistic | Oslo | 0.499 | 0.2462 | 0.2462 | +0.00% | 57.2% | 57.9% |
| gbdt | Oslo | 0.503 | 0.2462 | 0.2462 | +0.01% | 57.9% | 57.6% |

AUC 0.5 = coin flip. Skill = 1 − Brier / Brier(base rate): above 0 means the probabilities beat "stocks usually go up".

**Calls with the shipped predictor (US, out of sample):**

| Call | threshold | share | n | % were higher after 22 d | avg return | vs all stocks |
|---|---:|---:|---:|---:|---:|---:|
| BUY | ≥ 60.4% | 4.0% | 26,628 | 62.6% | 2.87% | +1.25 pts |
| HOLD | — | 96.0% | 634,452 | 58.2% | 1.57% | -0.05 pts |

For reference, the old rules engine's own BUY labels (unchanged thresholds) fired on 6.3% of days and the share that were higher after 22 d was 61.5%.

**Calibration** (shipped predictor, predicted vs realised, deciles):

| decile | mean predicted | realised | n |
|---:|---:|---:|---:|
| 1 | 56.9% | 60.2% | 66,108 |
| 2 | 57.7% | 63.1% | 66,108 |
| 3 | 57.8% | 65.8% | 66,108 |
| 4 | 58.1% | 62.0% | 66,108 |
| 5 | 58.2% | 60.7% | 66,108 |
| 6 | 58.4% | 56.3% | 66,108 |
| 7 | 58.5% | 55.6% | 66,108 |
| 8 | 58.6% | 54.2% | 66,108 |
| 9 | 58.9% | 49.2% | 66,108 |
| 10 | 60.3% | 56.6% | 66,108 |

**By period** (bullish calls vs that period's base rate):

| period | base up-rate | shipped bullish: n / % up | old rules bullish: n / % up |
|---|---:|---:|---:|
| 2010-13 | 61.7% | 6,256 / 66.0% | 10,404 / 65.2% |
| 2014-19 | 59.6% | 7,141 / 65.1% | 11,832 / 63.7% |
| 2020-21 | 59.8% | 5,191 / 63.1% | 7,216 / 61.7% |
| 2022 | 47.5% | 1,657 / 52.2% | 2,418 / 50.9% |
| 2023 | 56.0% | 1,812 / 57.7% | 2,856 / 56.5% |
| 2024-26 | 55.6% | 4,571 / 58.9% | 7,073 / 57.8% |

80% price band: width multiplier 1.018 × σ√h covers 80.0% of outcomes.

## Beat the S&P 500 — 5 trading days (1 week)

Base rate (share of US stock-days that beat the S&P 500 after 5 sessions): **50.1%**. Shipped predictor: **gbdt**.

| Predictor | set | AUC | Brier | Brier (base rate) | skill | top-10% up-rate | bottom-10% up-rate |
|---|---|---:|---:|---:|---:|---:|---:|
| rules | US (all OOS) | 0.507 | 0.2500 | 0.2500 | +0.01% | 51.7% | 49.1% |
| logistic | US (all OOS) | 0.512 | 0.2499 | 0.2500 | +0.04% | 52.5% | 48.5% |
| **gbdt** | US (all OOS) | 0.515 | 0.2498 | 0.2500 | +0.08% | 53.3% | 47.7% |
| rules | Holdout tickers | 0.507 | 0.2500 | 0.2500 | +0.01% | 51.5% | 48.9% |
| logistic | Holdout tickers | 0.509 | 0.2500 | 0.2500 | +0.02% | 51.8% | 48.4% |
| **gbdt** | Holdout tickers | 0.513 | 0.2499 | 0.2500 | +0.06% | 52.6% | 47.6% |
| rules | Oslo | 0.517 | 0.2499 | 0.2500 | +0.06% | 52.2% | 47.6% |
| logistic | Oslo | 0.518 | 0.2498 | 0.2500 | +0.09% | 52.7% | 46.4% |
| **gbdt** | Oslo | 0.521 | 0.2497 | 0.2500 | +0.14% | 53.3% | 45.8% |

AUC 0.5 = coin flip. Skill = 1 − Brier / Brier(base rate): above 0 means the probabilities beat "stocks usually go up".

**Calls with the shipped predictor (US, out of sample):**

| Call | threshold | share | n | % beat the S&P 500 after 5 d | avg excess return | vs all stocks |
|---|---:|---:|---:|---:|---:|---:|
| STRONG BUY | ≥ 54.1% | 2.7% | 17,722 | 55.5% | 0.66% | +0.57 pts |
| BUY | ≥ 52.1% | 3.8% | 25,458 | 53.0% | 0.36% | +0.28 pts |
| WEAK BUY | ≥ 51.1% | 10.7% | 71,114 | 50.9% | 0.16% | +0.08 pts |
| HOLD | — | 72.8% | 483,309 | 49.9% | 0.07% | -0.02 pts |
| WEAK SELL | ≤ 49.0% | 7.1% | 47,404 | 49.1% | -0.06% | -0.14 pts |
| SELL | ≤ 47.1% | 2.9% | 18,997 | 45.7% | -0.33% | -0.42 pts |

For reference, the old rules engine's own BUY labels (unchanged thresholds) fired on 6.3% of days and the share that beat the S&P 500 after 5 d was 52.5%.

**Calibration** (shipped predictor, predicted vs realised, deciles):

| decile | mean predicted | realised | n |
|---:|---:|---:|---:|
| 1 | 47.9% | 48.2% | 66,400 |
| 2 | 49.2% | 49.4% | 66,400 |
| 3 | 49.4% | 50.4% | 66,401 |
| 4 | 49.6% | 49.9% | 66,400 |
| 5 | 49.7% | 48.9% | 66,401 |
| 6 | 50.1% | 49.4% | 66,400 |
| 7 | 50.3% | 50.4% | 66,400 |
| 8 | 50.7% | 50.5% | 66,401 |
| 9 | 51.2% | 51.0% | 66,400 |
| 10 | 53.0% | 52.9% | 66,401 |

**By period** (bullish calls vs that period's base rate):

| period | base up-rate | shipped bullish: n / % up | old rules bullish: n / % up |
|---|---:|---:|---:|
| 2010-13 | 50.8% | 31,649 / 52.5% | 10,404 / 52.9% |
| 2014-19 | 51.0% | 35,861 / 52.4% | 11,832 / 54.1% |
| 2020-21 | 48.8% | 18,368 / 51.0% | 7,216 / 52.9% |
| 2022 | 52.0% | 9,621 / 53.8% | 2,418 / 53.1% |
| 2023 | 48.0% | 6,079 / 48.5% | 2,856 / 48.6% |
| 2024-26 | 48.6% | 12,716 / 51.8% | 7,275 / 50.2% |

80% price band: width multiplier 0.973 × σ√h covers 80.0% of outcomes.

<details><summary>Feature importance (total split gain)</summary>

| feature | share of gain |
|---|---:|
| S&P 500 3-month trend (`spy_ret63`) | 9.6% |
| S&P 500 drawdown (`spy_dd`) | 7.9% |
| Momentum vs S&P 500 (`rel_mom`) | 6.0% |
| Last week vs S&P 500 (`rel_ret5`) | 5.7% |
| VIX level (`vix_l`) | 5.6% |
| VIX spike (`vix_spike`) | 4.8% |
| S&P 500 last week (`spy_ret5`) | 4.5% |
| Biggest up-day this month (`max_ret21`) | 4.2% |
| Trend strength (ADX) (`adx_n`) | 4.0% |
| Volatility (`vol20`) | 3.9% |
| Last month vs S&P 500 (`rel_ret21`) | 3.9% |
| 12-month momentum (`mom121`) | 3.7% |
| Distance from 50-day (`d50`) | 3.7% |
| Drawdown from 1-year high (`dd252`) | 2.6% |
| Stretch from 200-day (`d200_sq`) | 2.6% |
| Yesterday's move (`ret1`) | 2.5% |
| Distance from 200-day (`d200`) | 2.3% |
| VIX change this week (`vix_chg5`) | 2.2% |
| S&P 500 death cross (`spy_dc`) | 2.2% |
| MACD histogram (`macd_n`) | 2.1% |

</details>

## Beat the S&P 500 — 10 trading days (2 weeks)

Base rate (share of US stock-days that beat the S&P 500 after 10 sessions): **50.2%**. Shipped predictor: **rules**.

| Predictor | set | AUC | Brier | Brier (base rate) | skill | top-10% up-rate | bottom-10% up-rate |
|---|---|---:|---:|---:|---:|---:|---:|
| **rules** | US (all OOS) | 0.504 | 0.2500 | 0.2500 | +0.01% | 51.4% | 49.6% |
| logistic | US (all OOS) | 0.508 | 0.2500 | 0.2500 | +0.01% | 52.3% | 49.5% |
| gbdt | US (all OOS) | 0.511 | 0.2500 | 0.2500 | +0.03% | 52.4% | 48.5% |
| **rules** | Holdout tickers | 0.503 | 0.2501 | 0.2501 | -0.00% | 50.9% | 49.6% |
| logistic | Holdout tickers | 0.504 | 0.2501 | 0.2501 | -0.02% | 51.1% | 49.5% |
| gbdt | Holdout tickers | 0.508 | 0.2500 | 0.2501 | +0.01% | 51.7% | 48.1% |
| **rules** | Oslo | 0.512 | 0.2500 | 0.2501 | +0.03% | 51.3% | 47.8% |
| logistic | Oslo | 0.512 | 0.2500 | 0.2501 | +0.02% | 51.4% | 46.3% |
| gbdt | Oslo | 0.517 | 0.2499 | 0.2501 | +0.07% | 52.7% | 46.2% |

AUC 0.5 = coin flip. Skill = 1 − Brier / Brier(base rate): above 0 means the probabilities beat "stocks usually go up".

**Calls with the shipped predictor (US, out of sample):**

| Call | threshold | share | n | % beat the S&P 500 after 10 d | avg excess return | vs all stocks |
|---|---:|---:|---:|---:|---:|---:|
| BUY | ≥ 52.2% | 3.3% | 21,663 | 53.6% | 0.57% | +0.41 pts |
| WEAK BUY | ≥ 51.2% | 1.2% | 8,096 | 51.0% | 0.36% | +0.19 pts |
| HOLD | — | 95.5% | 633,385 | 50.1% | 0.15% | -0.02 pts |

For reference, the old rules engine's own BUY labels (unchanged thresholds) fired on 6.3% of days and the share that beat the S&P 500 after 10 d was 52.2%.

**Calibration** (shipped predictor, predicted vs realised, deciles):

| decile | mean predicted | realised | n |
|---:|---:|---:|---:|
| 1 | 49.6% | 51.6% | 66,314 |
| 2 | 49.8% | 50.7% | 66,314 |
| 3 | 49.9% | 50.2% | 66,315 |
| 4 | 49.9% | 50.9% | 66,314 |
| 5 | 50.1% | 51.0% | 66,315 |
| 6 | 50.2% | 50.8% | 66,314 |
| 7 | 50.2% | 50.0% | 66,314 |
| 8 | 50.3% | 49.8% | 66,315 |
| 9 | 50.4% | 47.7% | 66,314 |
| 10 | 51.5% | 49.5% | 66,315 |

**By period** (bullish calls vs that period's base rate):

| period | base up-rate | shipped bullish: n / % up | old rules bullish: n / % up |
|---|---:|---:|---:|
| 2010-13 | 51.3% | 7,294 / 53.4% | 10,404 / 53.0% |
| 2014-19 | 51.4% | 7,727 / 55.3% | 11,832 / 53.8% |
| 2020-21 | 48.2% | 5,964 / 52.8% | 7,216 / 52.4% |
| 2022 | 52.7% | 1,482 / 53.6% | 2,418 / 54.8% |
| 2023 | 47.6% | 2,152 / 49.3% | 2,856 / 48.7% |
| 2024-26 | 48.0% | 5,140 / 49.8% | 7,169 / 49.1% |

80% price band: width multiplier 0.994 × σ√h covers 80.0% of outcomes.

## Beat the S&P 500 — 22 trading days (1 month)

Base rate (share of US stock-days that beat the S&P 500 after 22 sessions): **50.5%**. Shipped predictor: **rules**. Labels dropped because their own out-of-sample record did not beat the base rate: WEAK SELL/AVOID.

| Predictor | set | AUC | Brier | Brier (base rate) | skill | top-10% up-rate | bottom-10% up-rate |
|---|---|---:|---:|---:|---:|---:|---:|
| **rules** | US (all OOS) | 0.502 | 0.2501 | 0.2501 | -0.01% | 50.9% | 49.8% |
| logistic | US (all OOS) | 0.506 | 0.2501 | 0.2501 | +0.00% | 52.4% | 49.2% |
| gbdt | US (all OOS) | 0.507 | 0.2500 | 0.2501 | +0.01% | 52.8% | 49.5% |
| **rules** | Holdout tickers | 0.501 | 0.2501 | 0.2501 | -0.01% | 50.4% | 49.5% |
| logistic | Holdout tickers | 0.502 | 0.2501 | 0.2501 | -0.02% | 51.1% | 49.3% |
| gbdt | Holdout tickers | 0.505 | 0.2501 | 0.2501 | -0.00% | 52.1% | 49.1% |
| **rules** | Oslo | 0.509 | 0.2501 | 0.2501 | +0.02% | 51.3% | 49.2% |
| logistic | Oslo | 0.509 | 0.2500 | 0.2501 | +0.04% | 50.6% | 45.1% |
| gbdt | Oslo | 0.510 | 0.2501 | 0.2501 | +0.01% | 50.7% | 48.7% |

AUC 0.5 = coin flip. Skill = 1 − Brier / Brier(base rate): above 0 means the probabilities beat "stocks usually go up".

**Calls with the shipped predictor (US, out of sample):**

| Call | threshold | share | n | % beat the S&P 500 after 22 d | avg excess return | vs all stocks |
|---|---:|---:|---:|---:|---:|---:|
| WEAK BUY | ≥ 51.5% | 3.2% | 21,452 | 51.8% | 0.73% | +0.36 pts |
| HOLD | — | 96.8% | 639,628 | 50.5% | 0.35% | -0.01 pts |

For reference, the old rules engine's own BUY labels (unchanged thresholds) fired on 6.3% of days and the share that beat the S&P 500 after 22 d was 51.7%.

**Calibration** (shipped predictor, predicted vs realised, deciles):

| decile | mean predicted | realised | n |
|---:|---:|---:|---:|
| 1 | 49.8% | 51.4% | 66,108 |
| 2 | 50.1% | 50.3% | 66,108 |
| 3 | 50.3% | 54.4% | 66,108 |
| 4 | 50.4% | 51.9% | 66,108 |
| 5 | 50.5% | 52.1% | 66,108 |
| 6 | 50.5% | 51.8% | 66,108 |
| 7 | 50.6% | 50.2% | 66,108 |
| 8 | 50.7% | 48.2% | 66,108 |
| 9 | 50.8% | 46.4% | 66,108 |
| 10 | 51.3% | 48.1% | 66,108 |

**By period** (bullish calls vs that period's base rate):

| period | base up-rate | shipped bullish: n / % up | old rules bullish: n / % up |
|---|---:|---:|---:|
| 2010-13 | 52.4% | 5,338 / 55.2% | 10,404 / 54.0% |
| 2014-19 | 52.2% | 6,222 / 54.2% | 11,832 / 53.2% |
| 2020-21 | 48.1% | 2,772 / 48.9% | 7,216 / 51.5% |
| 2022 | 52.9% | 1,194 / 53.0% | 2,418 / 53.2% |
| 2023 | 45.9% | 1,603 / 49.6% | 2,856 / 49.7% |
| 2024-26 | 47.4% | 4,323 / 46.4% | 7,073 / 46.5% |

80% price band: width multiplier 1.018 × σ√h covers 80.0% of outcomes.

## Caveats

* Survivorship: tickers were chosen in 2026, so companies that failed are under-represented. Absolute probabilities may be a little optimistic; the *ranking* is what the holdout tests.
* Overlapping windows: consecutive days share most of their forward window, so row counts overstate the number of independent observations.
* News, analyst and catalyst inputs have no point-in-time history and are not part of the model — the app shows them as context only.
* Not investment advice. A 60% call is wrong 40% of the time.
