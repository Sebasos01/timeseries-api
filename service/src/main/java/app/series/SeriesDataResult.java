package app.series;

import java.time.Instant;
import java.util.List;

public record SeriesDataResult(
    SeriesDataResponse response,
    List<DataPoint> points,
    String etag,
    Instant lastModified
) {}