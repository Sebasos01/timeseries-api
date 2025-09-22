-- Latest value as of :asOf for each (series_id, ts_date) within the range
SELECT sd.series_id, sd.ts_date, sd.value
FROM (
  SELECT series_id, ts_date, value,
         ROW_NUMBER() OVER (
           PARTITION BY series_id, ts_date
           ORDER BY revision_time DESC NULLS LAST
         ) AS rn
  FROM (
    SELECT series_id, ts_date, value,
           NULL::TIMESTAMPTZ AS revision_time
    FROM series_data
    WHERE series_id = :id AND ts_date BETWEEN :start AND :end

    UNION ALL

    SELECT series_id, ts_date, value, revision_time
    FROM series_data_history
    WHERE series_id = :id
      AND ts_date BETWEEN :start AND :end
      AND revision_time <= :asOf
  ) u
) sd
WHERE rn = 1
ORDER BY ts_date;

-- Alternative:
-- SELECT DISTINCT ON (series_id, ts_date) series_id, ts_date, value
-- FROM (
--   SELECT series_id, ts_date, value, NULL::TIMESTAMPTZ AS revision_time
--   FROM series_data
--   WHERE series_id = :id AND ts_date BETWEEN :start AND :end
--   UNION ALL
--   SELECT series_id, ts_date, value, revision_time
--   FROM series_data_history
--   WHERE series_id = :id
--     AND ts_date BETWEEN :start AND :end
--     AND revision_time <= :asOf
-- ) merged
-- ORDER BY series_id, ts_date, revision_time DESC NULLS LAST;
