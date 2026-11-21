"""
Builds engine/src/test/resources/talib_reference.csv: a synthetic, crypto-like OHLCV series
plus indicator values from TA-Lib, an independent, widely used C implementation.

The engine follows TradingView's formulas. TA-Lib uses the same formulas but seeds a few of
them differently on the very first bars, so the test compares only the last 500 of 1,500 bars,
where those start-up differences have died out (they shrink exponentially).

Run:  pip install TA-Lib numpy  &&  python tools/make_talib_reference.py
"""
import csv
import pathlib

import numpy as np
import talib

N = 1500
rng = np.random.default_rng(20261121)

# Random walk with volatility regimes and trends, roughly like BTC on 1H.
price = 30000.0
rows = []
vol = 0.006
for i in range(N):
    if i % 250 == 0:
        vol = rng.choice([0.003, 0.006, 0.012])
        drift = rng.choice([-0.0008, 0.0, 0.0008])
    o = price
    c = o * (1 + drift + vol * rng.standard_normal())
    h = max(o, c) * (1 + abs(vol * 0.6 * rng.standard_normal()))
    l = min(o, c) * (1 - abs(vol * 0.6 * rng.standard_normal()))
    v = float(rng.lognormal(mean=6.0, sigma=0.5))
    rows.append((round(o, 2), round(h, 2), round(l, 2), round(c, 2), round(v, 4)))
    price = c

o, h, l, c, v = (np.array(col, dtype=float) for col in zip(*rows))

out = {
    "ema50": talib.EMA(c, 50),
    "ema200": talib.EMA(c, 200),
    "rsi14": talib.RSI(c, 14),
    "atr14": talib.ATR(h, l, c, 14),
    "adx14": talib.ADX(h, l, c, 14),
    "plusdi14": talib.PLUS_DI(h, l, c, 14),
    "minusdi14": talib.MINUS_DI(h, l, c, 14),
    "sma20": talib.SMA(c, 20),
    "obv": talib.OBV(c, v),
}
macd, signal, hist = talib.MACD(c, 12, 26, 9)
out.update(macd=macd, macdsignal=signal, macdhist=hist)
upper, middle, lower = talib.BBANDS(c, 20, 2.0, 2.0, 0)
out.update(bbupper=upper, bblower=lower)

path = pathlib.Path(__file__).resolve().parents[1] / "engine/src/test/resources/talib_reference.csv"
with path.open("w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["open", "high", "low", "close", "volume", *out.keys()])
    for i in range(N):
        w.writerow([*rows[i], *("" if np.isnan(out[k][i]) else repr(float(out[k][i])) for k in out)])
print(f"wrote {path} ({N} rows)")
