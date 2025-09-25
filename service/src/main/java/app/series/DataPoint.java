package app.series;

import java.time.LocalDate;

// Value object for data point (no JPA mapping needed on hot path)
public record DataPoint(LocalDate date, Double value) {}