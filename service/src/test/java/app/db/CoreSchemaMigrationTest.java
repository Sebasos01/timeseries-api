package app.db;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoreSchemaMigrationTest {

  private static final String AS_OF_QUERY = """
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
      ORDER BY ts_date
      """;

  private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
      .parse("timescale/timescaledb:2.17.2-pg16")
      .asCompatibleSubstituteFor("postgres");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TIMESCALE_IMAGE)
          .withInitScript("test-init-timescaledb.sql");

  @DynamicPropertySource
  static void configureDataSource(DynamicPropertyRegistry registry) {
    POSTGRES.start();
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private NamedParameterJdbcTemplate namedTemplate;

  @BeforeEach
  void setUp() {
    DataSource dataSource = requireNonNull(jdbcTemplate.getDataSource());
    namedTemplate = new NamedParameterJdbcTemplate(dataSource);
    jdbcTemplate.update("DELETE FROM series_data_history");
    jdbcTemplate.update("DELETE FROM series_data");
    jdbcTemplate.update("DELETE FROM series");
  }

  @Test
  void migrationCreatesCoreTables() {
    String series = jdbcTemplate.queryForObject("SELECT to_regclass('public.series')", String.class);
    String seriesData = jdbcTemplate.queryForObject("SELECT to_regclass('public.series_data')", String.class);
    String seriesDataHistory =
        jdbcTemplate.queryForObject("SELECT to_regclass('public.series_data_history')", String.class);

    assertThat(series).isEqualTo("series");
    assertThat(seriesData).isEqualTo("series_data");
    assertThat(seriesDataHistory).isEqualTo("series_data_history");
  }

  @Test
  void hypertableExistsWhenTimescaleAvailable() {
    assumeTrue(timescaleExtensionPresent(), "TimescaleDB extension not installed in container");

    List<String> hypertables = jdbcTemplate.queryForList(
        """
        SELECT hypertable_name FROM timescaledb_information.hypertables
        WHERE hypertable_name = 'series_data'
        """,
        String.class);

    assertThat(hypertables).contains("series_data");
  }

  @Test
  void explainUsesPrimaryKeyIndexForRangeScans() {
    String seriesId = "GDP.RANGE";
    insertSeries(seriesId);

    LocalDate start = LocalDate.of(2020, 1, 1);
    for (int i = 0; i < 6; i++) {
      jdbcTemplate.update(
          "INSERT INTO series_data (series_id, ts_date, value) VALUES (?, ?, ?)",
          seriesId,
          Date.valueOf(start.plusMonths(i)),
          100.0 + i);
    }

    List<String> plan = jdbcTemplate.queryForList(
        """
        EXPLAIN SELECT * FROM series_data
        WHERE series_id = ? AND ts_date BETWEEN ? AND ?
        ORDER BY ts_date
        """,
        String.class,
        seriesId,
        Date.valueOf(start),
        Date.valueOf(start.plusMonths(5)));

    assertThat(plan).anyMatch(line -> line.contains("Index Scan")
        && line.contains("series_data_ts_date_idx"));
  }

  @Test
  void asOfQueryReturnsLatestRevisionBeforeCutoff() {
    String seriesId = "GDP.ASOF";
    insertSeries(seriesId);

    jdbcTemplate.update(
        "INSERT INTO series_data (series_id, ts_date, value) VALUES (?, ?, ?)",
        seriesId,
        Date.valueOf(LocalDate.of(2020, 1, 1)),
        100.0);
    jdbcTemplate.update(
        "INSERT INTO series_data (series_id, ts_date, value) VALUES (?, ?, ?)",
        seriesId,
        Date.valueOf(LocalDate.of(2020, 2, 1)),
        110.0);

    jdbcTemplate.update(
        "INSERT INTO series_data_history (series_id, ts_date, value, revision_time) VALUES (?, ?, ?, ?)",
        seriesId,
        Date.valueOf(LocalDate.of(2020, 2, 1)),
        102.0,
        OffsetDateTime.of(2020, 2, 5, 0, 0, 0, 0, ZoneOffset.UTC));
    jdbcTemplate.update(
        "INSERT INTO series_data_history (series_id, ts_date, value, revision_time) VALUES (?, ?, ?, ?)",
        seriesId,
        Date.valueOf(LocalDate.of(2020, 2, 1)),
        105.0,
        OffsetDateTime.of(2020, 2, 10, 0, 0, 0, 0, ZoneOffset.UTC));
    jdbcTemplate.update(
        "INSERT INTO series_data_history (series_id, ts_date, value, revision_time) VALUES (?, ?, ?, ?)",
        seriesId,
        Date.valueOf(LocalDate.of(2020, 2, 1)),
        103.0,
        OffsetDateTime.of(2020, 2, 15, 0, 0, 0, 0, ZoneOffset.UTC));

    MapSqlParameterSource params = new MapSqlParameterSource()
        .addValue("id", seriesId)
        .addValue("start", LocalDate.of(2020, 1, 1))
        .addValue("end", LocalDate.of(2020, 2, 1))
        .addValue("asOf", OffsetDateTime.of(2020, 2, 12, 0, 0, 0, 0, ZoneOffset.UTC));

    List<DataPoint> points = namedTemplate.query(
        AS_OF_QUERY,
        params,
        (rs, rowNum) -> new DataPoint(
            rs.getDate("ts_date").toLocalDate(),
            rs.getDouble("value")));

    assertThat(points)
        .extracting(DataPoint::date)
        .containsExactly(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 2, 1));

    assertThat(points)
        .extracting(DataPoint::value)
        .containsExactly(100.0, 105.0);
  }

  private void insertSeries(String seriesId) {
    jdbcTemplate.update(
        "INSERT INTO series (series_id, name, frequency) VALUES (?, ?, ?) ON CONFLICT (series_id) DO NOTHING",
        seriesId,
        "Test series " + seriesId,
        "M");
  }

  private boolean timescaleExtensionPresent() {
    Boolean installed = jdbcTemplate.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'timescaledb')",
        Boolean.class);
    return Boolean.TRUE.equals(installed);
  }

  private record DataPoint(LocalDate date, double value) {}
}
