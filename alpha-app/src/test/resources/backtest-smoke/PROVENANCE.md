# Committed backtest smoke dataset

`BTCUSDT.PERP-1h.csv` is the fixed input for `BacktestSmokeTest` (T220, SC-02). It is committed so
the build stays hermetic: CI never reaches the network, and the numbers pinned in that test cannot
move because an exchange served a different window.

| | |
|---|---|
| Exchange | Binance USDⓈ-M futures **testnet** (`https://testnet.binancefuture.com`, public klines endpoint, no credentials) |
| Requested symbol | `BTCUSDT` (the testnet's own name; `BTCUSDT.PERP` is this project's unified form and the exchange rejects it) |
| Interval | `1h` |
| Window (bar open times, both inclusive) | `1704067200000` = 2024-01-01T00:00:00Z … `1712703600000` = 2024-04-09T23:00:00Z |
| Bars | 2400, one per hour, no holes |
| File | 174917 bytes |
| SHA-256 | `992672de066e55114cf8112681c88174a1b4264425cf2852f4dc020844ec61a5` |
| Fetched | 2026-09-10 |

## Regenerating

```
mvn -pl alpha-app -am test -Dtest=BacktestSmokeFixtureTest -Dalpha.it.testnet=true
```

That downloads the same window through `BinanceKlineDownloader` into `CsvKlineRepository` - the
production path, so the OHLC invariants are validated and a bar that has not closed yet cannot get
in - then downloads it a second time and requires that nothing changed. If it rewrites the file,
the SHA-256 above and every number in `BacktestSmokeTest` must be re-measured in the same commit.

Historical klines do not change, so regeneration is expected to be a no-op. Testnet data is
nonetheless *testnet* data: it mirrors real prices but is not the production feed, and nothing here
should be read as evidence about how the strategy behaves on live market data.

## Why this window

100 days of hourly bars is the smallest pull that exercises real paging in the downloader (two
pages of 1500) while staying small enough to commit, and 2400 bars is enough for the shipped
`ma-cross-btc` configuration to close 122 round trips - so the pinned metrics are averages over a
population, not over one or two lucky trades.
