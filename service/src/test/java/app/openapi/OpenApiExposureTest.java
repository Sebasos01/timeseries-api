package app.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiExposureTest {

  @Autowired
  private TestRestTemplate rest;

  @Test
  void openapiYamlServed() {
    ResponseEntity<String> response = rest.getForEntity("/v3/api-docs.yaml", String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("openapi:");
    assertThat(response.getBody()).contains("ProblemDetail");
  }
}
