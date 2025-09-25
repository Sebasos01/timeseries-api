package app.series;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "series")
public class Series {

  @Id
  private String seriesId;
  private String name;
  private String description;
  private char frequency;      // 'A','Q','M','W','D'
  private String unit;
  private String geography;
  private String source;
  private boolean isAdjusted;
  private LocalDate startDate;
  private LocalDate endDate;

  @Column(name = "last_update")
  private Instant lastUpdate;

  public Series() {
    // JPA default constructor
  }

  public String getSeriesId() {
    return seriesId;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public char getFrequency() {
    return frequency;
  }

  public String getUnit() {
    return unit;
  }

  public String getGeography() {
    return geography;
  }

  public String getSource() {
    return source;
  }

  public boolean isAdjusted() {
    return isAdjusted;
  }

  public LocalDate getStartDate() {
    return startDate;
  }

  public LocalDate getEndDate() {
    return endDate;
  }

  public Instant getLastUpdate() {
    return lastUpdate;
  }
}
