package com.ospicorp.timeseriesapi.series.model;

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
    @JsonProperty("total_points") int totalPoints,
    @JsonProperty("page") int page,
    @JsonProperty("page_size") int pageSize,
    @JsonProperty("total_pages") int totalPages,
    @JsonProperty("has_more") boolean hasMore,
    List<List<Object>> points,
    Map<String, Object> metadata
) {}
