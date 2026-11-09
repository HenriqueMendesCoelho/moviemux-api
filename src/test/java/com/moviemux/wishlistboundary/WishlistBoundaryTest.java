package com.moviemux.wishlistboundary;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.kronusintegrationtool.domain.repository.KronusCatalogRepository;
import com.moviemux.movie.domain.model.MovieCatalogEntry;
import com.moviemux.movie.domain.repository.MovieReadLookup;
import com.moviemux.wishlist.adapter.controller.dto.MovieWishlistRequestDto;
import com.moviemux.wishlist.adapter.controller.dto.WishlistRequestDto;
import com.moviemux.wishlist.adapter.controller.dto.WishlistResponseDto;
import com.moviemux.wishlist.adapter.controller.mapper.WishlistHttpMapper;
import com.moviemux.wishlist.adapter.repository.postgres.mapper.WishlistEntityMapper;
import com.moviemux.wishlist.domain.command.AddWishlistMovieCommand;
import com.moviemux.wishlist.domain.model.*;
import com.moviemux.wishlist.domain.repository.*;
import com.moviemux.wishlist.domain.usecase.AddWishlistMovieUseCase;
import com.moviemux.wishlist.domain.usecase.impl.DeleteMovieWishlistUseCaseImpl;
import com.moviemux.wishlist.domain.usecase.impl.SearchMoviesAlreadyRatedImpl;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WishlistBoundaryTest {
  private final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000010");
  private final UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000020");
  private final com.moviemux.user.domain.repository.UserIdentityLookup identities =
      org.mockito.Mockito.mock(com.moviemux.user.domain.repository.UserIdentityLookup.class);
  private final WishlistEntityMapper mapper = new WishlistEntityMapper(identities);

  private MoviesWishlists item(long tmdb, int order) {
    return MoviesWishlists.builder()
        .id(new MoviesWishlistsKey(id, tmdb))
        .wishlist(Wishlist.builder().id(id).build())
        .movieWishlist(
            MovieWishlist.builder()
                .tmdbId(tmdb)
                .title("Portuguese")
                .titleEnglish("English")
                .urlImage("https://example.invalid/poster")
                .releaseDate(LocalDate.of(2020, 1, 2))
                .build())
        .wishlistOrder(order)
        .build();
  }

  @Test
  void builderAndGetterProtectMembershipAndNestedMovieAliases() {
    MoviesWishlists original = item(9, 1);
    List<MoviesWishlists> items = new ArrayList<>(List.of(original));
    Wishlist wishlist = Wishlist.builder().moviesWishlists(items).build();
    items.clear();
    original.getMovieWishlist().setTitle("changed");
    original.getId().setMovieId(99L);
    assertThat(wishlist.getMoviesWishlists()).hasSize(1);
    assertThat(wishlist.getMoviesWishlists().getFirst().getMovieWishlist().getTitle())
        .isEqualTo("Portuguese");
    wishlist.getMoviesWishlists().getFirst().getMovieWishlist().setTitle("outside");
    assertThat(wishlist.getMoviesWishlists().getFirst().getMovieWishlist().getTitle())
        .isEqualTo("Portuguese");
    assertThat(wishlist.getMoviesWishlists().getFirst().getId().getMovieId()).isEqualTo(9L);
    assertThatThrownBy(() -> wishlist.getMoviesWishlists().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void setterAndToBuilderDoNotShareMembershipState() {
    Wishlist wishlist = new Wishlist();
    MoviesWishlists original = item(9, 1);
    wishlist.setMoviesWishlists(List.of(original));
    original.setWishlistOrder(100);
    Wishlist copy = wishlist.toBuilder().build();
    copy.getMoviesWishlists().getFirst().setWishlistOrder(55);
    assertThat(wishlist.getMoviesWishlists().getFirst().getWishlistOrder()).isEqualTo(1);
    assertThat(copy.getMoviesWishlists().getFirst().getWishlistOrder()).isEqualTo(1);
  }

  @Test
  void mapperRoundTripPreservesIdsDatesOwnerAndCompleteOrderedItems() {
    OffsetDateTime created = OffsetDateTime.parse("2024-01-02T03:04:05Z");
    Wishlist original =
        Wishlist.builder()
            .id(id)
            .name("Later")
            .user(new WishlistOwner(owner, "Owner"))
            .shareable(true)
            .createdAt(created)
            .updatedAt(created.plusHours(2))
            .moviesWishlists(List.of(item(20, 1), item(10, 2)))
            .build();
    org.mockito.Mockito.when(identities.find(owner))
        .thenReturn(
            java.util.Optional.of(
                new com.moviemux.user.domain.model.UserIdentity(
                    owner, "Owner", "owner@example.test", java.util.Set.of())));
    Wishlist result = mapper.toDomain(mapper.toEntity(original));
    assertThat(result).usingRecursiveComparison().isEqualTo(original);
    assertThat(result.getMoviesWishlists())
        .extracting(MoviesWishlists::getWishlistOrder)
        .containsExactly(1, 2);
    assertThat(result.getMoviesWishlists())
        .extracting(i -> i.getMovieWishlist().getTmdbId())
        .containsExactly(20L, 10L);
  }

  @Test
  void mapperRetainsNullMembershipCollectionsAndNullAssociations() {
    assertThat(
            mapper.toDomain(
                (com.moviemux.wishlist.adapter.repository.postgres.entity.WishlistEntity) null))
        .isNull();
    assertThat(
            mapper.toDomain(
                (com.moviemux.wishlist.adapter.repository.postgres.entity.MovieWishlistEntity)
                    null))
        .isNull();
    assertThat(
            mapper.toDomain(
                (com.moviemux.wishlist.adapter.repository.postgres.entity.MoviesWishlistsEntity)
                    null))
        .isNull();
    assertThat(
            mapper
                .toDomain(mapper.toEntity(Wishlist.builder().id(id).build()))
                .getMoviesWishlists())
        .isNull();
  }

  @Test
  void httpMapperPreservesBodyIdentityAndLegacyImageOmission() {
    MovieWishlistRequestDto movie =
        new MovieWishlistRequestDto(99L, "PT", "EN", "ignored", LocalDate.of(2020, 1, 2));
    Wishlist request =
        WishlistHttpMapper.toDomain(new WishlistRequestDto(id, "Later", List.of(movie), true));
    assertThat(request.getId()).isEqualTo(id);
    assertThat(request.getMoviesWishlists().getFirst().getId())
        .isEqualTo(new MoviesWishlistsKey(id, 99L));
    assertThat(request.getMoviesWishlists().getFirst().getMovieWishlist().getUrlImage()).isNull();
    request.setUser(new WishlistOwner(owner, "Owner"));
    WishlistResponseDto response = new WishlistResponseDto(request);
    assertThat(response.getUser().getId()).isEqualTo(owner);
    assertThat(response.getMoviesWishlists().getFirst().getReleaseDate())
        .isEqualTo(LocalDate.of(2020, 1, 2));
  }

  @Test
  void emptyWishlistResponseKeepsLegacyNullItemsInsteadOfChangingPayloadToEmptyArray() {
    WishlistResponseDto response =
        new WishlistResponseDto(
            Wishlist.builder()
                .id(id)
                .name("Later")
                .user(new WishlistOwner(owner, "Owner"))
                .moviesWishlists(List.of())
                .build());
    assertThat(response.getMoviesWishlists()).isNull();
  }

  @Test
  void catalogFailurePropagatesWithoutWritingMembershipsOrWishlist() {
    WishlistRepository wishlists = mock(WishlistRepository.class);
    MovieWishlistRepository movies = mock(MovieWishlistRepository.class);
    MoviesWishlistsRepository memberships = mock(MoviesWishlistsRepository.class);
    KronusCatalogRepository catalog = mock(KronusCatalogRepository.class);
    when(wishlists.findById(id))
        .thenReturn(Optional.of(Wishlist.builder().id(id).moviesWishlists(List.of()).build()));
    IllegalStateException unavailable = new IllegalStateException("synthetic catalog failure");
    when(catalog.movieSummary(99L)).thenThrow(unavailable);
    AddWishlistMovieUseCase useCase =
        new AddWishlistMovieUseCase(wishlists, movies, memberships, catalog);
    assertThatThrownBy(() -> useCase.execute(new AddWishlistMovieCommand(id, owner, 99L)))
        .isSameAs(unavailable);
    verifyNoInteractions(memberships);
    verify(wishlists, never()).save(any());
    verify(movies, never()).save(any());
  }

  @Test
  void cleanupDeletesOnlyUnreferencedMovies() {
    MovieWishlistRepository movies = mock(MovieWishlistRepository.class);
    MovieWishlist orphan = MovieWishlist.builder().tmdbId(1L).referenced(false).build();
    MovieWishlist used = MovieWishlist.builder().tmdbId(2L).referenced(true).build();
    when(movies.findAll()).thenReturn(List.of(orphan, used));
    new DeleteMovieWishlistUseCaseImpl(movies).execute();
    verify(movies).delete(orphan);
    verify(movies, never()).delete(used);
  }

  @Test
  void alreadyRatedUsesPublicMovieProjectionAndPreservesCatalogOrder() {
    WishlistRepository wishlists = mock(WishlistRepository.class);
    MovieReadLookup catalog = mock(MovieReadLookup.class);
    when(wishlists.findById(id))
        .thenReturn(
            Optional.of(
                Wishlist.builder().moviesWishlists(List.of(item(9, 1), item(8, 2))).build()));
    MovieCatalogEntry first = new MovieCatalogEntry(UUID.randomUUID(), 8L, "First");
    MovieCatalogEntry second = new MovieCatalogEntry(UUID.randomUUID(), 9L, "Second");
    when(catalog.listAll())
        .thenReturn(List.of(first, new MovieCatalogEntry(UUID.randomUUID(), 7L, "Other"), second));
    assertThat(new SearchMoviesAlreadyRatedImpl(wishlists, catalog).execute(id))
        .containsExactly(first, second);
  }

  @Test
  void missingWishlistNeverLoadsMovieCatalog() {
    WishlistRepository wishlists = mock(WishlistRepository.class);
    MovieReadLookup catalog = mock(MovieReadLookup.class);
    assertThat(new SearchMoviesAlreadyRatedImpl(wishlists, catalog).execute(id)).isEmpty();
    verifyNoInteractions(catalog);
  }
}
