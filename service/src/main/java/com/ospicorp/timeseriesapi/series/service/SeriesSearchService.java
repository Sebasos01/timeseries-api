package com.ospicorp.timeseriesapi.series.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ospicorp.timeseriesapi.series.model.Series;
import com.ospicorp.timeseriesapi.series.model.SeriesSearchResult;
import com.ospicorp.timeseriesapi.series.model.enums.Frequency;
import com.ospicorp.timeseriesapi.series.repository.SeriesRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Service
public class SeriesSearchService {
  private static final Logger log = LoggerFactory.getLogger(SeriesSearchService.class);
  static final String INDEX = "series_v1";

  private final RestTemplate restTemplate;
  private final ObjectMapper mapper;
  private final String baseUrl;
  private final SeriesRepository seriesRepository;

  public SeriesSearchService(RestTemplate restTemplate,
      ObjectMapper mapper,
      SeriesRepository seriesRepository,
      @Value("${search.opensearch.url:http://search:9200}") String baseUrl) {
    this.restTemplate = restTemplate;
    this.mapper = mapper;
    this.seriesRepository = seriesRepository;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    ensureIndex();
  }

  public List<SeriesSearchResult> search(String q, String country, Frequency freq, int page,
      int pageSize) {
    if (!StringUtils.hasText(q)) {
      throw new IllegalArgumentException("q must be provided");
    }
    if (!StringUtils.hasText(country) || freq == null) {
      throw new IllegalArgumentException("country and freq must be provided");
    }
    if (page < 1 || pageSize < 1) {
      throw new IllegalArgumentException("page and page_size must be positive");
    }
    int from = (page - 1) * pageSize;

    Map<String, Object> requestBody = buildRequestBody(q, country, freq, from, pageSize);
    String url = baseUrl + '/' + INDEX + "/_search";

    try {
      String payload = mapper.writeValueAsString(requestBody);
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      HttpEntity<String> entity = new HttpEntity<>(payload, headers);

      ResponseEntity<JsonNode> response = restTemplate.exchange(url, HttpMethod.POST, entity,
          JsonNode.class);
      List<SeriesSearchResult> results = parseResults(response.getBody());
      if (!results.isEmpty()) {
        return results;
      }
      log.debug("OpenSearch returned no results for query '{}'; falling back to database search", q);
      return fallbackSearch(q, country, freq, page, pageSize);
    } catch (HttpClientErrorException.NotFound e) {
      log.warn("OpenSearch index {} not found; falling back to database search", INDEX);
      return fallbackSearch(q, country, freq, page, pageSize);
    } catch (Exception e) {
      log.warn("OpenSearch query failed: {}. Falling back to database search", e.getMessage());
      return fallbackSearch(q, country, freq, page, pageSize);
    }
  }

  public void bulkIndexSeries(List<Series> series) {
    if (series == null || series.isEmpty()) {
      return;
    }
    final int batchSize = 500;
    for (int i = 0; i < series.size(); i += batchSize) {
      int end = Math.min(i + batchSize, series.size());
      sendBulkBatch(series.subList(i, end));
    }
  }

  private void sendBulkBatch(List<Series> batch) {
    if (batch.isEmpty()) {
      return;
    }
    StringBuilder ndjson = new StringBuilder();
    try {
      for (Series series : batch) {
        ndjson.append("{\"index\":{\"_index\":\"")
            .append(INDEX)
            .append("\",\"_id\":\"")
            .append(series.getSeriesId())
            .append("\"}}\n");
        Map<String, Object> doc = new HashMap<>();
        doc.put("series_id", series.getSeriesId());
        doc.put("name", series.getName());
        doc.put("description", series.getDescription());
        doc.put("geography", series.getGeography());
        doc.put("frequency", String.valueOf(series.getFrequency()));
        ndjson.append(mapper.writeValueAsString(doc)).append('\n');
      }
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.parseMediaType("application/x-ndjson"));
      HttpEntity<String> entity = new HttpEntity<>(ndjson.toString(), headers);
      restTemplate.exchange(baseUrl + "/_bulk", HttpMethod.POST, entity, String.class);
    } catch (Exception ex) {
      throw new RuntimeException("OpenSearch bulk indexing failed", ex);
    }
  }

  private Map<String, Object> buildRequestBody(String q, String country, Frequency freq, int from,
      int size) {
    Map<String, Object> multiMatch = Map.of(
        "multi_match",
        Map.of(
            "query", q,
            "fields", List.of("name^2", "description"))
    );

    List<Map<String, Object>> filters = new ArrayList<>();
    filters.add(Map.of("term", Map.of("geography", country)));
    filters.add(Map.of("term", Map.of("frequency", freq.name())));

    Map<String, Object> boolQuery = new HashMap<>();
    boolQuery.put("must", List.of(multiMatch));
    boolQuery.put("filter", filters);

    Map<String, Object> query = Map.of("bool", boolQuery);

    Map<String, Object> body = new HashMap<>();
    body.put("query", query);
    body.put("from", from);
    body.put("size", size);
    return body;
  }

  private List<SeriesSearchResult> parseResults(JsonNode body) {
    List<SeriesSearchResult> results = new ArrayList<>();
    if (body == null) {
      return results;
    }
    JsonNode hits = body.path("hits").path("hits");
    if (!hits.isArray()) {
      return results;
    }
    for (JsonNode hit : hits) {
      JsonNode source = hit.path("_source");
      if (!source.isObject()) {
        continue;
      }
      String seriesId = source.path("series_id").asText(null);
      String name = source.path("name").asText(null);
      String description = source.path("description").asText(null);
      if (seriesId != null) {
        results.add(new SeriesSearchResult(seriesId, name, description));
      }
    }
    return results;
  }

  private List<SeriesSearchResult> fallbackSearch(String q, String country, Frequency freq, int page,
      int pageSize) {
    Character frequencyFilter = null;
    if (freq != null && freq != Frequency.NATIVE) {
      frequencyFilter = freq.name().charAt(0);
    }
    PageRequest pageable = PageRequest.of(Math.max(page - 1, 0), pageSize, Sort.by("name").ascending());
    Page<Series> matches = seriesRepository.searchSeries(country, frequencyFilter, q, pageable);
    return matches.stream()
        .map(series -> new SeriesSearchResult(series.getSeriesId(), series.getName(), series.getDescription()))
        .toList();
  }

  private void ensureIndex() {
    String url = baseUrl + '/' + INDEX;
    try {
      restTemplate.headForHeaders(url);
    } catch (HttpClientErrorException.NotFound e) {
      createIndex(url);
    } catch (Exception ex) {
      log.warn("Unable to verify index {}: {}", INDEX, ex.getMessage());
    }
  }

  private void createIndex(String url) {
    String mapping = "{\"mappings\":{\"properties\":{"
        + "\"series_id\":{\"type\":\"keyword\"},"
        + "\"name\":{\"type\":\"text\"},"
        + "\"description\":{\"type\":\"text\"},"
        + "\"geography\":{\"type\":\"keyword\"},"
        + "\"frequency\":{\"type\":\"keyword\"}"
        + "}}}";
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<String> entity = new HttpEntity<>(mapping, headers);
    try {
      restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);
    } catch (Exception ex) {
      log.warn("Unable to create index {}: {}", INDEX, ex.getMessage());
    }
  }
}


