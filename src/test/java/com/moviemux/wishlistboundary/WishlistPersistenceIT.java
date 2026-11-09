package com.moviemux.wishlistboundary;

import static org.assertj.core.api.Assertions.*;

import com.moviemux.wishlist.domain.exception.WishlistPersistenceConflict;
import com.moviemux.wishlist.domain.model.MovieWishlist;
import com.moviemux.wishlist.domain.model.MoviesWishlists;
import com.moviemux.wishlist.domain.model.MoviesWishlistsKey;
import com.moviemux.wishlist.domain.model.Wishlist;
import com.moviemux.wishlist.domain.model.WishlistOwner;
import com.moviemux.wishlist.domain.repository.MovieWishlistRepository;
import com.moviemux.wishlist.domain.repository.MoviesWishlistsRepository;
import com.moviemux.wishlist.domain.repository.WishlistRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest
class WishlistPersistenceIT {
  @DynamicPropertySource
  static void requireDisposableLocalDatabase(DynamicPropertyRegistry properties) {
    String url = System.getenv("TEST_DATABASE_URL");
    if (url == null
        || !url.matches(
            "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*(\\?.*)?")) {
      throw new IllegalStateException(
          "TEST_DATABASE_URL must name a disposable local PostgreSQL test database");
    }
    properties.add("spring.datasource.url", () -> url);
  }

  @Autowired WishlistRepository wishlists;
  @Autowired MovieWishlistRepository movies;
  @Autowired MoviesWishlistsRepository memberships;
  @Autowired com.moviemux.user.adapter.repository.UserRepository users;
  private UUID owner;
  private final List<Long> createdMovies = new ArrayList<>();

  @BeforeEach
  void createSyntheticOwner() {
    var user =
        com.moviemux.user.domain.User.builder()
            .name("Synthetic owner")
            .email("wishlist-" + UUID.randomUUID() + "@example.invalid")
            .password("synthetic-hash")
            .build();
    owner = users.saveAndFlush(user).getId();
  }

  @AfterEach
  void removeOnlyOwnedFixtures() {
    if (owner != null) users.deleteById(owner);
    for (Long id : createdMovies) movies.findById(id).ifPresent(movies::delete);
  }

  private Wishlist create(String name) {
    return wishlists.save(
        Wishlist.builder()
            .name(name)
            .user(new WishlistOwner(owner, "Synthetic owner"))
            .shareable(false)
            .moviesWishlists(List.of())
            .build());
  }

  private MovieWishlist movie(long id) {
    createdMovies.add(id);
    return movies.save(
        MovieWishlist.builder()
            .tmdbId(id)
            .title("Portuguese title")
            .titleEnglish("English title")
            .releaseDate(LocalDate.of(2020, 1, 2))
            .urlImage("https://example.invalid/poster")
            .build());
  }

  private MoviesWishlists membership(Wishlist wishlist, MovieWishlist movie, int order) {
    return MoviesWishlists.builder()
        .id(new MoviesWishlistsKey(wishlist.getId(), movie.getTmdbId()))
        .wishlist(Wishlist.builder().id(wishlist.getId()).build())
        .movieWishlist(movie)
        .wishlistOrder(order)
        .build();
  }

  @Test
  void reconstitutionOutsideAdapterTransactionPreservesCompleteItemsAndStoredOrder() {
    Wishlist wishlist = create("Round trip");
    long seed = Math.abs(UUID.randomUUID().getMostSignificantBits() / 100);
    MovieWishlist first = movie(seed);
    MovieWishlist second = movie(seed + 1);
    memberships.saveAll(List.of(membership(wishlist, first, 2), membership(wishlist, second, 1)));
    Wishlist result = wishlists.findById(wishlist.getId()).orElseThrow();
    assertThat(result.getId()).isEqualTo(wishlist.getId());
    assertThat(result.getCreatedAt()).isNotNull();
    assertThat(result.getUser().name()).isEqualTo("Synthetic owner");
    assertThat(result.getMoviesWishlists())
        .extracting(MoviesWishlists::getWishlistOrder)
        .containsExactly(1, 2);
    assertThat(result.getMoviesWishlists())
        .extracting(i -> i.getMovieWishlist().getTmdbId())
        .containsExactly(seed + 1, seed);
    assertThat(result.getMoviesWishlists().getFirst().getMovieWishlist().getReleaseDate())
        .isEqualTo(LocalDate.of(2020, 1, 2));
    result.getMoviesWishlists().getFirst().getMovieWishlist().setTitle("Outside mutation");
    assertThat(
            wishlists
                .findById(wishlist.getId())
                .orElseThrow()
                .getMoviesWishlists()
                .getFirst()
                .getMovieWishlist()
                .getTitle())
        .isEqualTo("Portuguese title");
  }

  @Test
  void duplicateUserNameConstraintIsTranslatedWithoutCorruptingOriginal() {
    Wishlist original = create("Duplicate name");
    assertThatThrownBy(() -> create("Duplicate name"))
        .isInstanceOf(WishlistPersistenceConflict.class);
    assertThat(wishlists.findByUserIdOrderByCreatedAtDesc(owner))
        .extracting(Wishlist::getId)
        .containsExactly(original.getId());
  }

  @Test
  void membershipRemovalRetainsCatalogMovieAndUpdatesReferenceProjection() {
    Wishlist wishlist = create("Membership");
    long tmdbId = Math.abs(UUID.randomUUID().getMostSignificantBits() / 100);
    MovieWishlist movie = movie(tmdbId);
    List<MoviesWishlists> saved = memberships.saveAll(List.of(membership(wishlist, movie, 1)));
    assertThat(movies.findById(tmdbId).orElseThrow().isReferenced()).isTrue();
    memberships.deleteAll(saved);
    assertThat(wishlists.findById(wishlist.getId()).orElseThrow().getMoviesWishlists()).isEmpty();
    assertThat(movies.findById(tmdbId).orElseThrow().isReferenced()).isFalse();
  }
}
