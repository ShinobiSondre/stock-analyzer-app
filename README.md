# StockAnalyzer (Android)

Native Android version of StockAnalyzer, built for the Galaxy Z Fold (cover screen and inner screen).

* `engine/` — pure-Kotlin analysis engine: indicators, setups, Tyler 4-stack, calibrated probability model.
* `trainer/` — walk-forward trainer that fits and validates the model on 20 years of daily data (runs in GitHub Actions).
* `app/` — the Android app (Jetpack Compose).

Work in progress.
