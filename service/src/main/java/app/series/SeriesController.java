package app.series;

import jakarta.validation.constraints.Pattern;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@RestController
@RequestMapping("/v1/series")
@Validated
@Tag(name = "Series")
public class SeriesController {
  private static final MediaType CSV_MEDIA_TYPE = MediaType.valueOf("text/csv");
  private static final String SERIES_ID_REGEX = "^[A-Za-z0-9_.-]{1,64}$";

  private final TimeSeriesService svc;
  private final SeriesSearchService searchService;
  private final SeriesSyncService syncService;
  private final ObjectMapper mapper;

  public SeriesController(TimeSeriesService svc, SeriesSearchService searchService,
      SeriesSyncService syncService, ObjectMapper mapper) {
    this.svc = svc;
    this.searchService = searchService;
    this.syncService = syncService;
    this.mapper = mapper;
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get series metadata", description = "Fetch a single series metadata resource.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Series metadata",
          content = @Content(mediaType = "application/json",
              schema = @Schema(implementation = SeriesDto.class))),
      @ApiResponse(responseCode = "404", description = "Not found",
          content = @Content(mediaType = "application/problem+json",
              schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
  })
  public SeriesDto get(@PathVariable @Pattern(regexp = SERIES_ID_REGEX)
      @Parameter(description = "Series identifier", example = "US.GDP.REAL.Q") String id) {
    return svc.getSeries(id);
  }

