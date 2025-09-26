package com.ospicorp.timeseriesapi.series.model;

import java.time.LocalDate;

// Value object for data point (no JPA mapping needed on hot path)
public record DataPoint(LocalDate date, Double value) {}
