package app.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProblemDetailsHandlerTest {

  @Autowired
  private TestRestTemplate rest;

  @Test
  void invalidParameterReturnsProblemDetail() {
    ResponseEntity<Map> response = rest.getForEntity("/v1/series/search?q=test&country=US&freq=INVALID", Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getHeaders().getContentType()).isNotNull();
    assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
    Map<?, ?> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body).containsKeys("type", "title", "status", "detail", "instance");
  }
}