  @GetMapping("/{id}/data")
  @Tag(name = "Data")
  @Operation(summary = "Get time-series data", description = "Retrieve data points for a series with optional transforms and resampling.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Data points",
          headers = {
              @Header(name = "RateLimit", description = "Current quota", schema = @Schema(type = "string")),
              @Header(name = "RateLimit-Policy", description = "Quota policy", schema = @Schema(type = "string")),
              @Header(name = "X-RateLimit-Limit", description = "Maximum requests per window", schema = @Schema(type = "integer"))
          },
          content = @Content(mediaType = "application/json",
              schema = @Schema(implementation = Object.class))),
      @ApiResponse(responseCode = "304", description = "Not modified"),
      @ApiResponse(responseCode = "400", description = "Bad request",
          content = @Content(mediaType = "application/problem+json",
              schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
      @ApiResponse(responseCode = "404", description = "Not found",
          content = @Content(mediaType = "application/problem+json",
              schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
  })
  public ResponseEntity<?> data(@PathVariable @Pattern(regexp = SERIES_ID_REGEX)
      @Parameter(description = "Series identifier", example = "US.GDP.REAL.Q") String id,
      @RequestParam(required = false) @Parameter(description = "Start date (inclusive)") LocalDate start,
      @RequestParam(required = false) @Parameter(description = "End date (inclusive)") LocalDate end,
      @RequestParam(required = false, name = "as_of") @Parameter(description = "Historical as-of date") LocalDate asOf,
      @RequestParam(defaultValue = "native") String freq,
      @RequestParam(defaultValue = "as_is") String transform,
      @RequestParam(defaultValue = "none") String fill,
      @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {

    Frequency frequency = parseEnum(freq, Frequency::valueOf, "freq");
    Transform transformType = parseEnum(transform, Transform::valueOf, "transform");
    FillPolicy fillPolicy = parseEnum(fill, FillPolicy::valueOf, "fill");

    var resp = svc.getData(id, start, end, asOf, frequency, transformType, fillPolicy);
    String etag = svc.computeEtag(id, start, end, asOf, frequency, transformType, fillPolicy);

    String ifNoneMatch = currentRequestHeader(HttpHeaders.IF_NONE_MATCH);
    if (etag != null && etag.equals(ifNoneMatch)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
          .eTag(etag)
          .build();
    }

    MediaType contentType = selectMediaType(accept);
    return ResponseEntity.ok()
        .eTag(etag)
        .contentType(contentType)
        .body(resp);
  }

  @GetMapping("/search")
  @Tag(name = "Search")
  @Operation(summary = "Search series", description = "Full-text search with filters, pagination, and sparse fieldsets.")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Matches",
          headers = {
              @Header(name = "RateLimit", description = "Current quota", schema = @Schema(type = "string")),
              @Header(name = "RateLimit-Policy", description = "Quota policy", schema = @Schema(type = "string")),
              @Header(name = "X-RateLimit-Limit", description = "Maximum requests per window", schema = @Schema(type = "integer")),
              @Header(name = "X-RateLimit-Remaining", description = "Remaining requests", schema = @Schema(type = "integer")),
              @Header(name = "X-RateLimit-Reset", description = "Seconds until reset", schema = @Schema(type = "integer"))
          },
          content = @Content(mediaType = "application/json",
              schema = @Schema(implementation = SeriesSearchResult.class))),
      @ApiResponse(responseCode = "400", description = "Bad request",
          content = @Content(mediaType = "application/problem+json",
              schema = @Schema(implementation = org.springframework.http.ProblemDetail.class))),
      @ApiResponse(responseCode = "429", description = "Too many requests",
          headers = @Header(name = "Retry-After", description = "Seconds to wait", schema = @Schema(type = "integer")),
          content = @Content(mediaType = "application/problem+json",
              schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
  })
  public ResponseEntity<?> search(
      @RequestParam @Parameter(description = "Search term", example = "gdp") String q,
      @RequestParam(name = "country") @Parameter(description = "ISO country code", example = "BR") String country,
      @RequestParam(name = "freq") @Parameter(description = "Frequency filter", example = "Q") String freq,
      @RequestParam(defaultValue = "1") @Parameter(description = "Page number", example = "1") int page,
      @RequestParam(name = "page_size", defaultValue = "50") @Parameter(description = "Page size", example = "25") int pageSize,
      @RequestParam(name = "fields", required = false)
          @Parameter(description = "Comma separated list of fields for sparse response", example = "seriesId,name") String fields) {
    Frequency frequency = parseEnum(freq, Frequency::valueOf, "freq");
    var results = searchService.search(q, country, frequency, page, pageSize);
    if (!StringUtils.hasText(fields)) {
      return ResponseEntity.ok(results);
    }
    var requested = fieldsMapper(fields);
    return ResponseEntity.ok(results.stream()
        .map(result -> {
          Map<String, Object> map = mapper.convertValue(result, Map.class);
          return filterFields(map, requested);
        })
        .collect(Collectors.toList()));
  }

  @PostMapping("/batch")
  public ResponseEntity<List<?>> batch() {
    syncService.reindexAllAsync();
    return ResponseEntity.accepted().body(Collections.emptyList());
  }

  private List<String> fieldsMapper(String fields) {
    return List.of(fields.split(","))
        .stream()
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(s -> s.replace(" ", ""))
        .distinct()
        .toList();
  }

  private Map<String, Object> filterFields(Map<String, Object> original, List<String> requested) {
    return original.entrySet().stream()
        .filter(entry -> requested.contains(entry.getKey()))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private static <E extends Enum<E>> E parseEnum(String value, Function<String, E> resolver, String field) {
    try {
      return resolver.apply(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid " + field + " parameter");
    }
  }

  private static MediaType selectMediaType(String accept) {
    if (!StringUtils.hasText(accept)) {
      return MediaType.APPLICATION_JSON;
    }
    List<MediaType> mediaTypes = MediaType.parseMediaTypes(accept);
    mediaTypes.sort(MediaType.SPECIFICITY_COMPARATOR.thenComparing(MediaType.QUALITY_VALUE_COMPARATOR));
    for (MediaType mediaType : mediaTypes) {
      if (mediaType.isCompatibleWith(CSV_MEDIA_TYPE)) {
        return CSV_MEDIA_TYPE;
      }
      if (mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
        return MediaType.APPLICATION_JSON;
      }
    }
    return MediaType.APPLICATION_JSON;
  }

  private static String currentRequestHeader(String headerName) {
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    if (attrs instanceof ServletRequestAttributes servletRequestAttributes) {
      return servletRequestAttributes.getRequest().getHeader(headerName);
    }
    return null;
  }
}







