package app.series;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class FillerTest {

  @Test
  void noneReturnsOriginalList() {
    var input = List.of(new DataPoint(LocalDate.of(2020, 1, 31), 1d));
    assertSame(input, Filler.fill(input, FillPolicy.NONE));
  }

  @Test
  void forwardFillCarriesLastNonNull() {
    var input = List.of(
        new DataPoint(LocalDate.of(2020, 1, 31), 1d),
        new DataPoint(LocalDate.of(2020, 2, 29), null),
        new DataPoint(LocalDate.of(2020, 3, 31), 3d),
        new DataPoint(LocalDate.of(2020, 4, 30), null));

    var out = Filler.fill(input, FillPolicy.FFILL);

    assertEquals(1d, out.get(0).value());
    assertEquals(1d, out.get(1).value());
    assertEquals(3d, out.get(2).value());
    assertEquals(3d, out.get(3).value());
  }

  @Test
  void backFillLooksAhead() {
    var input = List.of(
        new DataPoint(LocalDate.of(2020, 1, 31), 1d),
        new DataPoint(LocalDate.of(2020, 2, 29), null),
        new DataPoint(LocalDate.of(2020, 3, 31), 3d),
        new DataPoint(LocalDate.of(2020, 4, 30), null));

    var out = Filler.fill(input, FillPolicy.BFILL);

    assertEquals(1d, out.get(0).value());
    assertEquals(3d, out.get(1).value());
    assertEquals(3d, out.get(2).value());
    assertNull(out.get(3).value());
  }
}