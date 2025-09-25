package app.series;

import java.util.ArrayList;
import java.util.List;

public final class Transformer {
  private Transformer() {
  }

  public static List<DataPoint> apply(List<DataPoint> in, Transform t, Frequency f) {
    if (t == Transform.AS_IS) return in;
    List<DataPoint> out = new ArrayList<>(in.size());
    if (in.isEmpty()) return out;

    switch (t) {
      case DIFF -> {
        Double prev = null;
        for (var p : in) {
          Double value = p.value();
          Double v = (prev == null || value == null) ? null : value - prev;
          out.add(new DataPoint(p.date(), v));
          prev = value;
        }
      }
      case PCT_CHANGE, MOM -> {
        Double prev = null;
        for (var p : in) {
          Double value = p.value();
          Double v = (prev == null || value == null || prev == 0d)
              ? null
              : ((value / prev) - 1d) * 100d;
          out.add(new DataPoint(p.date(), v));
          prev = value;
        }
      }
      case YOY -> {
        int lag = lagForYoY(f);
        for (int i = 0; i < in.size(); i++) {
          var current = in.get(i);
          Double value = current.value();
          Double v = null;
          if (value != null && i >= lag) {
            Double base = in.get(i - lag).value();
            if (base != null && base != 0d) {
              v = ((value / base) - 1d) * 100d;
            }
          }
          out.add(new DataPoint(current.date(), v));
        }
      }
      case YTD -> {
        int currentYear = Integer.MIN_VALUE;
        Double base = null;
        for (var p : in) {
          var date = p.date();
          Double value = p.value();
          if (date.getYear() != currentYear) {
            currentYear = date.getYear();
            base = null;
          }

          Double v = null;
          if (value != null) {
            if (base == null) {
              base = value;
              v = 0d;
            } else if (base == 0d) {
              v = (value == 0d) ? 0d : null;
            } else {
              v = ((value / base) - 1d) * 100d;
            }
          }
          out.add(new DataPoint(date, v));
        }
      }
      default -> {
        return in;
      }
    }
    return out;
  }

  private static int lagForYoY(Frequency frequency) {
    return switch (frequency) {
      case NATIVE, A -> 1;
      case Q -> 4;
      case M -> 12;
      case W -> 52;
      case D -> 365;
    };
  }
}