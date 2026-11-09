package com.moviemux.behavior;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.movie.domain.model.MovieStatisticsSnapshot.Film;
import com.moviemux.movie.domain.model.MovieStatisticsSnapshot.Genre;
import com.moviemux.movie.domain.repository.MovieStatisticsReader;
import com.moviemux.statistic.domain.model.MovieStatistic;
import com.moviemux.statistic.domain.usecase.impl.MovieStatisticsUseCaseImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Legacy defects are diagnostics pending an explicitly reviewed fix. */
class StatisticsBehaviorTest {
  private final MovieStatisticsReader reader = mock(MovieStatisticsReader.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);
  private final MovieStatisticsUseCaseImpl statistics =
      new MovieStatisticsUseCaseImpl(reader, clock);

  @Test
  void emptyCatalogProducesZeroAveragesAndSevenMonthlyBuckets() {
    MovieStatistic result = statistics.execute();
    assertThat(result.getTotalNumberOfMovies()).isZero();
    assertThat(result.getAverageRate()).isZero();
    assertThat(result.getAverageRuntime()).isZero();
    assertThat(result.getMoviesByGender()).isEmpty();
    assertThat(result.getMoviesSixMonthsAgo()).hasSize(7);
    assertThat(result.getMoviesSixMonthsAgo().values()).containsOnly(0L);
  }

  @Test
  void averagesRoundRatingToTwoDecimalsButTruncateRuntimeAndOmitEmptyGenres() {
    when(reader.movies())
        .thenReturn(
            List.of(
                new Film(OffsetDateTime.now(clock).minusDays(2), 91),
                new Film(OffsetDateTime.now(clock).minusYears(1), 92)));
    when(reader.ratings()).thenReturn(List.of(8, 8, 9));
    when(reader.genres()).thenReturn(List.of(new Genre("Drama", 2), new Genre("Empty", 0)));
    MovieStatistic result = statistics.execute();
    assertThat(result.getTotalNumberOfMovies()).isEqualTo(2);
    assertThat(result.getAverageRuntime()).isEqualTo(91);
    assertThat(result.getAverageRate()).isEqualTo(8.33);
    assertThat(result.getMoviesByGender()).containsExactlyEntriesOf(Map.of("Drama", 2));
    assertThat(result.getMoviesSixMonthsAgo().values().stream().mapToLong(Long::longValue).sum())
        .isEqualTo(1);
  }

  @Test
  void legacyNullRatingFailsInsteadOfSilentlyAveragingMissingData() {
    when(reader.ratings()).thenReturn(Arrays.asList((Integer) null));
    assertThatThrownBy(statistics::execute).isInstanceOf(NullPointerException.class);
  }
}
