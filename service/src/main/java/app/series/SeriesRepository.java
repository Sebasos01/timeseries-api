package app.series;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SeriesRepository extends JpaRepository<Series, String> {
  @Query("""
      SELECT s FROM Series s
      WHERE (:country IS NULL OR UPPER(s.geography) = UPPER(:country))
        AND (:frequency IS NULL OR s.frequency = :frequency)
        AND (
          LOWER(s.seriesId) LIKE LOWER(CONCAT('%', :query, '%'))
          OR LOWER(s.name) LIKE LOWER(CONCAT('%', :query, '%'))
          OR LOWER(COALESCE(s.description, '')) LIKE LOWER(CONCAT('%', :query, '%'))
        )
      """)
  Page<Series> searchSeries(@Param("country") String country,
      @Param("frequency") Character frequency,
      @Param("query") String query, Pageable pageable);
}
