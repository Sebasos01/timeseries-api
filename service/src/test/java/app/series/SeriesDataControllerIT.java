package app.series;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SeriesDataControllerIT {

  private static final DockerImageName TIMESCALE_IMAGE = DockerImageName
      .parse("timescale/timescaledb:2.17.2-pg16")
      .asCompatibleSubstituteFor("postgres");

  @SuppressWarnings("resource")
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
  private TestRestTemplate restTemplate;

  @AfterAll
  static void stopContainer() {
    POSTGRES.stop();
  }

  @Test
  void getSeriesDataReturnsStructuredJson() {
    ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
        "/v1/series/US.GDP.Q.NSA/data",
        HttpMethod.GET,
        null,
        new ParameterizedTypeReference<>() {});

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getETag()).isNotNull();

    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("series_id")).isEqualTo("US.GDP.Q.NSA");
    assertThat(body.get("freq")).isEqualTo("Q");
    assertThat(body.get("transform")).isEqualTo("as_is");

    Number pointCount = (Number) body.get("point_count");
    assertThat(pointCount).isNotNull();

    @SuppressWarnings("unchecked")
    List<List<Object>> points = (List<List<Object>>) body.get("points");
    assertThat(points).isNotNull();
    assertThat(points).isNotEmpty();
    assertThat(points.size()).isEqualTo(pointCount.intValue());
    assertThat(points.get(0).get(0)).isEqualTo("2018-03-31");

    Number totalPoints = (Number) body.get("total_points");
    assertThat(totalPoints).isNotNull();
    assertThat(totalPoints.intValue()).isGreaterThanOrEqualTo(pointCount.intValue());

    Number pageNumber = (Number) body.get("page");
    assertThat(pageNumber).isNotNull();
    assertThat(pageNumber.intValue()).isEqualTo(1);

    Number pageSize = (Number) body.get("page_size");
    assertThat(pageSize).isNotNull();
    assertThat(pageSize.intValue()).isEqualTo(500);

    Number totalPages = (Number) body.get("total_pages");
    assertThat(totalPages).isNotNull();
    assertThat(totalPages.intValue()).isGreaterThanOrEqualTo(1);

    assertThat(body.get("has_more")).isEqualTo(Boolean.FALSE);

    @SuppressWarnings("unchecked")
    Map<String, Object> metadata = (Map<String, Object>) body.get("metadata");
    assertThat(metadata).isNotNull();
    assertThat(metadata.get("country")).isEqualTo("US");
    assertThat(metadata.get("source")).isEqualTo("MockData");
  }

  @Test
  void getSeriesDataHonorsCsvAcceptHeader() {
    HttpHeaders headers = new HttpHeaders();
    headers.setAccept(List.of(MediaType.valueOf("text/csv")));

    ResponseEntity<String> response = restTemplate.exchange(
        "/v1/series/US.GDP.Q.NSA/data?start=2018-03-31&end=2018-06-30",
        HttpMethod.GET,
        new HttpEntity<>(headers),
        String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.valueOf("text/csv"));
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody()).contains("date,value");
    assertThat(response.getBody()).contains("2018-03-31");
    assertThat(response.getBody()).doesNotContain("100.0");
  }

  @Test
  void getSeriesDataSupportsPagination() {
    ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
        "/v1/series/US.GDP.Q.NSA/data?page=2&page_size=5",
        HttpMethod.GET,
        null,
        new ParameterizedTypeReference<>() {});

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(((Number) body.get("page")).intValue()).isEqualTo(2);
    assertThat(((Number) body.get("page_size")).intValue()).isEqualTo(5);
    assertThat(((Number) body.get("total_pages")).intValue()).isGreaterThanOrEqualTo(2);

    @SuppressWarnings("unchecked")
    List<List<Object>> points = (List<List<Object>>) body.get("points");
    assertThat(points).isNotNull();
    assertThat(points.size()).isLessThanOrEqualTo(5);
  }

  @Test
  void getSeriesDataReturnsHelpfulErrorForInvalidFrequency() {
    ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
        "/v1/series/US.GDP.Q.NSA/data?freq=invalid",
        HttpMethod.GET,
        null,
        new ParameterizedTypeReference<>() {});

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("error")).isEqualTo("Invalid frequency code. Supported values: native,D,W,M,Q,A.");
    assertThat(body.get("errorCode")).isEqualTo(1002);
    assertThat(body.get("moreInfo")).isEqualTo("https://developers.company.com/docs/errors/1002");
  }
}