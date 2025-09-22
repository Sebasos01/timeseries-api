package app.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class PostgresContainerIT {

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

  @Test
  void flywayRanAgainstContainer() {
    List<String> tables = jdbcTemplate.queryForList(
        """
        SELECT table_name FROM information_schema.tables
        WHERE table_schema = 'public'
          AND table_name IN ('series', 'series_data', 'series_data_history')
        ORDER BY table_name
        """,
        String.class);

    assertThat(tables).containsExactly("series", "series_data", "series_data_history");
  }
}
