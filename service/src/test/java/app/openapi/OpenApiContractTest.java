package app.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractTest {

  @Autowired
  private TestRestTemplate rest;

  @Test
  void openapiDocumentIsValid() {
    String yaml = rest.getForObject("/v3/api-docs.yaml", String.class);
    ParseOptions options = new ParseOptions();
    options.setResolve(true);
    SwaggerParseResult result = new OpenAPIV3Parser().readContents(yaml, null, options);
    assertThat(result.getMessages()).as("validation messages").isEmpty();
    assertThat(result.getOpenAPI()).isNotNull();
  }
}
