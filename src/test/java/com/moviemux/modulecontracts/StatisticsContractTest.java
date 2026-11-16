package com.moviemux.modulecontracts;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.movie.domain.model.MovieStatisticsSnapshot.Film;
import com.moviemux.movie.domain.model.MovieStatisticsSnapshot.Genre;
import com.moviemux.movie.domain.repository.MovieStatisticsReader;
import com.moviemux.statistic.application.spring.CachedMovieStatistics;
import com.moviemux.statistic.domain.usecase.impl.MovieStatisticsUseCaseImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;

class StatisticsContractTest {
  private final Clock clock = Clock.fixed(Instant.parse("2026-11-09T12:34:56Z"), ZoneOffset.UTC);
  private final MovieStatisticsReader reader = mock(MovieStatisticsReader.class);
  private final MovieStatisticsUseCaseImpl useCase = new MovieStatisticsUseCaseImpl(reader, clock);

  @Test
  void preservesExclusiveDayOneTimeBoundaryAndInclusiveSevenMonthBuckets() {
    OffsetDateTime boundary = OffsetDateTime.parse("2026-05-01T12:34:56Z");
    when(reader.movies())
        .thenReturn(
            List.of(
                new Film(boundary, 1),
                new Film(boundary.minusNanos(1), 1),
                new Film(boundary.plusNanos(1), 1)));
    var result = useCase.execute();
    assertThat(result.getMoviesSixMonthsAgo())
        .containsOnlyKeys(
            "maio - 2026",
            "junho - 2026",
            "julho - 2026",
            "agosto - 2026",
            "setembro - 2026",
            "outubro - 2026",
            "novembro - 2026");
    assertThat(result.getMoviesSixMonthsAgo().keySet())
        .containsExactly(
            "maio - 2026",
            "junho - 2026",
            "julho - 2026",
            "agosto - 2026",
            "setembro - 2026",
            "outubro - 2026",
            "novembro - 2026");
    assertThat(result.getMoviesSixMonthsAgo().get("maio - 2026")).isEqualTo(1);
  }

  @Test
  void preservesFutureBucketsAndSumsDuplicateGenreNames() {
    when(reader.movies())
        .thenReturn(List.of(new Film(OffsetDateTime.parse("2027-01-01T00:00:00Z"), 1)));
    when(reader.genres())
        .thenReturn(List.of(new Genre("Drama", 2), new Genre("Drama", 3), new Genre("Empty", 0)));
    var result = useCase.execute();
    assertThat(result.getMoviesSixMonthsAgo()).hasSize(8).containsEntry("janeiro - 2027", 1L);
    assertThat(result.getMoviesByGender()).containsOnlyKeys("Drama").containsEntry("Drama", 5);
  }

  @Test
  void technicalReadFailureIsNotAnEmptyCatalog() {
    IllegalStateException failure = new IllegalStateException("synthetic storage failure");
    when(reader.movies()).thenThrow(failure);
    assertThatThrownBy(useCase::execute).isSameAs(failure);
    verify(reader).movies();
    verifyNoMoreInteractions(reader);
  }

  @Test
  void cacheRemainsOnlyOnTheTechnicalDecorator() throws Exception {
    assertThat(MovieStatisticsUseCaseImpl.class.getMethod("execute").getAnnotation(Cacheable.class))
        .isNull();
    assertThat(
            CachedMovieStatistics.class.getMethod("execute").getAnnotation(Cacheable.class).value())
        .containsExactly("statistics");
  }
}
