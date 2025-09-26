package com.ospicorp.timeseriesapi.series.service;

import com.ospicorp.timeseriesapi.series.model.DataPoint;
import com.ospicorp.timeseriesapi.series.model.enums.Frequency;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Resampler {
  private Resampler() {
  }

  public static List<DataPoint> resample(List<DataPoint> in, Frequency from, Frequency to) {
    if (to == Frequency.NATIVE || to == from) return in;
    Map<LocalDate, Double> buckets = new LinkedHashMap<>();
    for (DataPoint p : in) {
      LocalDate bucket = bucketDate(p.date(), to);
      buckets.put(bucket, p.value());
    }
    List<DataPoint> out = new ArrayList<>(buckets.size());
    for (var e : buckets.entrySet()) {
      out.add(new DataPoint(e.getKey(), e.getValue()));
    }
    return out;
  }

  private static LocalDate bucketDate(LocalDate date, Frequency to) {
    return switch (to) {
      case NATIVE, D -> date;
      case W -> date.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
      case M -> date.with(TemporalAdjusters.lastDayOfMonth());
      case Q -> endOfQuarter(date);
      case A -> date.with(TemporalAdjusters.lastDayOfYear());
    };
  }

  private static LocalDate endOfQuarter(LocalDate date) {
    int quarterEndMonth = ((date.getMonthValue() - 1) / 3 + 1) * 3;
    return LocalDate.of(date.getYear(), quarterEndMonth, 1)
        .with(TemporalAdjusters.lastDayOfMonth());
  }
}
