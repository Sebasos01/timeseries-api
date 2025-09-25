package app.series;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SeriesDataDao {
  private final JdbcTemplate jdbc;

  public SeriesDataDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public List<DataPoint> fetchRange(String id, LocalDate start, LocalDate end) {
    String sql = """
      SELECT ts_date, value
      FROM series_data
      WHERE series_id = ? AND ts_date BETWEEN ? AND ?
      ORDER BY ts_date
    """;
    return jdbc.query(sql, (rs, i) -> new DataPoint(rs.getDate(1).toLocalDate(),
                                                    (Double)rs.getObject(2)),
                      id, start, end);
  }

  public List<DataPoint> fetchRangeAsOf(String id, LocalDate start, LocalDate end, Instant asOf) {
    String sql = """
      SELECT ts_date, value
      FROM (
        SELECT ts_date, value,
               ROW_NUMBER() OVER (PARTITION BY ts_date ORDER BY revision_time DESC NULLS LAST) rn
        FROM (
          SELECT ts_date, value, NULL::TIMESTAMPTZ AS revision_time
          FROM series_data WHERE series_id = ? AND ts_date BETWEEN ? AND ?
          UNION ALL
          SELECT ts_date, value, revision_time
          FROM series_data_history
          WHERE series_id = ? AND ts_date BETWEEN ? AND ? AND revision_time <= ?
        ) u
      ) x
      WHERE rn = 1
      ORDER BY ts_date
    """;
    return jdbc.query(sql, (rs, i) -> new DataPoint(rs.getDate(1).toLocalDate(),
                                                    (Double)rs.getObject(2)),
      id, start, end, id, start, end, Timestamp.from(asOf));
  }
}
