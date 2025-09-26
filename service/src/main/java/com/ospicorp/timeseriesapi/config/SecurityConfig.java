package com.ospicorp.timeseriesapi.config;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.CrossOriginEmbedderPolicyHeaderWriter;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

  private static final String[] PUBLIC_ENDPOINTS = {
      "/actuator/**",
      "/v3/api-docs/**",
      "/swagger-ui/**",
      "/swagger-ui.html"
  };

  @Bean
  @ConditionalOnProperty(name = "security.auth.enabled", havingValue = "true")
  SecurityFilterChain jwtChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
            .anyRequest().authenticated())
        .oauth2ResourceServer(oauth -> oauth.jwt(withDefaults()));
    configureSecurityHeaders(http);
    return http.build();
  }

  @Bean
  @ConditionalOnProperty(name = "security.auth.enabled", havingValue = "false", matchIfMissing = true)
  SecurityFilterChain openChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    configureSecurityHeaders(http);
    return http.build();
  }

  private void configureSecurityHeaders(HttpSecurity http) throws Exception {
    http.headers(headers -> {
      headers.defaultsDisabled();
      headers.frameOptions(frame -> frame.deny());
      headers.contentTypeOptions(withDefaults());
      headers.httpStrictTransportSecurity(hsts -> hsts
          .includeSubDomains(true)
          .preload(true)
          .maxAgeInSeconds(63_072_000));
      headers.referrerPolicy(referrer -> referrer.policy(
          ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
      headers.crossOriginOpenerPolicy(coop -> coop.policy(
          CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN));
      headers.crossOriginEmbedderPolicy(coep -> coep.policy(
          CrossOriginEmbedderPolicyHeaderWriter.CrossOriginEmbedderPolicy.REQUIRE_CORP));
      headers.crossOriginResourcePolicy(corp -> corp.policy(
          CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy.SAME_SITE));
      headers.addHeaderWriter(new StaticHeadersWriter(
          "Permissions-Policy", "geolocation=(), camera=(), microphone=()"));
    });
  }

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatCustomizer() {
    return factory -> {
      factory.addContextCustomizers(context -> context.setUseHttpOnly(true));
      factory.addConnectorCustomizers(connector -> {
        connector.setXpoweredBy(false);
        connector.setProperty("server", "");
      });
    };
  }
}
