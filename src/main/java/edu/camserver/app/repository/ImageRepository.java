package edu.camserver.app.repository;

import edu.camserver.app.model.Image;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.time.LocalDateTime;
import java.util.List;

public interface ImageRepository extends JpaRepository<Image, Long>, QuerydslPredicateExecutor<Image> {

    /** Every distinct {@code TimeZone} value on the image rows; contains {@code null} when rows without one exist. */
    @Query("select distinct i.timeZone from Image i")
    List<String> findDistinctTimeZones();

    /** The earliest capture time in the table as UTC wall-clock time, or {@code null} when it is empty. */
    @Query("select min(i.timestampUtc) from Image i")
    LocalDateTime findEarliestTimestampUtc();
}
