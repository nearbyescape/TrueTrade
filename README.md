# TrueTrade

A private, rule-based futures signal app for **BTC, ETH, SOL, Gold and Silver**. It is an Android app
with daily signals and alerts, and it has no servers, no AI and no Telegram.

- **`signal-app/engine`:** the strategy engine. It covers indicators, Binance/Bybit data, the version 3
  breakout-ensemble strategy, futures position sizing and the backtester, all with tests. See
  [signal-app/README.md](signal-app/README.md) for the strategy, the backtest results and their limits.
- **`signal-app/app`:** the Android app. It scans after each daily close (05:30 IST), notifies you of
  legs to enter or exit, and shows open legs with their stops, paper results, a journal and settings.

## Getting the app

Each push to `main` builds the APK in GitHub Actions and publishes it under **Releases**.

1. On your phone, open the repository's **Releases** page.
2. Download `app-debug.apk` from the latest release.
3. Open it and allow "install unknown apps" when Android asks.

New builds install over the old one and keep your settings, because the app is signed with the fixed
key in `signal-app/app/signing/`. Because that key is in this public repository, only install APKs from
this repository's own Releases page.

Not financial advice. Paper trade before using real money.
