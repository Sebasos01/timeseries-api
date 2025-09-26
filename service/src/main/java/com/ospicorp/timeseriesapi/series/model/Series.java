package com.ospicorp.timeseriesapi.series.model;

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

  public void setSeriesId(String seriesId) {
    this.seriesId = seriesId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
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

  public void setFrequency(char frequency) {
    this.frequency = frequency;
  }

  public String getUnit() {
    return unit;
  }

  public void setUnit(String unit) {
    this.unit = unit;
  }

  public String getGeography() {
    return geography;
  }

  public void setGeography(String geography) {
    this.geography = geography;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public boolean isAdjusted() {
    return isAdjusted;
  }

  public void setAdjusted(boolean adjusted) {
    isAdjusted = adjusted;
  }

  public LocalDate getStartDate() {
    return startDate;
  }

  public void setStartDate(LocalDate startDate) {
    this.startDate = startDate;
  }

  public LocalDate getEndDate() {
    return endDate;
  }

  public void setEndDate(LocalDate endDate) {
    this.endDate = endDate;
  }

  public Instant getLastUpdate() {
    return lastUpdate;
  }

  public void setLastUpdate(Instant lastUpdate) {
    this.lastUpdate = lastUpdate;
  }
}

