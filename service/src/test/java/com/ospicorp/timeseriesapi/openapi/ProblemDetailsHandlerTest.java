package com.ospicorp.timeseriesapi.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProblemDetailsHandlerTest {

  @Autowired
  private TestRestTemplate rest;

  @Test
  void invalidParameterReturnsProblemDetail() {
    ResponseEntity<Map<String, Object>> response = rest.exchange(
        "/v1/series/search?q=test&country=US&freq=INVALID",
        HttpMethod.GET,
        null,
        new ParameterizedTypeReference<Map<String, Object>>() {});
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    MediaType contentType = Objects.requireNonNull(response.getHeaders().getContentType());
    assertThat(contentType.toString()).contains("application/problem+json");
    Map<String, Object> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body).containsKeys("type", "title", "status", "detail", "instance");
  }
}


