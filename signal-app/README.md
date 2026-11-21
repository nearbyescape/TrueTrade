# Signal App: engine (phases 1–3, strategy version 3)

> **Current strategy: version 3, the breakout ensemble.** It runs 20, 30, 60 and 90-day breakouts per
> asset, each with a midpoint stop. It was picked from deep research, backtested on real Binance futures,
> and the scan and backtest tools now use it.
>
> - **Version 2** (single 55-day breakout) also passed, but trades about 6× less often.
> - **Version 1** failed.
>
> Both older versions stay in the code for comparison only.

## Version 3 strategy: breakout ensemble (`strategy/EnsembleStrategy.kt`)

Each asset runs four independent legs, one per lookback **N = 20, 30, 60 and 90 days**:

| Part | Rule |
| --- | --- |
| Long | A daily close is above the highest **close** of the previous N days, and above the 200-day EMA |
| Short | A daily close is below the lowest close of the previous N days, and below the 200-day EMA |
| Stop | The middle of the N-day closing range. It moves to each new middle only in your favour |
| Exit | A daily **close** beyond the stop. Act at the next open; intraday wicks don't count |
| When | Daily close, 00:00 UTC (05:30 IST); orders at the next open |
| Size | 0.5% of capital risked per leg, so up to 2% when all four legs on one asset are open |

**Where it comes from:** deep research (see the separate research report) found that only 1-to-4-week
trend rules survive retail fees. Hourly momentum, mean reversion and time-of-day rules don't. The design
follows Zarattini, Pagani & Barbon, "Catching Crypto Trends" (2025). I tested legs of
5/10/20/30/60/90 days, and the 5 and 10-day legs lost too much to fees, so they were dropped.

**Results**, on Binance futures with 0.05% fee + 0.03% slippage per side and real funding. Closed trades
only; legs still open at the end are listed separately.

| Watch list | Trades | Per week | Win rate | Avg per trade | Profit factor | Return at 0.5%/leg | Max drawdown |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Development, Jul 2023 to Jun 2025 | 172 | 1.7 | 40% | +0.83R | 2.52 | +96% | 15.6% |
| **Unseen, Jul 2025 to Nov 2026** | 112 | 1.5 | 46% | **+0.35R** | 1.76 | +21% | 5.5% |
| Full, Jul 2023 to Nov 2026 | 284 | 1.6 | 43% | **+0.64R** | 2.25 | **+138%** | 15.6% |
| Unseen, gold history via PAXG | 133 | 1.8 | 50% | +0.61R | 2.36 | +49% | 4.7% |

On 20 November, 20 more legs were open with +50R unrealised. Those aren't counted.

Compared with version 2 on the same assets and period, version 3 has about **7× the trades** (about 7 a
month against 1), a higher R per trade (+0.64R against +0.38R) and far more total profit.

**Honest limits:**
- **Losing streaks:** legs on one asset win and lose together, so long losing streaks happen. The worst
  was 27 losing legs in a row (2024–25), costing about 15% at 0.5% per leg.
- **Gold on the real XAUUSDT contract** has lost so far: 15 trades at −0.45R. That is a short sample
  (the contract dates from Dec 2025), and gold history via PAXG was strongly positive.
- **Silver:** only 2 closed trades so far.
- **Weak year:** 2025 was weak (+0.08R per trade on the watch list).
- **Mild selection:** the four legs were picked after seeing per-leg results, which slightly flatters the
  numbers. The development and unseen periods were both positive.
- **Market conditions:** 2023–2026 contained strong trends.
- **Paper trade first.**

```bash
./gradlew :engine:scan --args="100000 0.5 2"     # after 05:30 IST: today's leg entries/exits and the stop for every open leg
./gradlew :engine:backtest                        # version 3 backtest (ensemble is the default)
```

The engine is the core of the app. It downloads futures candles, calculates indicators, and decides
signals. It is plain Kotlin with no Android code, so it is tested on a PC. The Android app (phase 4)
uses it unchanged.

**Futures only.** Every asset is a USDT perpetual, so the engine signals longs **and** shorts.

## Watch list

| Asset | Main data | Backup data | Notes |
| --- | --- | --- | --- |
| BTC | Binance futures BTCUSDT | Bybit perp BTCUSDT | |
| ETH | Binance futures ETHUSDT | Bybit perp ETHUSDT | Blocked while BTC's 4H trend is against the trade |
| SOL | Binance futures SOLUSDT | Bybit perp SOLUSDT | Blocked while BTC's 4H trend is against the trade |
| GOLD | Binance futures XAUUSDT | Bybit perp XAUTUSDT | No BTC filter; no signals Fri 21:00 to Sun 23:00 UTC |
| SILVER | Binance futures XAGUSDT | Bybit perp XAGUSDT | No BTC filter; no signals Fri 21:00 to Sun 23:00 UTC |

To add a coin: `Assets.crypto("XRP", "XRP")`. The Settings screen will do this in phase 6.

Binance listed its gold and silver perpetuals in January 2026, so they have about 10 months of history:
enough for the indicators, but short for backtesting.

The loader never mixes exchanges within one scan. If Binance fails partway through, all timeframes are
reloaded from Bybit.

