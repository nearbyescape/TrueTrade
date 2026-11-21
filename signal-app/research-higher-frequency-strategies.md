# Widen the universe, not the timeframe

The strategies with credible, cost-inclusive evidence of positive risk-adjusted returns on crypto perps and metals are almost all **trend or momentum rules on one-to-four-week horizons**. Examples include multi-lookback Donchian ensembles, 7-28 day time-series momentum, and 4-6 hour momentum with ATR trailing stops. These can trade several times a week across 10-17 assets because each asset contributes about one trade a month, not because any single asset trades intraday. The evidence is consistent that **edges at hourly and sub-hourly horizons are real but smaller than a 0.05% taker fee**. Intraday BTC momentum breaks even at 3-10 bps per trade, 15-minute reversal earns about 1.3 bp gross, and the famous 21:00-23:00 UTC BTC hour earns about 9 bps per trade before costs. The failed 1H pullback strategy fits this pattern. With tight hourly stops, a roughly 16 bps round-trip cost consumes 0.15-0.3R per trade, which is more than a 1:3 target system's typical gross edge. Funding carry on majors has collapsed (median Binance funding fell from 13.4% to 0.6% annualised between 2024 and 2026). Cross-sectional momentum needs a larger universe than 5 coins and is unproven after 2022. Gold and silver reward slow trend and offer only one interesting faster idea, the overnight-long session effect. The ranked shortlist below puts a faster Donchian ensemble first, then 4H momentum with an ATR trail, 28/5-day TSMOM, weekly relative-strength rotation, and a speculative gold overnight rule. All five share one test protocol that prices fees, slippage, funding and manual fill delay in R.

## Costs measured in R explain the failed 1H strategy

The most useful lens for this decision is cost per trade in R, not in percent. All-in round-trip cost on Binance USD-M is about 10 bps in fees (0.05% taker each side) plus 2-5 bps of slippage per side, so roughly **14-20 bps per round trip**. For long holds you also pay funding. Binance's baseline BTC funding of **0.01% per 8 hours is about 11% a year** ([Wharton](https://finance-pillar.wharton.upenn.edu/wharton-crypto-insights/ethenas-synthetic-dollar-token/)), so a three-day long in a normal bull regime pays about 9 bps of funding, close to half the trading cost. Dividing round-trip cost by stop distance gives the cost in R:

| Initial stop distance (% of price) | Typical context | Cost in R at 16 bps round trip |
|---|---|---|
| 0.5% | 1H sweep/retest stop | **0.32R** |
| 1% | 1H pullback stop | **0.16R** |
| 2% | 4H ATR stop | 0.08R |
| 4% | 4H-6H chandelier, 5-10 day Donchian midpoint | 0.04R |
| 8% | Daily 2×ATR (current validated system) | 0.02R |

This is arithmetic, not a sourced result, but it explains both of the user's results. A 1:3 target system that wins 30% of the time has a gross expectancy of 0.3×3 − 0.7 = +0.2R. A 0.16-0.32R cost wipes that out, which matches the observed −0.06 to −0.14R. The daily breakout pays about 0.02R per trade and keeps almost all of its +0.38R. The design rule follows: **a faster strategy must keep stops at roughly 3% of price or wider, or its gross edge per trade must exceed about 30 bps.** The literature points the same way. BTC intraday momentum earns only **0.03-0.10% per trade** ([Shen, Urquhart & Wang](https://reading-9.eprints-hosting.org/100181/3/21Sep2021Bitcoin%20Intraday%20Time-Series%20Momentum.R2.pdf)), well below a 14-20 bps round trip, while the multi-day trend designs below average 0.6-1.8% per trade in their favourable regimes ([arXiv 2602.11708](https://arxiv.org/html/2602.11708v1)).

