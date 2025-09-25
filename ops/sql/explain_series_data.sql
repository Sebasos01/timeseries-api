-- Run with psql, e.g.:
--   psql "$DB_URL" -f ops/sql/explain_series_data.sql -v series_id='GDP' -v start_date='2023-01-01' -v end_date='2023-12-31'
-- Verifies that the composite index on (series_id, ts_date) is used for range scans.

\pset format aligned

EXPLAIN (ANALYZE, VERBOSE, BUFFERS)
SELECT ts_date, value
FROM series_data
WHERE series_id = :'series_id'
  AND ts_date BETWEEN :'start_date'::date AND :'end_date'::date
ORDER BY ts_date;
