package app.series;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

@Service
public class TimeSeriesService {

  public SeriesDto getSeries(String id) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must be provided");
    }
    return new SeriesDto(id, "Series " + id);
  }

  public List<DataPoint> getData(String id, LocalDate start, LocalDate end, LocalDate asOf,
      String freq, String transform, String fill) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must be provided");
    }
    List<DataPoint> data = new ArrayList<>();
    data.add(new DataPoint(Instant.now().atZone(java.time.ZoneOffset.UTC).toLocalDate(), 100d));
    return data;
  }

  public String computeEtag(String id, LocalDate start, LocalDate end, LocalDate asOf,
      String freq, String transform, String fill) {
    String payload = String.join("|",
        id,
        String.valueOf(start),
        String.valueOf(end),
        String.valueOf(asOf),
        freq,
        transform,
        fill);
    String hash = DigestUtils.md5DigestAsHex(payload.getBytes(StandardCharsets.UTF_8));
    return '"' + hash + '"';
  }
}