Frequency is mostly a matter of how many assets you trade. The validated system produces about 0.26 trades per asset per month (1.3 a month on 5 assets). Several trades a week, say 10-20 a month, can come from 17 assets each trading about once a month. A useful benchmark: the 6-hour Binance perp trend system discussed below logged **about 142 trades a month across 150+ perps**, roughly one per coin per month ([arXiv 2602.11708](https://arxiv.org/html/2602.11708v1)). Moving the same rule set from 5 to 17 assets, and from a 55-day to a 10-30 day horizon, reaches the target frequency without leaving the cost-safe zone.

## One-to-four-week trend survives 15 bps; intraday trend does not

### The evidence converges on 7-28 day lookbacks

Every serious crypto momentum study puts the edge at horizons of days to weeks. In BTC daily data, today's return predicts returns 1-6 days ahead, and weekly returns predict the next 1-4 weeks. Top-quintile weeks were followed by **11.22% a week on average, versus 2.60% after bottom-quintile weeks** ([Liu & Tsyvinski, NBER](https://www.nber.org/system/files/working_papers/w24877/w24877.pdf)). ETH's momentum was weaker and less consistent ([Liu & Tsyvinski, NBER](https://www.nber.org/system/files/working_papers/w24877/w24877.pdf)). The most realistic academic test is Han, Kang & Ryu. It uses survivorship-free data from 2013 to 2023 and charges 15 bps per trade. It screened lookbacks of 7-28 days and holds of 1-14 days. The best long-only rule, a **28-day lookback with a 5-day hold, returned 72.85% a year at a Sharpe of 1.51 after costs**, against a market Sharpe of 0.85. It was invested about 48% of the time, and costs reduced Sharpe by only 0.05 ([Han, Kang & Ryu](https://acfr.aut.ac.nz/__data/assets/pdf_file/0009/918729/Time_Series_and_Cross_Sectional_Momentum_in_the_Cryptocurrency_Market_with_IA.pdf)). It beat the market's Sharpe in 8 of 10 years. Its edge sits mostly in bull markets and fades when both lookback and hold exceed 28 days ([Han, Kang & Ryu](https://acfr.aut.ac.nz/__data/assets/pdf_file/0009/918729/Time_Series_and_Cross_Sectional_Momentum_in_the_Cryptocurrency_Market_with_IA.pdf)). One caveat for the shortlist: that study applied the signal to a value-weighted market index, so using it per coin is an extrapolation.

Hourly bars do not reveal a separate short-horizon edge. A decade-long walk-forward study of hourly BTC moving-average crossovers found the best settings at **SMA 141/781h, EMA 721/951h and DEMA 791/981h**, all hundreds of hours long. Walk-forward Sharpe was 0.5-1.5 at zero cost, and the authors reported a "notable absence of profitable intra-day trend following strategies" ([arXiv 2009.12155](https://ar5iv.labs.arxiv.org/html/2009.12155)). At the extreme short end, BTC's half-hour momentum around the US close is statistically significant (out-of-sample R² of 1.1-1.8%). But its **break-even costs are 3, 7 and 10 bps per trade** for its three variants, and it works only on high-volume, high-volatility days ([Shen, Urquhart & Wang](https://reading-9.eprints-hosting.org/100181/3/21Sep2021Bitcoin%20Intraday%20Time-Series%20Momentum.R2.pdf)). The practical reading is to compute signals on hourly bars for timely entries while trading 1-4 week information.

### Ensembles and 4-6 hour bars are the best-documented "faster" designs

The strongest cost-tested design is Zarattini, Pagani & Barbon's ensemble of Donchian close-breakouts. It uses lookbacks of **5, 10, 20, 30, 60, 90, 150, 250 and 360 days**. Each leg goes long when the close exceeds the lookback's maximum close and exits on a ratchet stop at the midpoint of that lookback's max and min closes. Legs are equal-weighted, and each position is sized to 25% annual volatility with leverage capped at 200% ([CXO Advisory](https://www.cxoadvisory.com/technical-trading/crypto-asset-trend-following-strategies/)). On a rotating top-20 liquid-coin universe from 2015 to March 2025, it reports a **net Sharpe above 1.5 and about 10.8% annual alpha versus BTC** at 0.10% cost, with cost tiers up to 0.50% tested ([Concretum Group](https://concretumgroup.com/catching-crypto-trends-a-tactical-approach-for-bitcoin-and-altcoins/)). Win rate, trade count and drawdown were paywalled. The design matters for this user because the validated 55-day breakout is essentially one leg of this ensemble. The faster legs add trades, and the ensemble ramps exposure from 1/9 to 9/9 as more lookbacks break out, which acts as a smooth, evidence-based form of pyramiding.

The only direct cost-inclusive comparison across bar sizes on Binance perps comes from Bui & Nguyen's "AdaptiveTrend". It uses momentum (return over L bars) above a threshold θ with an ATR trailing stop, tested on 150+ perps with 4 bps taker, volume-based slippage and 8-hour funding. Its out-of-sample Sharpe by bar size forms a clear hump ([arXiv 2602.11708](https://arxiv.org/html/2602.11708v1)):

| Bar | Sharpe | Max DD | Trades/month (portfolio) |
|---|---|---|---|
| H1 | 1.54 | −21.3% | 847 |
| H4 | 2.08 | −15.6% | 213 |
| H6 | **2.41** | −12.7% | 142 |
| H12 | 1.91 | −16.8% | 76 |
| D1 | 1.63 | −19.4% | 41 |

Sharpe falls by about 0.1 per extra basis point of cost, from **2.87 at 0 bps to 1.62 at 12 bps**. Removing the ATR trailing stop cut Sharpe from 2.41 to 1.68 ([arXiv 2602.11708](https://arxiv.org/html/2602.11708v1)). Treat the levels with suspicion. The H6 bar was chosen using the out-of-sample table, the reported Sharpe does not reconcile with its own return and volatility, the universe looks survivorship-biased, and **fixed parameters gave Sharpe 1.34 versus 2.41 with monthly re-optimisation** ([arXiv 2602.11708](https://arxiv.org/html/2602.11708v1)). The shape of the result is still informative: 4-8 hour bars are where speed stops paying for its turnover. At this user's cost of about 8 bps per side, double the paper's assumption, the H1 penalty would be larger still. Monash's trend project reaches the same qualitative conclusion: shorter lookbacks have high Sharpe for BTC and ETH, but rebalancing costs are a significant drag ([Monash MCFS](https://www.monash.edu/business/mcfs/our-research/all-projects/investment-strategy/trend-following-strategies-for-crypto-investors)).

### Volatility scaling helps; pyramiding mostly adds leverage

Volatility targeting is the best-supported sizing change. Man Group finds that sizing BTC inversely to its volatility adds **"around 40 Sharpe points"** from 2012 to 2024, whatever half-life is used for the volatility estimate, and also improves expected shortfall ([Man Group](https://www.man.com/insights/crypto-too-hot-to-handle)). In AdaptiveTrend's 2022-2024 benchmarks, vol-scaled TSMOM at a 10% target had Sharpe 1.83 against 0.65 for raw 1-month TSMOM ([arXiv 2602.11708](https://arxiv.org/html/2602.11708v1)). The only pyramiding study found is old and not about crypto (US T-bonds, 1977-1999). Pyramiding raised net profit almost sixfold but left **profit factor at 1.60 versus 1.58**, so the gain was amplification, not edge ([TradingMarkets](https://tradingmarkets.com/?p=669138)). Any pyramiding test should therefore be compared with an unpyramided version at matched average volatility, with every add-on charged a taker fill.

### Decay is regime-shaped, not a collapse

No source shows crypto trend dying after 2022. The Zarattini sample includes the 2022 bear market and still nets Sharpe above 1.5 through March 2025 ([Concretum Group](https://concretumgroup.com/catching-crypto-trends-a-tactical-approach-for-bitcoin-and-altcoins/)). The recurring weakness is bear and sideways markets. AdaptiveTrend's bear-regime Sharpe was **−0.31 with a 41.3% win rate**, against 3.42 in bull regimes ([arXiv 2602.11708](https://arxiv.org/html/2602.11708v1)), and Han et al. find momentum concentrated in bull markets. This matters directly for a 2023-2026 backtest window, which is mostly a crypto bull market. Such a window will flatter any long-biased trend rule. If Binance's 2021-2022 perp klines can be added, the 2022 bear should be included as a stress test, and long and short legs should be reported separately.

## Cross-sectional momentum and funding carry add little at 5-17 assets

### Relative strength works on paper, in the winner leg, in large coins

Cross-sectional momentum has the canonical evidence from Liu, Tsyvinski & Wu's *Journal of Finance* paper. Weekly-rebalanced quintile spreads were significant at 1-4 week lookbacks, peaking at **4.1% a week for 3-week momentum (t = 2.74)**. Lookbacks of 8 weeks or more were insignificant ([NBER WP 25882](https://www.nber.org/papers/w25882.pdf)). The spread came mainly from winners, and it held when BTC replaced the loser short leg (3.0% a week). It was concentrated in above-median-size coins, at 4.2% a week versus 0.6% below median ([NBER WP 25882](https://www.nber.org/papers/w25882.pdf)). That sample is gross of costs and ends in 2018. A later machine-learning combination of 28 technical signals (CTREND) earned **3.87% a week (Sharpe 1.94) through May 2022**. It was still 3.39% a week in the top 100 coins and is reported to survive 30-40 bps costs ([JFQA](https://www.cambridge.org/core/services/aop-cambridge-core/content/view/S0022109024000747)). The cautionary evidence is just as strong. Han, Kang & Ryu call cross-sectional momentum "weak", find long-only beating long-short, and document **five long-short portfolios that were fully liquidated** in their sample ([Han, Kang & Ryu](https://acfr.aut.ac.nz/__data/assets/pdf_file/0009/918729/Time_Series_and_Cross_Sectional_Momentum_in_the_Cryptocurrency_Market_with_IA.pdf)). The only post-2022 figure, Artemis's 75% a year for a 3-week factor, is explicitly gross of costs ([Artemis](https://research.artemis.ai/p/crypto-factor-model-analysis)).

For this user, the binding constraint is breadth. Most studies rank 30 or more coins. With 5 assets, "relative strength" collapses into a time-series filter. With 17 it is borderline. The workable version ranks a wider top-20-to-30 Binance universe (hourly data for these is available) and trades only the top 3-5 long, rebalancing weekly. That gives about 2-6 order tickets a week and is manually feasible. Costs are the main drag. Replacing half of a 5-name book every week at about 8 bps per side costs roughly 35-50 bps of traded notional a week, so a hysteresis band is needed, for example replacing a holding only when it drops out of the top 2N.

### Short-term cross-sectional reversal belongs to illiquid coins

Daily reversal is large in the broad crypto cross-section, but it is an illiquidity premium. Among 3,600+ coins, previous-day losers beat winners, while **"the handful of largest and most tradeable coins" show daily momentum instead** ([Zaremba et al., IRFA](https://ideas.repec.org/a/eee/finana/v78y2021ics1057521921002349.html)). In the top 100 pairs, value-weighted reversal had no significant net return, and **on Binance net returns were negative** ([Bianchi, Babiak & Dickerson, Riksbank WP](https://www.riksbank.se/globalassets/media/rapporter/working-papers/2022/no.-413-trading-volume-and-liquidity-provision-in-cryptocurreny-markets.pdf)). Reversal is not a candidate for a large-cap perp book.

### Funding carry has been arbitraged down to a cash-like yield

Delta-neutral carry was once lucrative. BIS researchers found BTC and ETH futures carry averaged **above 10% a year and peaked near 60%**, and they found that **high carry predicts crashes** ([BIS WP 1087](https://bis.org/publ/work1087.htm)). He, Manela, Ross & von Wachter estimated a perp-spot arbitrage Sharpe of about 1.8 for BTC at retail costs over 2020-2022, but also found deviations shrinking by about 11% a year ([arXiv 2212.06888](https://arxiv.org/html/2212.06888v5)). The decay has since arrived. A practitioner study of 30 Binance coins from 2023 to May 2026 found **median annualised funding falling from +13.4% in 2024 to +0.6% in 2026**. Level carry net of 6 bps per side returned +5.6% a year over the full sample but turned negative by 2026 ([Vine](https://bryanvine.github.io/alpha-research/paper2.html)). BTC's 30-day average funding stayed below zero for **67 consecutive days through May 2026**, CME basis fell to about 3% (below Treasuries), and funding then rebounded to about 8% annualised by end-October ([The Block](https://theblock.co/news/markets/2026-11-19-sygnum-bitcoin-carry-trades-beating-treasury-bills-2027-443547)). A cross-sectional funding factor (long low-funding coins, short high-funding coins, weekly) had a purged walk-forward out-of-sample **Sharpe of only 0.39, with a probability of backtest overfitting of 0.81** ([Vine](https://bryanvine.github.io/alpha-research/paper2.html)). Cross-exchange funding arbitrage made money in only 8 of 20 top opportunities after costs ([MDPI Mathematics](https://www.mdpi.com/2227-7390/14/2/346)). None of these is a directional signal app strategy.

Funding as a directional signal fares no better. A BitMEX test that faded funding beyond ±1-2σ had hit rates of 44-53%, and most of its gain came from funding received rather than price ([BitMEX](https://www.bitmex.com/blog/xbtusd-funding-mean-reversion-strategy)). A regression of next-8h returns on funding gave an R² of 0.0011 ([Brave New Coin](https://bravenewcoin.com/insights/testing-theory-is-perpetual-swap-funding-rate-predictive)). Past momentum explains more than half of the perp-spot gap ([arXiv 2212.06888](https://arxiv.org/html/2212.06888v5)), so using funding as an entry signal largely double-counts the trend. Funding's defensible role is as a **crowding veto**: skip new longs when funding is extreme. One slower signal is worth noting. In K33's data, buying BTC during negative 30-day funding beat random entry 83-96% of the time over 30-360 day horizons. That evidence is secondhand and covers few regimes ([The Block / K33](https://www.theblock.co/news/markets/2026-05-06-bitcoin-longest-negative-funding-streak-this-decade-k33-flags-short-squeeze-risk-400182)), and the horizon is far too slow to trade frequently.

## Mean reversion and clock-based edges are real but smaller than the fee

### Short-horizon reversal is statistically robust and economically dead for majors

The best recent measurement comes from Kitron & Wengrowicz. They studied 183 Binance pairs on 15-minute bars from 2025 to 2026, with a holdout to August 2026. **90% of pairs show significant reversal, but the gross edge peaks near 1.3 bp per trade (median 0.46 bp), and no pair clears even a 5 bp round trip.** The signal decays to nothing within about 4 hours ([arXiv 2608.21888](https://arxiv.org/pdf/2608.21888)). Reversal is stronger after aggressive taker-flow moves, which fits a liquidity-provision premium that market makers, not taker-fee traders, collect. Older work found significant negative autocorrelation in BTC at 1-4 hours, larger after bigger moves ([De Nicola, Ledger](https://ledger.pitt.edu/ojs/ledger/article/view/213)). Since then, BTC's short-interval Hurst exponent has moved from about 0.42 toward 0.49, close to a random walk ([Tang et al.](https://arxiv.org/abs/2402.11930v2)). On daily bars, Quantpedia's out-of-sample revisit (2022-2024) found that buying BTC at 10-50 day lows **"performs poorly, with low or negative returns"**, while buying at highs "remains effective" ([Quantpedia 2024](https://quantpedia.com/revisiting-trend-following-and-mean-reversion-strategies-in-bitcoin/)). The one bullish dip-buying claim is an RSI(2) < 30 rule above the 50-day SMA, run across 500+ Binance futures, reporting Sharpe "almost 2". It is self-reported, gives no fee or slippage rates, and probably draws on altcoin dispersion ([Setup4Alpha](https://setup4alpha.substack.com/p/this-is-how-an-algorithmic-crypto-realtest)). The user's failed 1H pullback strategy lines up with this literature. Large-coin crypto rewards continuation, and hourly dips are too small relative to fees.

### Time-of-day and day-of-week effects fail costs or multiple-testing checks

The best-known BTC hour effect, long from 21:00 to 23:00 UTC, came from in-sample Gemini data with no costs ([Quantpedia](https://quantpedia.com/are-there-seasonal-intraday-or-overnight-anomalies-in-bitcoin/)). An independent Binance replication from 2017 to 2026 found an **expectancy of 0.09% per trade over 2,504 trades, and a permutation test p-value of about 0.42**. The author abandoned it ([notp1ssed](https://notp1ssed.github.io/portfolio/assets/docs/001_Seasonality_Backtest.html)). A 300-rule search of 12-hour session splits on BTC and ETH failed Hansen's SPA test, and the best BTC rule **underperformed buy-and-hold in its 2021-2025 holdout** ([Wu & Pinsky, JRFM](https://www.mdpi.com/1911-8074/19/9/692)). Crypto day-of-week seasonality is not robust, and the BTC Monday effect disappears after 2015 ([Mueller, FRL](https://ideas.repec.org/a/eee/finlet/v64y2024ics1544612324004598.html)). Two calendar ideas survive only as filters on a trend system. Concretum finds its BTC intraday trend ensemble (gross Sharpe about 1.6, 2018-2025, rules undisclosed) works best from about the Sunday-evening Tokyo open through Monday ([Concretum](https://concretumgroup.com/seasonality-in-bitcoin-intraday-trend-trading/)). Quantpedia's out-of-sample rule since October 2021 goes long at the NYSE close only when BTC is at a 10-day high, holds overnight, and returned "over 35%" with about −12% drawdown. Costs were not discussed ([Quantpedia 2024](https://quantpedia.com/?p=36811)). Both are, in effect, trend signals with a clock attached.

### Volatility breakouts have strong equity evidence but no crypto cost test

Zarattini, Aziz & Barbon's "Noise Area" intraday breakout is the best-documented intraday breakout. It sets bands from the 14-day average absolute move from the open at each time of day, enters on a breakout, trails at the band or VWAP, and exits at the close. On SPY it earned **a Sharpe of 1.33 net of commissions and slippage** over 2007-2024, with a 37% hit rate and an average Sharpe of 0.60 across 33 futures markets ([Zarattini et al.](https://alexandria.unisg.ch/server/api/core/bitstreams/a99aba00-f967-49b3-aceb-f544dc386e0b/content)). Its two post-publication months (January and February 2025) lost 1.2%. No cost-inclusive crypto version exists. Larry Williams and Dual Thrust daily-range breakouts have only forum-grade crypto evidence. These designs flatten every day, so the stop distance is an intraday band, and the cost-in-R problem from the first section returns. They are lower priority than multi-day trend.

## Gold and silver reward slow trend and punish fast trading

The long-history evidence for metals is diversified trend at 1-12 month horizons. Moskowitz, Ooi & Pedersen find TSMOM profitable at 1-12 months across 58 futures, including gold and silver, strongest at 12 months and partially reversing beyond ([qgtm-docs summary](https://qgtm-docs.pages.dev/research/gold_silver/paper_moskowitz_tsmom)). AQR's century study blends 1-, 3- and 12-month signals and assumes one-way commodity costs of 0.10% after 2003. It reports a **net Sharpe of 0.77** for the diversified book ([Hurst, Ooi & Pedersen](https://www.TrendFollowing.com/whitepaper/Century_Evidence_Trend_Following.pdf)). Across 28 commodities, channel and dual-MA rules were net-positive in at least 22 markets for every parameter set ([Szakmary, Shen & Sharma](https://econpapers.repec.org/RePEc:eee:jbfina:v:34:y:2010:i:2:p:409-426)). Gold traded alone is weaker. An unverified replication puts gold's standalone TSMOM Sharpe at about 0.4, with losing spells such as 2013-2015 ([qgtm-docs](https://qgtm-docs.pages.dev/research/gold_silver/paper_moskowitz_tsmom)). Silver futures had a **−5.66% a year drift at 31.5% volatility** from 1980 onward, versus +3.25% at 18% for gold ([Hutchinson & O'Brien](https://www.ucc.ie/en/media/research/centreforinvestmentresearch/wp/TrendFollowingFeb2014.pdf)). That argues for symmetric long/short trend on silver and for strict vol-scaling.

Faster metal rules have weak support. Standard intraday moving-average rules on gold and silver showed **"no predictive power whatsoever"**. Some longer parameter sets worked in gold, none in silver ([Batten et al.](https://eprints.soton.ac.uk/411993/)). Intraday momentum in Shanghai gold futures was gross-only and lost its power when night trading was introduced ([Bouri et al.](https://acfr.aut.ac.nz/__data/assets/pdf_file/0008/686816/4a-Z-Ivy-Zhou.pdf)). Gold/silver ratio mean reversion fails too. GLD-SLV failed cointegration (ADF p = 0.38), earned a **net Sharpe of 0.289 versus 0.830 for holding gold** in 2018-2020, and broke down after March 2020 ([Pandini](https://mmx.prnewswire.com/media/2965868/2026_00G__StationaryButNotProfitable_ACriticalLookatPairsTrading_DavidePandini.pdf?p=pdf)). The ratio then swung from above 100x to 60x or below ([CME Group](https://www.cmegroup.com/articles/2026/precious-metals-outlook-2026-market-dynamics-following-a-record-breaking-year.html)).

The one faster metal idea with cost-inclusive academic support is session-based. Over about 30 years, gold's overnight (Asia/London) returns were significantly positive and US daytime returns significantly negative, across COMEX futures, the London Fix and gold ETFs. The authors call this **"economically important even with transaction costs"** ([Blose, Gondhalekar & Kort](https://link.springer.com/10.1007/s12197-017-9403-0)). A 2026 desk note shows the same split year to date: **Asia +3.4%, Europe −0.9%, US −28.1%** ([StoneX](https://www.stonex.com/en-gb/news-and-analysis/gold-outlook-asia-buys-the-dip-us-hammers-the-rip-2026-11-16/)). A daily overnight rule trades about 5 times a week per metal. It needs an edge above about 16 bps per day, and the per-day magnitude in the long-run study was not available. Effects measured on COMEX may not carry over to a 24/7 USDT perpetual with its own funding and weekend trading, and the listing history and liquidity of Binance's XAU/XAG perps need checking before testing.

The 2023-2026 metals sample is a trap for backtests. Gold rose **65% in 2025, its best year since 1979**, and was the second-most profitable single market for trend followers since 2013. Its volatility roughly doubled from about 11% to 20% ([Man Group](https://www.man.com/insights/views-from-the-floor-2026-27-jan)). Silver then fell **about 31% in two sessions** from its late-January 2026 record of $118.45 ([S&P Global](https://www.spglobal.com/market-intelligence/en/news-insights/research/2026/02/the-rise-and-fall-of-the-silver-price)). Any long-biased metal rule will look excellent on this window. Test it against buy-and-hold, with long and short reported separately, and use hard per-trade loss limits, because on a perp a crash day means liquidation, not just a drawdown.

## Five strategies to backtest, ranked

The ranking weighs four things: strength of cost-inclusive evidence, fit with the already-validated daily breakout, cost-in-R safety, and the ability to reach several trades a week across 10-17 assets. All parameter grids are deliberately small, because each extra variant raises the risk of overfitting on three years of data.

**Shared test protocol for all five strategies.** Compute signals on closed 1H bars and fill at the next bar's open. Add a manual-delay variant that fills 1-4 hours later. Charge 5 bps taker plus 3 bps slippage per side as the base case, with sensitivity runs at 2, 5 and 10 bps slippage (10 for alts and silver). Apply actual Binance funding at 00/08/16 UTC to any position held through those times. Define R as the initial stop distance, and report net average R per trade, win rate, payoff ratio, trades per week, max drawdown in R, and longs and shorts separately. Report each calendar year (2023, 2024, 2025, 2026 YTD) and the 2022 bear if it can be added. Compare each strategy with the validated 55-day system on the same assets. Use a random-entry or permutation baseline with the same holding-time distribution. **Kill a strategy if its net average falls below +0.10R, if more than half of its gross edge goes to costs, or if it loses in two of four years.**

### 1. Fast Donchian ensemble with midpoint ratchet (highest priority)

This extends the validated system directly and has the best net-of-cost crypto evidence (Zarattini et al.: Sharpe above 1.5 at 10 bps through March 2025). The extra trades come from the faster legs.

| Element | Specification |
|---|---|
| Universe | The user's 5 core assets plus up to 12 more liquid USD-M perps (17 total). Gold/silver perps as a separate test |
| Bars | Daily closes built from 1H data at 00:00 UTC. Variant: 4H closes with lookbacks ×6 |
| Legs | N ∈ {5, 10, 20, 30, 60, 90} days, each an independent unit |
| Entry (per leg) | Long when daily close > max(close of prior N days). Short mirror as a separate test |
| Stop (per leg) | stop = max(prior stop, (maxClose_N + minClose_N)/2). Exit when the daily close is below the stop. Initial R = entry − first stop |
| Trend filter variant | Longs only when close > 200-day EMA (the validated filter); shorts only below it |
| Sizing | Asset target weight = min(2.0, 0.25 / σ_90d annualised), split equally across the 6 legs. R-based alternative: risk 0.25-0.5% of equity per leg |
| Overlay test | Skip new long legs when the asset's 3-day mean funding is in its top decile of the trailing 180 days |
| Expected frequency | The 5- and 10-day legs should fire several times as often as the 55-day rule. Measure this; across 10-17 assets it plausibly reaches 2-5 unit entries a week |

### 2. 4H momentum with ATR chandelier trail, fixed parameters

This is the only direct Binance-perp, funding-inclusive timeframe evidence. Its H4-H6 optimum and its ATR-stop ablation are the useful parts. Use fixed parameters, because adaptive re-fitting is where the paper's Sharpe is suspect.

| Element | Specification |
|---|---|
| Bars | 1H resampled to 4H at 00/04/08/12/16/20 UTC. 6H variant at 00/06/12/18 UTC |
| Signal | ROC_L = close / close[L] − 1, L ∈ {42, 84, 168} 4H bars (7, 14, 28 days) |
| Entry | Long when ROC_L > k·σ_4H·√L, with σ_4H the stdev of 4H returns over the last 180 bars and k ∈ {0.5, 1.0}. Short mirror tested separately |
| Initial stop | Entry − α·ATR(14 × 4H), α ∈ {2.5, 3.0} (the paper reports a stable plateau for α between 2.0 and 3.5) |
| Trail | Chandelier: highest close since entry − α·ATR(14); never lowered |
| Re-entry | Only on a fresh ROC threshold cross after the exit, with a minimum 6-bar (24h) cooldown |
| Regime variant | Alt longs only when BTC is above its 200-day EMA; compare with always-on |
| Expected frequency | The paper's portfolio implies about 1-1.5 trades per coin per month at H4-H6, so about 4-6 a week on 17 assets |

### 3. 28/5 time-series momentum with time exit

This is the most credible academic result that survives 15 bps (Sharpe 1.51 net). It trades faster than a breakout because exits are scheduled. Applying it per asset extrapolates from the paper's index-level design.

| Element | Specification |
|---|---|
| Signal time | Daily at 00:00 UTC |
| Signal | r_L = close / close(L days ago) − 1, L ∈ {14, 21, 28}. Percentile-rank r_L within the asset's own trailing 365-day history of r_L |
| Entry | Long when the rank is in the top tercile. Flat (variant: short) when it is in the bottom tercile |
| Hold | H ∈ {3, 5, 7} days, then re-evaluate. If the signal still qualifies, keep the position without a new trade |
| Protective stop | 3×ATR(14 daily) from entry, which also defines R |
| Sizing | Inverse-vol: 0.25 / σ_90d, capped at 2× |
| Expected frequency | About 48% time in market. Each asset re-qualifies or exits every H days, so roughly 1-2 position changes per asset per month |

### 4. Weekly relative-strength rotation (top 3-5 long)

Use this only if the ranking universe can be widened to 20-30 perps. The evidence is strong pre-2022 and in the winner leg, but unproven after 2022 net of costs.

| Element | Specification |
|---|---|
| Ranking universe | Top 20-30 USD-M perps by 30-day median quote volume, excluding stablecoins and listings under 60 days old |
| Rebalance | Monday 00:00 UTC |
| Signal | R = close(t) / close(t − 504h) − 1 (21 days). Variant: skip the last 24h. Grid: lookback {14, 21, 28} days, N {3, 5} |
| Position | Long the top N, inverse-vol weighted (20-day hourly realised vol). Variant: short BTC beta-matched against the basket |
| Hysteresis | Replace a holding only if it falls out of the top 2N |
| Regime gate | Hold cash when BTC's 28-day return is below 0 |
| Stops | 2.5×ATR(14 daily) catastrophic stop per name (defines R). Funding crowding veto as in strategy 1 |
| Expected frequency | About 1-3 replacements a week, or 2-6 tickets |

### 5. Gold/silver overnight long (speculative, metals only)

This is the only faster metals rule with cost-inclusive long-history support. It ranks last because the transfer to USDT perps is unverified and 2023-2026 is a gold bull market.

| Element | Specification |
|---|---|
| Instrument | XAU (and XAG as a variant) USDT perpetual. Confirm listing date, funding and weekend liquidity first |
| Entry | Long at 17:00 New York time (22:00 UTC in winter, 21:00 UTC during US daylight time), Sunday through Thursday evenings |
| Exit | 08:00 New York time (13:00 / 12:00 UTC) |
| Variants | Asia-only window 00:00-07:00 UTC; trend-gated (only when the 63-day return > 0); short during US hours instead of flat |
| Stop | 1.5× the 20-day average overnight-window range (defines R) |
| Benchmark | Buy-and-hold over the same calendar span, compared per unit of time in the market. Run a shuffled-session permutation test |
| Expected frequency | 5 trades a week per metal. It needs a gross edge of more than about 16 bps per night, so kill it if net expectancy is at or below 0 at base costs |

## What will not work at retail taker costs

The evidence rules out a clear set of designs, because each one's gross edge per trade is at or below 16 bps or has failed out of sample. Intraday time-series momentum breaks even at 3-10 bps. Hourly crossovers found no profitable intraday rules even at zero cost. Fifteen-minute and hourly reversal earns about 1 bp. The 21-23 UTC hour earns about 9 bps and fails a permutation test. Twelve-hour session splits fail SPA. Daily cross-sectional reversal is negative net on Binance. Funding fades have coin-flip hit rates. Delta-neutral carry on majors now yields roughly 0-8% with tail risk. Cross-exchange funding arbitrage loses in most cases. Gold/silver ratio mean reversion breaks down in trending ratio regimes. Symmetric long-short cross-sectional books carry liquidation risk on the short leg. **Any 1H system with stops near 1% and fixed 1:3 targets starts each trade about 0.16-0.32R behind**, which is the user's own failed result restated. Monthly re-optimised "adaptive" parameters are also risky. AdaptiveTrend's own ablation lost almost half its Sharpe with fixed parameters, which is what a live signal app would run. Market-maker economics (limit-order entries at roughly half the fee) could revive a few of these ideas, but no source tested maker fills with adverse selection, and they do not suit manual execution from phone alerts.

## Conclusion

The question changes from "which faster strategy" to "where trade frequency should come from". The evidence does not support speeding up the signal clock below about 4 hours. It does support **holding the information horizon at 1-4 weeks while adding legs and assets**. The validated 55-day breakout is one leg of a documented ensemble. Its 20-day variant already tested positive in the user's own work, which is consistent with the literature, and adding 5-30 day legs across 10-17 vol-scaled assets is the most direct route to several trades a week at about 0.02-0.05R cost per trade. The 4H momentum and 28/5 TSMOM rules give independent confirmation of the same 1-4 week effect, through trailing and time exits respectively.

The biggest unresolved risk is the backtest window, not the strategy family. Three bull-heavy years in crypto and a record gold run will make almost any long trend rule look good, and the documented failure mode is bear and chop regimes (bear-regime Sharpe −0.31 in the one Binance-perp study). The decision rule follows. A faster variant earns a place in the app only if it adds trades without lowering the per-trade R below about +0.10 net. It must also hold up when shorts, the 2022 bear if available, a 1-4 hour manual fill delay, and actual funding are all included. Strategies that pass only on 2023-2026 longs are measuring beta, not edge.
