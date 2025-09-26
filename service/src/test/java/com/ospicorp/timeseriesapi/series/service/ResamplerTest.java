package com.ospicorp.timeseriesapi.series.service;

import static org.junit.jupiter.api.Assertions.*;

import com.ospicorp.timeseriesapi.series.model.DataPoint;
import com.ospicorp.timeseriesapi.series.model.enums.Frequency;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResamplerTest {

  @Test
  void resampleReturnsInputWhenFrequencyMatches() {
    var input = List.of(new DataPoint(LocalDate.of(2020, 1, 31), 1d));
    assertSame(input, Resampler.resample(input, Frequency.M, Frequency.M));
  }

  @Test
  void resampleMonthlyToQuarterKeepsLastObservation() {
    List<DataPoint> monthly = new ArrayList<>();
    LocalDate start = LocalDate.of(2020, 1, 31);
    for (int i = 0; i < 6; i++) {
      monthly.add(new DataPoint(start.plusMonths(i), (double) (i + 1)));
    }

    var out = Resampler.resample(monthly, Frequency.M, Frequency.Q);

    assertEquals(2, out.size());
    assertEquals(LocalDate.of(2020, 3, 31), out.get(0).date());
    assertEquals(3d, out.get(0).value());
    assertEquals(LocalDate.of(2020, 6, 30), out.get(1).date());
    assertEquals(6d, out.get(1).value());
  }
}

