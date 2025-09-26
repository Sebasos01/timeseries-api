package com.ospicorp.timeseriesapi.series.service;

import static org.junit.jupiter.api.Assertions.*;

import com.ospicorp.timeseriesapi.series.model.DataPoint;
import com.ospicorp.timeseriesapi.series.model.enums.Frequency;
import com.ospicorp.timeseriesapi.series.model.enums.Transform;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransformerTest {

  @Test
  void asIsReturnsSameList() {
    var input = List.of(new DataPoint(LocalDate.of(2020, 1, 31), 1d));
    assertSame(input, Transformer.apply(input, Transform.AS_IS, Frequency.M));
  }

  @Test
  void diffHandlesNullsAndFirstValue() {
    var dates = List.of(
        LocalDate.of(2020, 1, 31),
        LocalDate.of(2020, 2, 29),
        LocalDate.of(2020, 3, 31),
        LocalDate.of(2020, 4, 30));
    var input = List.of(
        new DataPoint(dates.get(0), 10d),
        new DataPoint(dates.get(1), 15d),
        new DataPoint(dates.get(2), null),
        new DataPoint(dates.get(3), 20d));

    var output = Transformer.apply(input, Transform.DIFF, Frequency.M);

    assertNull(output.get(0).value());
    assertEquals(5d, output.get(1).value());
    assertNull(output.get(2).value());
    assertNull(output.get(3).value());
  }

  @Test
  void pctChangeHandlesZerosAndMissing() {
    var input = List.of(
        new DataPoint(LocalDate.of(2020, 1, 31), 10d),
        new DataPoint(LocalDate.of(2020, 2, 29), 12d),
        new DataPoint(LocalDate.of(2020, 3, 31), 0d),
        new DataPoint(LocalDate.of(2020, 4, 30), null),
        new DataPoint(LocalDate.of(2020, 5, 31), 9d));

    var output = Transformer.apply(input, Transform.PCT_CHANGE, Frequency.M);

    assertNull(output.get(0).value());
    assertEquals(20d, output.get(1).value());
    assertEquals(-100d, output.get(2).value());
    assertNull(output.get(3).value());
    assertNull(output.get(4).value());
  }

  @Test
  void momMatchesPctChange() {
    var input = List.of(
        new DataPoint(LocalDate.of(2020, 1, 31), 10d),
        new DataPoint(LocalDate.of(2020, 2, 29), 12d),
        new DataPoint(LocalDate.of(2020, 3, 31), 9d));
    var pct = Transformer.apply(input, Transform.PCT_CHANGE, Frequency.M);
    var mom = Transformer.apply(input, Transform.MOM, Frequency.M);
    assertEquals(pct, mom);
  }

  @Test
  void yoyUsesLagBasedOnFrequencyAndHandlesMissing() {
    List<DataPoint> input = new ArrayList<>();
    LocalDate start = LocalDate.of(2019, 1, 31);
    for (int i = 0; i < 14; i++) {
      double base = 100d + i;
      Double value = (i == 12) ? null : base;
      input.add(new DataPoint(start.plusMonths(i), value));
    }

    var output = Transformer.apply(input, Transform.YOY, Frequency.M);

    for (int i = 0; i < 12; i++) {
      assertNull(output.get(i).value());
    }
    assertNull(output.get(12).value());
    double expected = ((input.get(13).value() / input.get(1).value()) - 1d) * 100d;
    assertEquals(expected, output.get(13).value(), 1e-6);
  }

  @Test
  void ytdResetsEachYearAndTreatsMissingGracefully() {
    var input = List.of(
        new DataPoint(LocalDate.of(2020, 1, 31), 100d),
        new DataPoint(LocalDate.of(2020, 2, 29), 110d),
        new DataPoint(LocalDate.of(2020, 3, 31), null),
        new DataPoint(LocalDate.of(2020, 4, 30), 130d),
        new DataPoint(LocalDate.of(2021, 1, 31), null),
        new DataPoint(LocalDate.of(2021, 2, 28), 90d),
        new DataPoint(LocalDate.of(2021, 3, 31), 99d));

    var output = Transformer.apply(input, Transform.YTD, Frequency.M);

    assertEquals(0d, output.get(0).value());
    assertEquals(10d, output.get(1).value());
    assertNull(output.get(2).value());
    assertEquals(30d, output.get(3).value());
    assertNull(output.get(4).value());
    assertEquals(0d, output.get(5).value());
    assertEquals(((99d / 90d) - 1d) * 100d, output.get(6).value(), 1e-6);
  }
}
