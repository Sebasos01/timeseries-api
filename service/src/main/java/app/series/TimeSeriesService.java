package app.series;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

@Service
public class TimeSeriesService {

  private final SeriesRepository seriesRepository;
  private final SeriesDataDao dataDao;

  public TimeSeriesService(SeriesRepository seriesRepository, SeriesDataDao dataDao) {
    this.seriesRepository = seriesRepository;
    this.dataDao = dataDao;
  }

  public SeriesDto getSeries(String id) {
    if (!StringUtils.hasText(id)) {
      throw new IllegalArgumentException("id must be provided");
    }
    Series series = seriesRepository.findById(id)
        .orElseThrow(() -> new NoSuchElementException("Series not found: " + id));
    return toDto(series);
  }

  public SeriesDataResult getData(String id, LocalDate start, LocalDate end, LocalDate asOf,
      Frequency frequency, Transform transform, FillPolicy fillPolicy, int page, int pageSize) {

    if (!StringUtils.hasText(id)) {
      throw new IllegalArgumentException("id must be provided");
    }

    Series series = seriesRepository.findById(id)
        .orElseThrow(() -> new NoSuchElementException("Series not found: " + id));

    LocalDate effectiveStart = start != null ? start : series.getStartDate();
    LocalDate effectiveEnd = end != null ? end : series.getEndDate();

    if (effectiveStart == null || effectiveEnd == null) {
      throw new IllegalStateException("Series " + id + " does not have coverage dates defined");
    }

    if (effectiveStart.isAfter(effectiveEnd)) {
      throw new IllegalArgumentException("start must be before or equal to end");
    }

    List<DataPoint> points = fetchDataPoints(series, effectiveStart, effectiveEnd, asOf);

    Frequency nativeFrequency = resolveNativeFrequency(series);
    Frequency targetFrequency = frequency == Frequency.NATIVE ? nativeFrequency : frequency;

    if (targetFrequency != nativeFrequency) {
      points = Resampler.resample(points, nativeFrequency, targetFrequency);
    }

    List<DataPoint> transformed = Transformer.apply(points, transform, targetFrequency);
    List<DataPoint> finalPoints = Filler.fill(transformed, fillPolicy);

    int totalPoints = finalPoints.size();
    int totalPages = totalPoints == 0 ? 0 : (int) Math.ceil((double) totalPoints / pageSize);
    int fromIndex = Math.min(Math.max(0, (page - 1) * pageSize), totalPoints);
    int toIndex = Math.min(fromIndex + pageSize, totalPoints);
    List<DataPoint> pagePoints = new ArrayList<>(finalPoints.subList(fromIndex, toIndex));

    SeriesDataResponse response = buildResponse(series, asOf, targetFrequency, transform,
        fillPolicy, finalPoints, pagePoints, page, pageSize, totalPoints, totalPages);

    String etag = computeEtag(id, effectiveStart, effectiveEnd, asOf, targetFrequency, transform,
        fillPolicy, series.getLastUpdate(), finalPoints, page, pageSize);

    return new SeriesDataResult(response, pagePoints, etag, series.getLastUpdate());
  }

  private SeriesDto toDto(Series series) {
    return new SeriesDto(
        series.getSeriesId(),
        series.getName(),
        series.getDescription(),
        series.getFrequency() == 0 ? null : String.valueOf(series.getFrequency()),
        series.getUnit(),
        series.getGeography(),
        series.getSource(),
        series.isAdjusted(),
        series.getStartDate(),
        series.getEndDate(),
        series.getLastUpdate());
  }

  private List<DataPoint> fetchDataPoints(Series series, LocalDate start, LocalDate end,
      LocalDate asOf) {
    if (asOf != null) {
      Instant asOfInstant = asOf.atTime(LocalTime.MAX).atZone(ZoneOffset.UTC).toInstant();
      return dataDao.fetchRangeAsOf(series.getSeriesId(), start, end, asOfInstant);
    }
    return dataDao.fetchRange(series.getSeriesId(), start, end);
  }

  private Frequency resolveNativeFrequency(Series series) {
    String code = String.valueOf(series.getFrequency()).toUpperCase(Locale.ROOT);
    try {
      return Frequency.valueOf(code);
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(
          "Unsupported native frequency " + code + " for series " + series.getSeriesId(), ex);
    }
  }

  private SeriesDataResponse buildResponse(Series series, LocalDate asOf,
      Frequency effectiveFrequency, Transform transform, FillPolicy fillPolicy,
      List<DataPoint> allPoints, List<DataPoint> pagePoints, int page, int pageSize,
      int totalPoints, int totalPages) {

    List<List<Object>> tuples = new ArrayList<>(pagePoints.size());
    for (DataPoint point : pagePoints) {
      List<Object> tuple = new ArrayList<>(2);
      tuple.add(point.date() != null ? point.date().toString() : null);
      tuple.add(point.value());
      tuples.add(tuple);
    }

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("country", series.getGeography());
    metadata.put("source", series.getSource());
    metadata.put("coverage_start",
        series.getStartDate() != null ? series.getStartDate().toString() : null);
    metadata.put("coverage_end",
        series.getEndDate() != null ? series.getEndDate().toString() : null);
    metadata.put("last_update",
        series.getLastUpdate() != null ? series.getLastUpdate().toString() : null);
    metadata.put("is_adjusted", series.isAdjusted());
    metadata.values().removeIf(Objects::isNull);

    LocalDate firstDate = allPoints.isEmpty() ? null : allPoints.get(0).date();
    LocalDate lastDate = allPoints.isEmpty() ? null : allPoints.get(allPoints.size() - 1).date();
    boolean hasMore = page < totalPages;

    return new SeriesDataResponse(
        series.getSeriesId(),
        series.getName(),
        effectiveFrequency.name(),
        series.getUnit(),
        asOf,
        transform.name().toLowerCase(Locale.ROOT),
        fillPolicy.name().toLowerCase(Locale.ROOT),
        firstDate,
        lastDate,
        pagePoints.size(),
        totalPoints,
        page,
        pageSize,
        totalPages,
        hasMore,
        tuples,
        metadata);
  }

  private String computeEtag(String id, LocalDate start, LocalDate end, LocalDate asOf,
      Frequency frequency, Transform transform, FillPolicy fillPolicy, Instant lastModified,
      List<DataPoint> points, int page, int pageSize) {

    StringBuilder builder = new StringBuilder();
    builder.append(Objects.toString(id, "")).append('|')
        .append(Objects.toString(start, "")).append('|')
        .append(Objects.toString(end, "")).append('|')
        .append(Objects.toString(asOf, "")).append('|')
        .append(frequency.name()).append('|')
        .append(transform.name()).append('|')
        .append(fillPolicy.name()).append('|')
        .append(Objects.toString(lastModified, "")).append('|')
        .append(points.size()).append('|')
        .append(page).append('|')
        .append(pageSize);

    for (DataPoint point : points) {
      builder.append('|')
          .append(point.date() != null ? point.date().toString() : "")
          .append('=');
      Double value = point.value();
      if (value != null) {
        builder.append(value);
      }
    }

    byte[] bytes = builder.toString().getBytes(StandardCharsets.UTF_8);
    return "\"" + DigestUtils.md5DigestAsHex(bytes) + "\"";
  }
}