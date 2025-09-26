package com.ospicorp.timeseriesapi.series.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;

public record SeriesDto(
    @JsonProperty("series_id") String seriesId,
    String name,
    String description,
    String frequency,
    String unit,
    @JsonProperty("country") String country,
    String source,
    @JsonProperty("is_adjusted") boolean adjusted,
    @JsonProperty("start_date") LocalDate startDate,
    @JsonProperty("end_date") LocalDate endDate,
    @JsonProperty("last_update") Instant lastUpdate
) {}
