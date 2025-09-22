# Phase 3 Database Notes

## Local development
- Ensure the TimescaleDB extension is available and loaded: `CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;` should run as part of container initialization (see Phase 1 compose notes).
- When running tests or local tooling against vanilla PostgreSQL without the extension, the schema migration will fail while attempting to call `create_hypertable`. This is intentional; the hypertable must exist in supported environments.

## Verifying hypertable
- Connect to the database and run `SELECT * FROM timescaledb_information.hypertables WHERE hypertable_name = 'series_data';`.
- A row for `series_data` confirms the hypertable exists and uses the primary key index for chunk management.

## Checking query plan
- Run `EXPLAIN SELECT * FROM series_data WHERE series_id = 'X' AND ts_date BETWEEN '2020-01-01' AND '2020-12-31' ORDER BY ts_date;`.
- The resulting plan should show an `Index Scan` (not a `Seq Scan`) using the composite `(series_id, ts_date)` key (for Timescale chunks this appears as `_hyper_*_series_data_ts_date_idx`).
