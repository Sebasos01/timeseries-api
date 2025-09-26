package app.series;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SeriesDataResponse(
    @JsonProperty("series_id") String seriesId,
    String name,
    @JsonProperty("freq") String frequency,
    String unit,
    @JsonProperty("as_of") LocalDate asOf,
    String transform,
    String fill,
    @JsonProperty("start_date") LocalDate startDate,
    @JsonProperty("end_date") LocalDate endDate,
    @JsonProperty("point_count") int pointCount,
    List<List<Object>> points,
    Map<String, Object> metadata
) {}