package app.series;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SeriesDataDao {

  private final JdbcTemplate jdbcTemplate;

  public SeriesDataDao(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<Map<String, Object>> sample() {
    return jdbcTemplate.queryForList("select 1 as ok");
  }
}
