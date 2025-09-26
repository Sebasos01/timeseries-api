package com.ospicorp.timeseriesapi.series.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SeriesSearchControllerIT {

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
    registry.add("search.opensearch.url", () -> "http://127.0.0.1:65535");
  }

  @Autowired
  private TestRestTemplate restTemplate;

  @AfterAll
  static void stopContainer() {
    POSTGRES.stop();
  }

  @Test
  void searchReturnsSeededResultsWhenOpenSearchUnavailable() {
    ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
        "/v1/series/search?q=gdp&country=US&freq=Q&page=1&page_size=25&fields=seriesId,name,description",
        HttpMethod.GET,
        null,
        new ParameterizedTypeReference<>() {});

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

    List<Map<String, Object>> body = requireNonNull(response.getBody());
    assertThat(body).isNotNull();
    assertThat(body).isNotEmpty();
    assertThat(body.stream()
        .anyMatch(entry -> "US.GDP.Q.NSA".equals(entry.get("seriesId")))).isTrue();
  }
}

