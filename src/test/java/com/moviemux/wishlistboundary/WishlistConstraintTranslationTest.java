package com.moviemux.wishlistboundary;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.moviemux.user.domain.repository.UserIdentityLookup;
import com.moviemux.wishlist.adapter.repository.postgres.impl.MovieWishlistPostgresRepository;
import com.moviemux.wishlist.adapter.repository.postgres.impl.MoviesWishlistsPostgresRepository;
import com.moviemux.wishlist.adapter.repository.postgres.impl.WishlistPostgresRepository;
import com.moviemux.wishlist.adapter.repository.postgres.mapper.WishlistEntityMapper;
import com.moviemux.wishlist.domain.exception.WishlistPersistenceConflict;
import com.moviemux.wishlist.domain.model.MovieWishlist;
import com.moviemux.wishlist.domain.model.MoviesWishlists;
import com.moviemux.wishlist.domain.model.MoviesWishlistsKey;
import com.moviemux.wishlist.domain.model.Wishlist;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;

class WishlistConstraintTranslationTest {
  enum Target {
    WISHLIST("user_wishlist_user_id_name_key"),
    CATALOG_MOVIE("movie_wishlist_pkey"),
    MEMBERSHIP("movie_wishlist_user_wishlist_pkey");

    final String constraint;

    Target(String constraint) {
      this.constraint = constraint;
    }
  }

  @ParameterizedTest
  @EnumSource(Target.class)
  void recognizedUniqueConstraintPreservesCauseInBusinessConflict(Target target) {
    DataIntegrityViolationException failure = failure("23505", target.constraint);
    assertThatThrownBy(operation(target, failure)::run)
        .isInstanceOf(WishlistPersistenceConflict.class)
        .hasCause(failure);
  }

  @ParameterizedTest
  @MethodSource("unrelatedFailures")
  void unrelatedIntegrityFailuresRemainTechnical(
      Target target, String sqlState, String constraint) {
    DataIntegrityViolationException failure = failure(sqlState, constraint);
    assertThatThrownBy(operation(target, failure)::run)
        .isSameAs(failure)
        .isNotInstanceOf(WishlistPersistenceConflict.class);
  }

  @ParameterizedTest
  @EnumSource(Target.class)
  void plainIntegrityFailureWithoutStructuredConstraintMetadataIsNotGuessedFromMessage(
      Target target) {
    DataIntegrityViolationException failure =
        new DataIntegrityViolationException("23505 " + target.constraint);
    assertThatThrownBy(operation(target, failure)::run).isSameAs(failure);
  }

  private static Stream<Arguments> unrelatedFailures() {
    return Stream.of(Target.values())
        .flatMap(
            target ->
                Stream.of(
                    Arguments.of(target, "23503", target.constraint),
                    Arguments.of(target, "23502", target.constraint),
                    Arguments.of(target, "23505", "unrecognized_unique_constraint"),
                    Arguments.of(target, "23505", null),
                    Arguments.of(target, null, target.constraint)));
  }

  private Runnable operation(Target target, DataIntegrityViolationException failure) {
    WishlistEntityMapper mapper = new WishlistEntityMapper(mock(UserIdentityLookup.class));
    return switch (target) {
      case WISHLIST -> {
        var repository = mock(com.moviemux.wishlist.adapter.repository.WishlistRepository.class);
        when(repository.saveAndFlush(any())).thenThrow(failure);
        var adapter = new WishlistPostgresRepository(repository, mapper);
        yield () -> adapter.save(Wishlist.builder().name("Synthetic").build());
      }
      case CATALOG_MOVIE -> {
        var repository =
            mock(com.moviemux.wishlist.adapter.repository.MovieWishlistRepository.class);
        when(repository.saveAndFlush(any())).thenThrow(failure);
        var adapter = new MovieWishlistPostgresRepository(repository, mapper);
        yield () -> adapter.save(MovieWishlist.builder().tmdbId(100L).build());
      }
      case MEMBERSHIP -> {
        var repository =
            mock(com.moviemux.wishlist.adapter.repository.MoviesWishlistsRepository.class);
        when(repository.saveAllAndFlush(any())).thenThrow(failure);
        var adapter = new MoviesWishlistsPostgresRepository(repository, mapper);
        yield () ->
            adapter.saveAll(
                List.of(
                    MoviesWishlists.builder()
                        .id(new MoviesWishlistsKey(UUID.randomUUID(), 100L))
                        .build()));
      }
    };
  }

  private DataIntegrityViolationException failure(String sqlState, String constraint) {
    var violation =
        new ConstraintViolationException(
            "synthetic database rejection", new SQLException("synthetic", sqlState), constraint);
    return new DataIntegrityViolationException(
        "synthetic wrapper", new IllegalStateException("nested cause", violation));
  }
}
