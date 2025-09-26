package com.ospicorp.timeseriesapi.config;

import com.ospicorp.timeseriesapi.series.model.Series;
import com.ospicorp.timeseriesapi.series.repository.SeriesRepository;
import com.ospicorp.timeseriesapi.series.service.SeriesSearchService;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DatabaseSeeder implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(DatabaseSeeder.class);
  private static final String MOCK_SOURCE = "MockData";
  private static final String GDP_INDICATOR_DESCRIPTION =
      "Gross Domestic Product with quarterly observations";
  private static final String CPI_INDICATOR_DESCRIPTION =
      "Consumer Price Index with monthly observations";
  private static final String UNEMPLOYMENT_DESCRIPTION =
      "Unemployment insurance weekly claims";
  private static final String STOCK_DESCRIPTION =
      "Composite stock market index";
  private static final String POPULATION_DESCRIPTION =
      "Resident population estimates";

  private final SeriesRepository seriesRepository;
  private final JdbcTemplate jdbcTemplate;
  private final SeriesSearchService searchService;
  private final Environment environment;
  private final boolean seedEnabled;
  private final Random random = new Random(8675309L);

  public DatabaseSeeder(SeriesRepository seriesRepository,
      JdbcTemplate jdbcTemplate,
      SeriesSearchService searchService,
      Environment environment,
      @Value("${timeseries.seed.enabled:true}") boolean seedEnabled) {
    this.seriesRepository = seriesRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.searchService = searchService;
    this.environment = environment;
    this.seedEnabled = seedEnabled;
  }

  @Override
  public void run(String... args) {
    if (!seedEnabled) {
      log.info("Database seeding disabled via property timeseries.seed.enabled=false");
      return;
    }
    if (environment.acceptsProfiles(Profiles.of("prod"))) {
      log.info("Skipping database seeding because active profile includes prod");
      return;
    }
    long existing = seriesRepository.count();
    if (existing > 0) {
      log.info("Database already contains {} series entries; skipping seeding", existing);
      return;
    }
    seedDatabase();
  }

  @Transactional
  void seedDatabase() {
    log.info("Seeding database with reference time-series dataset");
    Instant lastUpdatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    List<SeedSeries> seeds = buildSeedDefinitions(lastUpdatedAt);
    List<Series> series = new ArrayList<>(seeds.size());
    for (SeedSeries seed : seeds) {
      series.add(seed.series());
    }
    seriesRepository.saveAll(series);
    insertSeriesData(seeds);
    insertRevisionHistory(seeds);
    long totalPoints = seeds.stream().mapToLong(seed -> seed.points().size()).sum();
    log.info("Inserted {} series metadata rows and {} data points", series.size(), totalPoints);
    try {
      searchService.bulkIndexSeries(series);
      log.info("Indexed seeded series into OpenSearch");
    } catch (Exception ex) {
      log.warn("Failed to index seeded series into OpenSearch: {}", ex.getMessage());
    }
  }

  private List<SeedSeries> buildSeedDefinitions(Instant lastUpdatedAt) {
    List<SeedSeries> seeds = new ArrayList<>();

    // Quarterly GDP series (US and Brazil, SA/NSA)
    seeds.add(createSeries("US.GDP.Q.NSA", "US GDP (Quarterly, NSA)",
        GDP_INDICATOR_DESCRIPTION + " for the United States, not seasonally adjusted.", 'Q',
        "USD Billion", "US", false, lastUpdatedAt, LocalDate.of(2018, 3, 31), 24,
        18500.0, 160.0, 120.0, 220.0));
    seeds.add(createSeries("US.GDP.Q.SA", "US GDP (Quarterly, SA)",
        GDP_INDICATOR_DESCRIPTION + " for the United States, seasonally adjusted.", 'Q',
        "USD Billion", "US", true, lastUpdatedAt, LocalDate.of(2018, 3, 31), 24,
        18750.0, 150.0, 90.0, 40.0));
    seeds.add(createSeries("BR.GDP.Q.NSA", "Brazil GDP (Quarterly, NSA)",
        GDP_INDICATOR_DESCRIPTION + " for Brazil, not seasonally adjusted.", 'Q',
        "BRL Billion", "BR", false, lastUpdatedAt, LocalDate.of(2018, 3, 31), 24,
        1600.0, 45.0, 70.0, 140.0));
    seeds.add(createSeries("BR.GDP.Q.SA", "Brazil GDP (Quarterly, SA)",
        GDP_INDICATOR_DESCRIPTION + " for Brazil, seasonally adjusted.", 'Q',
        "BRL Billion", "BR", true, lastUpdatedAt, LocalDate.of(2018, 3, 31), 24,
        1650.0, 42.0, 55.0, 35.0));

    // Monthly CPI series (US and Brazil, SA/NSA)
    seeds.add(createSeries("US.CPI.M.NSA", "US CPI (Monthly, NSA)",
        CPI_INDICATOR_DESCRIPTION + " for the United States, not seasonally adjusted.", 'M',
        "Index 2015=100", "US", false, lastUpdatedAt, LocalDate.of(2019, 1, 31), 72,
        100.0, 0.45, 0.6, 1.8));
    seeds.add(createSeries("US.CPI.M.SA", "US CPI (Monthly, SA)",
        CPI_INDICATOR_DESCRIPTION + " for the United States, seasonally adjusted.", 'M',
        "Index 2015=100", "US", true, lastUpdatedAt, LocalDate.of(2019, 1, 31), 72,
        100.2, 0.44, 0.4, 0.3));
    seeds.add(createSeries("BR.CPI.M.NSA", "Brazil CPI (Monthly, NSA)",
        CPI_INDICATOR_DESCRIPTION + " for Brazil, not seasonally adjusted.", 'M',
        "Index 2015=100", "BR", false, lastUpdatedAt, LocalDate.of(2019, 1, 31), 72,
        95.0, 0.60, 0.8, 2.4));
    seeds.add(createSeries("BR.CPI.M.SA", "Brazil CPI (Monthly, SA)",
        CPI_INDICATOR_DESCRIPTION + " for Brazil, seasonally adjusted.", 'M',
        "Index 2015=100", "BR", true, lastUpdatedAt, LocalDate.of(2019, 1, 31), 72,
        95.4, 0.58, 0.5, 0.4));

    // Weekly unemployment claims (United States)
    seeds.add(createSeries("US.UNEMP.W", "US Weekly Jobless Claims",
        UNEMPLOYMENT_DESCRIPTION + " for the United States.", 'W',
        "Initial claims (thousands)", "US", false, lastUpdatedAt, LocalDate.of(2023, 1, 2), 64,
        220.0, -0.35, 15.0, 8.0));

    // Daily stock index values (United States)
    seeds.add(createSeries("US.STOCK.D", "US Equity Index (Daily)",
        STOCK_DESCRIPTION + " for the United States.", 'D',
        "Index points", "US", true, lastUpdatedAt, LocalDate.of(2024, 1, 2), 120,
        4200.0, 2.2, 25.0, 12.0));

    // Annual population estimates (United States)
    seeds.add(createSeries("US.POP.A", "US Population (Annual)",
        POPULATION_DESCRIPTION + " for the United States.", 'A',
        "Millions of people", "US", false, lastUpdatedAt, LocalDate.of(2010, 12, 31), 15,
        309.0, 1.55, 0.25, 0.0));

    return seeds;
  }

  private SeedSeries createSeries(String seriesId, String name, String description, char frequency,
      String unit, String geography, boolean adjusted, Instant lastUpdatedAt,
      LocalDate startDate, int periods, double baseValue, double trendPerStep,
      double noiseAmplitude, double seasonalAmplitude) {
    Period step = stepForFrequency(frequency);
    List<SeedPoint> points = new ArrayList<>(periods);
    LocalDate current = startDate;
    int seasonalPeriod = seasonalPeriod(frequency);
    for (int i = 0; i < periods; i++) {
      double trendComponent = baseValue + (i * trendPerStep);
      double noise = noiseAmplitude > 0 ? random.nextGaussian() * noiseAmplitude : 0.0;
      double seasonal = 0.0;
      if (seasonalAmplitude > 0 && seasonalPeriod > 1) {
        seasonal = seasonalAmplitude
            * Math.sin((2.0 * Math.PI * (i % seasonalPeriod)) / seasonalPeriod);
      }
      double rawValue = Math.max(0.0, trendComponent + noise + seasonal);
      double roundedValue = Math.round(rawValue * 100.0) / 100.0;
      points.add(new SeedPoint(current, roundedValue));
      current = current.plus(step);
    }
    LocalDate endDate = startDate.plus(step.multipliedBy(periods - 1));

    Series series = new Series();
    series.setSeriesId(seriesId);
    series.setName(name);
    series.setDescription(description);
    series.setFrequency(frequency);
    series.setUnit(unit);
    series.setGeography(geography);
    series.setSource(MOCK_SOURCE);
    series.setAdjusted(adjusted);
    series.setStartDate(startDate);
    series.setEndDate(endDate);
    series.setLastUpdate(lastUpdatedAt);

    return new SeedSeries(series, points);
  }

  private void insertSeriesData(List<SeedSeries> seeds) {
    final String sql = "INSERT INTO series_data(series_id, ts_date, value) VALUES (?, ?, ?)";
    for (SeedSeries seed : seeds) {
      List<Object[]> batchArgs = new ArrayList<>(seed.points().size());
      for (SeedPoint point : seed.points()) {
        batchArgs.add(new Object[]{
            seed.series().getSeriesId(),
            Date.valueOf(point.date()),
            point.value()
        });
      }
      jdbcTemplate.batchUpdate(sql, batchArgs);
    }
  }

  private void insertRevisionHistory(List<SeedSeries> seeds) {
    Optional<SeedSeries> maybeSeries = seeds.stream()
        .filter(seed -> "US.GDP.Q.NSA".equals(seed.series().getSeriesId()))
        .findFirst();
    if (maybeSeries.isEmpty()) {
      return;
    }
    SeedSeries target = maybeSeries.get();
    LocalDate revisionDate = LocalDate.of(2023, 12, 30);
    target.points().stream()
        .filter(point -> point.date().equals(revisionDate))
        .findFirst()
        .ifPresent(point -> {
          double priorValue = Math.max(0.0, Math.round(point.value() * 0.98 * 100.0) / 100.0);
          Instant revisionTime = Instant.parse("2024-02-15T00:00:00Z");
          jdbcTemplate.update(
              "INSERT INTO series_data_history(series_id, ts_date, value, revision_time) VALUES (?, ?, ?, ?)",
              target.series().getSeriesId(),
              Date.valueOf(point.date()),
              priorValue,
              Timestamp.from(revisionTime));
        });
  }

  private Period stepForFrequency(char frequency) {
    return switch (frequency) {
      case 'D' -> Period.ofDays(1);
      case 'W' -> Period.ofWeeks(1);
      case 'M' -> Period.ofMonths(1);
      case 'Q' -> Period.ofMonths(3);
      case 'A' -> Period.ofYears(1);
      default -> throw new IllegalArgumentException("Unsupported frequency: " + frequency);
    };
  }

  private int seasonalPeriod(char frequency) {
    return switch (frequency) {
      case 'M' -> 12;
      case 'Q' -> 4;
      case 'W' -> 52;
      case 'D' -> 7;
      default -> 1;
    };
  }

  private record SeedSeries(Series series, List<SeedPoint> points) {}

  private record SeedPoint(LocalDate date, double value) {}
}

