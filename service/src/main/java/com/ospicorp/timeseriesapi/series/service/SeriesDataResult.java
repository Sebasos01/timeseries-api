package com.ospicorp.timeseriesapi.series.service;

import com.ospicorp.timeseriesapi.series.model.SeriesDataResponse;
import com.ospicorp.timeseriesapi.series.model.DataPoint;
import java.time.Instant;
import java.util.List;

public record SeriesDataResult(
    SeriesDataResponse response,
    List<DataPoint> points,
    String etag,
    Instant lastModified
) {}

