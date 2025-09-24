CREATE TABLE series (
  series_id     VARCHAR(128) PRIMARY KEY,
  name          TEXT NOT NULL,
  frequency     CHAR(1) NOT NULL,  -- A/Q/M/W/D
  unit          TEXT,
  geography     VARCHAR(16),
  source        TEXT,
  is_adjusted   BOOLEAN DEFAULT FALSE,
  start_date    DATE,
  end_date      DATE,
  last_update   TIMESTAMP WITH TIME ZONE DEFAULT now()
);

CREATE TABLE series_data (
  series_id     VARCHAR(128) NOT NULL,
  ts_date       DATE NOT NULL,
  value         DOUBLE PRECISION,
  PRIMARY KEY (series_id, ts_date),
  FOREIGN KEY (series_id) REFERENCES series(series_id)
);

-- Point-in-time history (latest values live in series_data)
CREATE TABLE series_data_history (
  series_id      VARCHAR(128) NOT NULL,
  ts_date        DATE NOT NULL,
  value          DOUBLE PRECISION,
  revision_time  TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (series_id, ts_date, revision_time),
  FOREIGN KEY (series_id) REFERENCES series(series_id)
);

-- Convert to hypertable for range scans:
SELECT create_hypertable('series_data', 'ts_date', if_not_exists => TRUE);
-- Optionally space-partition by series_id for very large datasets:
-- SELECT create_hypertable('series_data', 'ts_date',
--   chunk_time_interval => INTERVAL '1 year',
--   partitioning_column => 'series_id', number_partitions => 8,
--   if_not_exists => TRUE);
