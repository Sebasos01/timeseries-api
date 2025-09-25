package app.series;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Filler {
  private Filler() {
  }

  public static List<DataPoint> fill(List<DataPoint> in, FillPolicy policy) {
    if (policy == FillPolicy.NONE) return in;
    List<DataPoint> out = new ArrayList<>(in.size());
    if (policy == FillPolicy.FFILL) {
      Double last = null;
      for (var p : in) {
        last = (p.value() != null) ? p.value() : last;
        out.add(new DataPoint(p.date(), last));
      }
      return out;
    }
    Double next = null;
    for (int i = in.size() - 1; i >= 0; --i) {
      var p = in.get(i);
      next = (p.value() != null) ? p.value() : next;
      out.add(new DataPoint(p.date(), next));
    }
    Collections.reverse(out);
    return out;
  }
}