## Phase 2: signal engine (`strategy/`)

Every hour, at each 1H candle close, `SignalEngine` checks each asset and direction in this order. The
first check that fails is reported with a plain-language reason.

1. **Data and timing:** enough history, the market is open (metals), and it's not inside a news blackout.
2. **Trend:** the daily close is beyond the 200 EMA; the 4H EMA 50 is beyond the EMA 200 and still sloping
   that way; 4H ADX is at least 20.
3. **BTC filter** (ETH, SOL): blocks the trade if BTC's 4H EMA 50 is against it.
4. **Volatility:** 4H ATR is between 0.5× and 2× its usual level.
5. **Setup** in the last 6 hours: a pullback to the 4H EMA 50, a 0.5–0.618 retracement, a retest of a 4H
   breakout, or a sweep of a 1H swing low that was then reclaimed.
6. **Trigger:** an engulfing candle or a 1H break of structure.
7. **Volume:** at least the average of the previous 20 candles.
8. **Stop:** below the setup's low with a buffer. It must be no wider than 1.5 × 4H ATR and no tighter
   than 0.5 × 1H ATR.
9. **Room:** the nearest unbroken 4H or daily swing high (for shorts, swing low) must be at least 3R away.

A setup that passes all nine is scored out of 100:

| Part | Max |
| --- | --- |
| Trend | 25 |
| Entry location | 20 |
| Trigger | 15 |
| Volume | 15 |
| Room | 15 |
| Context | 10 |

85 or more is A+, 75 or more is an alert, and anything lower is logged only.

**Trade plan:** take 40% at 1.5R and move the stop to entry, take 30% at 3R, and let the last 30% run
behind a trailing stop of 3 × 4H ATR.

**Shorts** use the long rules on price-flipped data, so the two directions can't drift apart.

### Futures position sizing (`strategy/Risk.kt`)

- The size is set by risk, not by leverage: a stop-out costs your chosen % of capital, fees included
  (default fee: Binance taker 0.05%).
- Leverage only changes the margin locked and how close liquidation is.
- Every size comes with an estimated liquidation price. It is flagged if liquidation would come before
  the stop, and `maxSafeLeverage` gives the highest leverage that keeps liquidation at least 2× further
  away than the stop.

### Alert cooldown

There is a 12-hour cooldown per asset and direction, but a higher-grade setup still gets through.

## Tests: 76, all passing

**Phase 1 (indicators and data):**
- Hand-worked cases for every formula.
- Matches TA-Lib to about 15 decimal places.
- Covers retries, rate limits, fallback to Bybit, paging and offline catch-up.

**Phase 2 (strategy):** tested on 420 days of synthetic hourly markets, trending up and down.

- **No look-ahead:** every signal bar, and a sample of other bars, is judged again with all future candles
  removed. The result must be identical. A deliberately planted peek at the still-forming 4H candle was
  caught at once.
- **Every signal obeys every hard gate:** stop width, 3R room, the TP levels, the score maximums, and a
  grade that matches its score.
- **Shorts mirror longs exactly:** same scores, same setups, prices flipped.
- **Filters block what they should:**
  - With BTC in a 4H downtrend, all ETH longs are blocked.
  - Metals give no signals over the weekend.
  - The news blackout works.
  - Every one of the 12 gates can fire.
- **Data never mixes exchanges:** if Binance fails partway through a load, everything is reloaded from Bybit.
- **Resampler:** incomplete 4H or daily periods are dropped.
- **Sizing:** fees, leverage and margin, liquidation estimates, and step rounding.

Synthetic data proves the rules work as written. Whether they **make money** is phase 3's job: a backtest
on real exchange history, with fees, slippage and funding.

## Run it on your PC

You need JDK 17 or newer. Android Studio already includes one.

```bash
./gradlew :engine:test                          # all 76 tests
./gradlew :engine:verify --args="BTC 1h"        # indicator values vs TradingView (BINANCE:BTCUSDT.P)
./gradlew :engine:verify --args="GOLD 4h"
./gradlew :engine:scan --args="100000 1 2"      # live signals: capital, risk %, leverage
```

The scan shows, for each asset, what the newest closed 1H candle decided and any signals in the last 72
hours. Each signal comes with its entry, stop, TP1, TP2 and target, the position size, margin, estimated
liquidation price, and the reasons it fired.

The workspace this was built in can't reach the exchanges or Maven, so the code was compiled and tested
there with Kotlin 2.0.21 directly. Running the Gradle build and the live tools on your PC is the first
real-data check.

## Phase 3: backtester (`history/`, `backtest/`)

- **`BinanceArchive`** downloads Binance's official futures history and funding rates from
  data.binance.vision. It uses monthly files plus daily files for the current month, and caches them on
  disk.
- **`Backtester`** simulates the portfolio hour by hour, as you would trade it by hand:
  - Entry at the next candle's open, with slippage. The trade is skipped if price has run more than 0.5R.
  - TP1 at 1.5R (40%), then the stop moves to entry.
  - TP2 at 3R (30%), then the stop moves to +1R. The runner trails 3 × 4H ATR behind the best price.
  - A 48-hour time stop.
  - Fees: taker 0.05% on market orders, maker 0.02% on take-profits.
  - Funding is paid or received every 8 hours.
  - Portfolio limits: max 3 open trades, max 2 per group, a 12-hour cooldown, and daily (−2R) and weekly
    (−5R) loss limits.
