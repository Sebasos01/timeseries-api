package app.series;

import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
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
public class SeriesController {
  private static final MediaType CSV_MEDIA_TYPE = MediaType.valueOf("text/csv");
  private static final String SERIES_ID_REGEX = "^[A-Za-z0-9_.-]{1,64}$";

  private final TimeSeriesService svc;
  private final SeriesSearchService searchService;
  private final SeriesSyncService syncService;

  public SeriesController(TimeSeriesService svc, SeriesSearchService searchService, SeriesSyncService syncService) {
    this.svc = svc;
    this.searchService = searchService;
    this.syncService = syncService;
  }

  @GetMapping("/{id}")
  public SeriesDto get(@PathVariable @Pattern(regexp = SERIES_ID_REGEX) String id) {
    return svc.getSeries(id);
  }

  @GetMapping("/{id}/data")
  public ResponseEntity<?> data(@PathVariable @Pattern(regexp = SERIES_ID_REGEX) String id,
      @RequestParam(required = false) LocalDate start,
      @RequestParam(required = false) LocalDate end,
      @RequestParam(required = false, name = "as_of") LocalDate asOf,
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
  public ResponseEntity<List<SeriesSearchResult>> search(
      @RequestParam String q,
      @RequestParam(name = "country") String country,
      @RequestParam(name = "freq") String freq,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
    Frequency frequency = parseEnum(freq, Frequency::valueOf, "freq");
    var results = searchService.search(q, country, frequency, page, pageSize);
    return ResponseEntity.ok(results);
  }

  @PostMapping("/batch")
  public ResponseEntity<List<?>> batch() {
    syncService.reindexAllAsync();
    return ResponseEntity.accepted().body(Collections.emptyList());
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



