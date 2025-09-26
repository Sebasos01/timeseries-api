package com.ospicorp.timeseriesapi.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  @Bean
  OpenAPI apiInfo() {
    return new OpenAPI()
        .info(new Info()
            .title("Time Series Data API")
            .version("v1")
            .description("REST API for time-series metadata, search, and data exports")
            .contact(new Contact().name("Time Series Platform Team").email("api-support@example.com"))
            .license(new License().name("MIT")))
        .servers(List.of(new Server().url("/")))
        .externalDocs(new ExternalDocumentation()
            .description("Developer Guide")
            .url("https://docs.timeseries-api.dev"));
  }
}

