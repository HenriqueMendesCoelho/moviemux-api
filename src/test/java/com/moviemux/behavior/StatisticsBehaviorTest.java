package com.moviemux.behavior;

import com.moviemux.movie.adapter.repository.*;
import com.moviemux.movie.domain.*;
import com.moviemux.statistic.domain.MovieStatistic;
import com.moviemux.statistic.usecase.impl.MovieStatisticsUseCaseImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Baseline characterization. Tests named legacy diagnose defects pending an explicitly reviewed fix. */
@ExtendWith(MockitoExtension.class)
class StatisticsBehaviorTest {
    @Mock MovieRepository repository;
    @Mock MovieGenreRepository genreRepository;
    @Mock MovieNoteRepository movieNoteRepository;
    @InjectMocks MovieStatisticsUseCaseImpl statistics;

    @Test void emptyCatalogProducesZeroAveragesAndSevenMonthlyBuckets() {
        MovieStatistic result=statistics.getStatistics();
        assertThat(result.getTotalNumberOfMovies()).isZero();
        assertThat(result.getAverageRate()).isZero();assertThat(result.getAverageRuntime()).isZero();
        assertThat(result.getMoviesByGender()).isEmpty();
        assertThat(result.getMoviesSixMonthsAgo()).hasSize(7);
        assertThat(result.getMoviesSixMonthsAgo().values()).containsOnly(0L);
    }

    @Test void averagesRoundRatingToTwoDecimalsButTruncateRuntimeAndOmitEmptyGenres() {
        Movie a=Movie.builder().runtime(91).createdAt(OffsetDateTime.now().minusDays(2)).build();
        Movie b=Movie.builder().runtime(92).createdAt(OffsetDateTime.now().minusYears(1)).build();
        MovieNote n1=new MovieNote();n1.setNote(8);
        MovieNote n2=new MovieNote();n2.setNote(8);
        MovieNote n3=new MovieNote();n3.setNote(9);
        when(repository.findAll()).thenReturn(List.of(a,b));
        when(movieNoteRepository.findAll()).thenReturn(List.of(n1,n2,n3));
        when(genreRepository.findAll()).thenReturn(List.of(
                MovieGenre.builder().name("Drama").movies(List.of(a,b)).build(),
                MovieGenre.builder().name("Empty").movies(List.of()).build()));
        MovieStatistic result=statistics.getStatistics();
        assertThat(result.getTotalNumberOfMovies()).isEqualTo(2);
        assertThat(result.getAverageRuntime()).isEqualTo(91);
        assertThat(result.getAverageRate()).isEqualTo(8.33);
        assertThat(result.getMoviesByGender()).containsExactlyEntriesOf(Map.of("Drama",2));
        assertThat(result.getMoviesSixMonthsAgo().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(1);
    }

    @Test void legacyNullRatingFailsInsteadOfSilentlyAveragingMissingData() {
        when(movieNoteRepository.findAll()).thenReturn(List.of(new MovieNote()));
        assertThatThrownBy(statistics::getStatistics).isInstanceOf(NullPointerException.class);
    }
}
