package app.series;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class SeriesSyncService {
  private final SeriesRepository seriesRepository;
  private final SeriesSearchService searchService;

  public SeriesSyncService(SeriesRepository seriesRepository, SeriesSearchService searchService) {
    this.seriesRepository = seriesRepository;
    this.searchService = searchService;
  }

  @Async
  public CompletableFuture<Void> syncSeries(Series series) {
    if (series != null) {
      searchService.bulkIndexSeries(Collections.singletonList(series));
    }
    return CompletableFuture.completedFuture(null);
  }

  @Async
  public CompletableFuture<Void> reindexAllAsync() {
    List<Series> allSeries = seriesRepository.findAll();
    searchService.bulkIndexSeries(allSeries);
    return CompletableFuture.completedFuture(null);
  }
}