- **Worst case inside each hour.** Hourly candles don't show the order of the high and the low, so the
  simulator always assumes the worse one. For example, a candle that touched both the stop and a target
  counts as a stop-out.
- **`RunBacktest`** splits the period 70/30. Settings are chosen on the first 70% only, and the last 30%
  gives the honest estimate. It writes `summary.txt` and `trades.csv`.

```bash
./gradlew :engine:backtest                                  # version 2, 2023-01 to yesterday
./gradlew :engine:backtest --args="v1 2023-01 2026-11-20"   # version 1, for comparison
```

## Version 2 strategy (superseded by version 3): daily breakout trend-following (`strategy/TrendStrategy.kt`)

| Part | Rule |
| --- | --- |
| Long | A daily candle closes above the highest high of the previous 55 days, the day before it hadn't, and the close is above the 200-day EMA |
| Short | The mirror: a close below the 55-day low, below the 200-day EMA |
| When | Only at the daily close (00:00 UTC, 05:30 IST); entry at the next candle's open |
| Stop | 2 × daily ATR(14) from entry |
| Exit | No fixed target. The whole position trails 5 × ATR behind the best price, and the stop only moves in your favour |
| Metals | No new signals from Friday's close (the underlying market is closed) |

**How it was chosen.** I tested 16 variants (4H or daily, 20 or 55 bars, stop 2 or 3 ATR, trail 3 or 5
ATR) on July 2023 to June 2025 only. This setting was strong on the watch list **and** on 12 other coins,
and its neighbouring settings were also positive. It was then locked and run once on July 2025 to
November 2026, which it had never seen.

| Period (watch list) | Trades | Win rate | Avg per trade | Profit factor | Max drawdown |
| --- | --- | --- | --- | --- | --- |
| Development, Jul 2023 to Jun 2025 | 26 | 34.6% | +0.29R | 1.46 | 7.2R |
| **Unseen, Jul 2025 to Nov 2026** | 16 | 37.5% | **+0.44R** | 1.72 | 5.8R |
| Full, Jul 2023 to Nov 2026 | 41 | 36.6% | **+0.38R** | 1.64 | 7.2R |
| Unseen, gold history via PAXG | 21 | 47.6% | +1.07R | 3.17 | 3.6R |
| Unseen, 12 other coins (check) | 20 | 45.0% | +0.60R | 2.21 | 6.7R |

- **Typical result:** average win +2.7R, average loss −0.95R, average hold about 6 weeks.
- **Every year was positive:** 2023 +7.1R, 2024 +3.5R, 2025 +2.9R, 2026 +2.3R.
- **Return:** at 1% risk per trade, +16% over 3.3 years with a 7% max drawdown. At 2% risk, roughly
  double both.

**Honest limits:**
- **Few trades:** about 1 every 4 weeks across the watch list.
- **Concentrated profits:** a handful of big winners make most of the profit.
- **Expect losing streaks of 6–7 trades.**
- **Gold and silver futures:** only 3 trades so far.

Samples this size prove direction, not precision. Paper trade it before using real money.

### Results for version 1 rules: NO-GO

Real Binance futures, July 2023 to November 2026, after fees, slippage and funding:

| Test | Trades | Win rate | Avg per trade | Profit factor |
| --- | --- | --- | --- | --- |
| Watch list (BTC, ETH, SOL, gold, silver), score 75+ | 75 | 42.7% | −0.06R | 0.91 |
| Same rules on 15 large coins, score 75+ | 209 | 40.7% | −0.14R | 0.79 |
| Same rules on 15 large coins, score 80+ | 101 | 43.6% | −0.06R | 0.91 |
| Gold on PAXG history, 2023–2026 | 29 | 27.6% | −0.74R | 0.20 |

What this shows:
- **No edge before costs:** trades average −0.09R even before fees are taken off.
- **Fees cost about 0.07R per trade**, because stops based on 1H candles are tight.
- **Very few signals:** about 0.4 per week across the whole watch list, against the 2–5 planned.

These rules must not be traded. The engine, data layer, backtester and tests are all sound and are kept;
only the strategy rules need replacing. Any new rule set is judged by this same backtester before use.

Tests: 118, all passing. Version 3 added 8 tests: a hand-worked leg (entry, stop moves, exit, then a short),
next-open fills with exact costs, funding, the trend filter, shorts on and off, small-stop skips,
independent legs, and no look-ahead. The production backtest reproduces the research numbers exactly.

Before version 3: 110 tests.
- Phase 3 added 23 backtester tests: every exit type, trail-only mode, fees, funding, limits, and the
  worst-case candle rule.
- It also added 3 archive tests.
- Version 2 added 8 tests: breakout timing, no late entries, daily-close-only signals, the trend filter,
  mirrored shorts, the gold weekend rule, no look-ahead, and trail backtests.
