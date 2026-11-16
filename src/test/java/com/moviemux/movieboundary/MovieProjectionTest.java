package com.moviemux.movieboundary;

import com.moviemux.movie.domain.model.*;
import com.moviemux.movie.domain.repository.MovieRepository;
import com.moviemux.movie.domain.usecase.impl.GetMovieUseCaseImpl;
import com.moviemux.movie.domain.usecase.impl.SearchMovieNoteUseCaseImpl;
import com.moviemux.user.domain.model.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MovieProjectionTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private Movie movie(long age) {
        Movie movie = Movie.builder().id(UUID.randomUUID()).createdAt(OffsetDateTime.now(CLOCK).minusSeconds(age))
                .genres(new ArrayList<>()).notes(new ArrayList<>()).build();
        var owner = new MovieActor(OWNER, "Zoe", "zoe@example.test", Set.of(Role.USER));
        var other = new MovieActor(OTHER, "Alice", "alice@example.test", Set.of(Role.USER));
        movie.setNotes(new ArrayList<>(List.of(MovieNote.builder().user(owner).movie(movie).note(8).build(),
                MovieNote.builder().user(other).movie(movie).note(10).build())));
        return movie;
    }

    @ParameterizedTest
    @CsvSource({"1799,false", "1800,true", "1801,true"})
    void revealBoundaryPreservesLegacyTimingAndNeverErasesOriginalScores(long age, boolean revealed) throws Exception {
        Movie source = movie(age);
        MovieRepository repository = mock(MovieRepository.class);
        when(repository.findById(source.getId())).thenReturn(Optional.of(source));
        Movie result = new GetMovieUseCaseImpl(repository, CLOCK).execute(source.getId(), OWNER);
        assertThat(result).isNotSameAs(source);
        assertThat(result.isShowNotes()).isEqualTo(revealed);
        assertThat(source.getNotes()).extracting(MovieNote::getNote).containsExactly(8, 10);
        assertThat(result.getNotes().getFirst()).isNotSameAs(source.getNotes().getFirst());
        assertThat(result.getNotes().getFirst().getMovie().getNotes()).isNull();
        if (revealed) {
            assertThat(result.getNotes()).extracting(MovieNote::getNote).containsExactly(10, 8);
            verify(repository).save(source);
        } else {
            assertThat(result.getNotes()).extracting(MovieNote::getNote).containsExactly(null, 8);
            verify(repository, never()).save(any());
        }
    }

    @Test
    void noteListingMasksOnlyProjectionAndSortsAfterMasking() throws Exception {
        Movie source = movie(100);
        MovieRepository repository = mock(MovieRepository.class);
        when(repository.findById(source.getId())).thenReturn(Optional.of(source));
        var result = new SearchMovieNoteUseCaseImpl(repository).execute(source.getId(), OWNER);
        assertThat(result).extracting(MovieNote::getNote).containsExactly(8, null);
        assertThat(source.getNotes()).extracting(MovieNote::getNote).containsExactly(8, 10);
        verify(repository, never()).save(any());
    }

    @Test
    void failedRevealPersistenceDoesNotReturnSuccessfulProjection() {
        Movie source = movie(1800);
        MovieRepository repository = mock(MovieRepository.class);
        when(repository.findById(source.getId())).thenReturn(Optional.of(source));
        when(repository.save(source)).thenThrow(new IllegalStateException("synthetic write failure"));
        assertThatThrownBy(() -> new GetMovieUseCaseImpl(repository, CLOCK).execute(source.getId(), OWNER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("synthetic");
    }
}
