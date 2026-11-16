package com.moviemux.qualitycoverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moviemux.movie.domain.model.Movie;
import com.moviemux.movie.domain.model.MovieActor;
import com.moviemux.movie.domain.model.MovieDiscord;
import com.moviemux.movie.domain.model.MovieGenre;
import com.moviemux.movie.domain.model.MovieNote;
import com.moviemux.movie.domain.model.MoviePage;
import com.moviemux.movie.domain.model.MovieQuery;
import com.moviemux.movie.domain.model.MovieVisibility;
import com.moviemux.movie.domain.model.MovieWebhookSnapshot;
import com.moviemux.user.domain.model.Role;
import com.moviemux.user.domain.model.UserIdentity;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DetachedMovieModelsTest {
  private static final UUID MOVIE = UUID.fromString("00000000-0000-0000-0000-000000000041");
  private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000042");
  private static final OffsetDateTime CREATED = OffsetDateTime.parse("2020-01-02T03:04:05Z");

  private MovieActor actor(String name) {
    return new MovieActor(OWNER, name, "actor@example.test", Set.of(Role.USER));
  }

  @Test
  void projectionDetachesGenresAndWebhookReferenceWithoutChangingTheirValues() {
    Movie source = Movie.builder().id(MOVIE).portugueseTitle("Original").createdAt(CREATED).build();
    MovieGenre genre = MovieGenre.builder().id(1L).tmdbId(18L).name("Drama").build();
    source.setGenres(new ArrayList<>(List.of(genre)));
    source.setMovieDiscord(new MovieDiscord(MOVIE, "synthetic-message", source));

    Movie projected = MovieVisibility.project(source, OWNER, false);
    assertThat(projected.getGenres()).hasSize(1);
    MovieGenre projectedGenre = projected.getGenres().getFirst();
    assertThat(projectedGenre).isNotSameAs(genre);
    assertThat(projectedGenre.getId()).isEqualTo(1L);
    assertThat(projectedGenre.getTmdbId()).isEqualTo(18L);
    assertThat(projectedGenre.getName()).isEqualTo("Drama");
    assertThat(projected.getMovieDiscord()).isNotSameAs(source.getMovieDiscord());
    assertThat(projected.getMovieDiscord().getId()).isEqualTo(MOVIE);
    assertThat(projected.getMovieDiscord().getMessageId()).isEqualTo("synthetic-message");
    Movie header = projected.getMovieDiscord().getMovie();
    assertThat(header).isNotSameAs(source);
    assertThat(header.getId()).isEqualTo(MOVIE);
    assertThat(header.getPortugueseTitle()).isEqualTo("Original");
    assertThat(header.getCreatedAt()).isEqualTo(CREATED);
    assertThat(header.getNotes()).isNull();
    assertThat(header.getGenres()).isNull();
    assertThat(header.getMovieDiscord()).isNull();
    projectedGenre.setName("Changed projection");
    projected.getMovieDiscord().setMessageId("changed-reference");
    assertThat(genre.getName()).isEqualTo("Drama");
    assertThat(source.getMovieDiscord().getMessageId()).isEqualTo("synthetic-message");
  }

  @Test
  void projectionPreservesMissingCollectionsAndDoesNotInventReferences() {
    Movie source = Movie.builder().id(MOVIE).showNotes(true).build();
    Movie projected = MovieVisibility.project(source, OWNER, true);
    assertThat(projected).isNotSameAs(source);
    assertThat(projected.getId()).isEqualTo(MOVIE);
    assertThat(projected.getGenres()).isNull();
    assertThat(projected.getNotes()).isNull();
    assertThat(projected.getMovieDiscord()).isNull();
  }

  @Test
  void unorderedVisibleProjectionKeepsOriginalOrderAndDetachesNoteScores() {
    Movie source = Movie.builder().id(MOVIE).showNotes(true).build();
    MovieNote first =
        MovieNote.builder().movie(source).user(actor("Zoe")).note(2).createdAt(CREATED).build();
    MovieNote second = MovieNote.builder().movie(source).user(actor("Alice")).note(9).build();
    source.setNotes(new ArrayList<>(List.of(first, second)));
    Movie projected = MovieVisibility.project(source, OWNER, false);
    assertThat(projected.getNotes()).extracting(MovieNote::getNote).containsExactly(2, 9);
    projected.getNotes().getFirst().setNote(10);
    assertThat(first.getNote()).isEqualTo(2);
    assertThat(projected.getNotes().getFirst().getId()).isEqualTo(first.getId());
    assertThat(projected.getNotes().getFirst().getCreatedAt()).isEqualTo(CREATED);
  }

  @Test
  void noteCloneSeparatesMutableScoreWhilePreservingIdentityAndTimestamps() throws Exception {
    Movie movie = Movie.builder().id(MOVIE).build();
    MovieNote original =
        MovieNote.builder()
            .movie(movie)
            .user(actor("Alice"))
            .note(7)
            .createdAt(CREATED)
            .updatedAt(CREATED.plusDays(1))
            .build();
    MovieNote copy = original.clone();
    assertThat(copy).isNotSameAs(original);
    assertThat(copy).usingRecursiveComparison().isEqualTo(original);
    copy.setNote(null);
    assertThat(original.getNote()).isEqualTo(7);
    assertThat(copy.getId().getUserId()).isEqualTo(OWNER);
    assertThat(copy.getId().getMovieId()).isEqualTo(MOVIE);
  }

  @Test
  void rankingHandlesNullScoresAndBreaksEqualScoresAlphabetically() {
    Movie movie = Movie.builder().id(MOVIE).build();
    MovieNote zoe = MovieNote.builder().movie(movie).user(actor("Zoe")).note(8).build();
    MovieNote alice = MovieNote.builder().movie(movie).user(actor("Alice")).note(8).build();
    MovieNote missing = MovieNote.builder().movie(movie).user(actor("Missing")).note(null).build();
    var ranked = new ArrayList<>(List.of(missing, zoe, alice));
    ranked.sort(MovieNote.comparator());
    assertThat(ranked).containsExactly(alice, zoe, missing);
    ranked.sort(MovieNote.comparatorAlphabetical());
    assertThat(ranked).containsExactly(alice, missing, zoe);
  }

  @Test
  void actorProjectionCopiesRolesAndPreservesPublicIdentity() {
    var roles = new HashSet<>(Set.of(Role.USER, Role.ADM));
    var identity = new UserIdentity(OWNER, "Alice", "alice@example.test", roles);
    MovieActor actor = MovieActor.from(identity);
    roles.clear();
    assertThat(actor.getId()).isEqualTo(OWNER);
    assertThat(actor.getName()).isEqualTo("Alice");
    assertThat(actor.getEmail()).isEqualTo("alice@example.test");
    assertThat(actor.getRoles()).containsExactlyInAnyOrder(Role.USER, Role.ADM);
    assertThatThrownBy(() -> actor.getRoles().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void webhookSnapshotCopiesRatingsAndPreservesMissingCollection() {
    var rating = new MovieWebhookSnapshot.Rating("Alice", 8);
    var ratings = new ArrayList<>(List.of(rating));
    var snapshot =
        new MovieWebhookSnapshot(MOVIE, "Movie", "/image", true, "Alice", CREATED, ratings);
    ratings.clear();
    assertThat(snapshot.ratings()).containsExactly(rating);
    assertThat(snapshot.ratings().getFirst().userName()).isEqualTo("Alice");
    assertThat(snapshot.ratings().getFirst().note()).isEqualTo(8);
    assertThatThrownBy(() -> snapshot.ratings().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    var missing = new MovieWebhookSnapshot(MOVIE, "Movie", null, false, null, CREATED, null);
    assertThat(missing.ratings()).isNull();
  }

  @Test
  void pagingCopiesInputListsWithoutLosingOrderingOrTotal() {
    var order = new MovieQuery.Order("createdAt", false, false, MovieQuery.NullHandling.NULLS_LAST);
    var orders = new ArrayList<>(List.of(order));
    var query = new MovieQuery("title", "18", null, 2, 10, false, orders);
    var movie = Movie.builder().id(MOVIE).build();
    var content = new ArrayList<>(List.of(movie));
    var page = new MoviePage(content, 21, query);
    orders.clear();
    content.clear();
    assertThat(query.orders()).containsExactly(order);
    assertThat(page.content()).containsExactly(movie);
    assertThat(page.totalElements()).isEqualTo(21);
    assertThat(page.query()).isSameAs(query);
    assertThatThrownBy(() -> query.orders().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> page.content().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
