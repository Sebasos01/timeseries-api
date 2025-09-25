package app.series;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

@Service
public class TimeSeriesService {

  public SeriesDto getSeries(String id) {
    if (!StringUtils.hasText(id)) {
      throw new IllegalArgumentException("id must be provided");
    }
    return new SeriesDto(id, "Series " + id);
  }

  public List<DataPoint> getData(String id, LocalDate start, LocalDate end, LocalDate asOf,
      Frequency frequency, Transform transform, FillPolicy fillPolicy) {
    if (!StringUtils.hasText(id)) {
      throw new IllegalArgumentException("id must be provided");
    }
    List<DataPoint> data = new ArrayList<>();
    data.add(new DataPoint(LocalDate.now(), 100d));
    return data;
  }

  public String computeEtag(String id, LocalDate start, LocalDate end, LocalDate asOf,
      Frequency frequency, Transform transform, FillPolicy fillPolicy) {
    String payload = String.join("|",
        Objects.toString(id, ""),
        Objects.toString(start, ""),
        Objects.toString(end, ""),
        Objects.toString(asOf, ""),
        frequency.name(),
        transform.name(),
        fillPolicy.name());
    return "\"" + DigestUtils.md5DigestAsHex(payload.getBytes(StandardCharsets.UTF_8)) + "\"";
  }
}
