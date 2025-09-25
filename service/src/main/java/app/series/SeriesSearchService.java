package app.series;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

  public SeriesSearchService(RestTemplate restTemplate,
      ObjectMapper mapper,
      @Value("${opensearch.url:http://localhost:9200}") String baseUrl) {
    this.restTemplate = restTemplate;
    this.mapper = mapper;
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
      return parseResults(response.getBody());
    } catch (HttpClientErrorException.NotFound e) {
      throw new NoSuchElementException("OpenSearch index not found: " + INDEX);
    } catch (Exception e) {
      throw new RuntimeException("OpenSearch query failed", e);
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
