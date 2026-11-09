package com.moviemux.behavior;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.kronusintegrationtool.domain.repository.KronusCatalogRepository;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import com.moviemux.wishlist.adapter.controller.WishlistQueryOperations;
import com.moviemux.wishlist.adapter.controller.WishlistUpdateOperations;
import com.moviemux.wishlist.domain.command.*;
import com.moviemux.wishlist.domain.exception.*;
import com.moviemux.wishlist.domain.exception.WishlistPersistenceConflict;
import com.moviemux.wishlist.domain.model.*;
import com.moviemux.wishlist.domain.model.WishlistOwner;
import com.moviemux.wishlist.domain.repository.*;
import com.moviemux.wishlist.domain.usecase.*;
import com.moviemux.wishlist.domain.usecase.impl.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Baseline characterization. Tests named legacy diagnose defects pending an explicitly reviewed
 * fix.
 */
@ExtendWith(MockitoExtension.class)
class WishlistBehaviorTest {
  @Mock WishlistRepository repository;
  @Mock MovieWishlistRepository movieWishlistRepository;
  @Mock MoviesWishlistsRepository moviesWishlistsRepository;
  @Mock KronusCatalogRepository kitRepository;
  @InjectMocks CreateUserWishlistUseCaseImpl create;
  WishlistQueryOperations search;
  @InjectMocks DeleteUserWishlistUseCaseImpl delete;
  WishlistUpdateOperations update;
  private final UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000002");

  @BeforeEach
  void assembleUseCases() {
    search =
        new WishlistQueryOperations(
            new FindUserWishlistUseCase(repository), new ListUserWishlistsUseCase(repository));
    update =
        new WishlistUpdateOperations(
            new UpdateWishlistUseCase(
                repository, movieWishlistRepository, moviesWishlistsRepository, kitRepository),
            new AddWishlistMovieUseCase(
                repository, movieWishlistRepository, moviesWishlistsRepository, kitRepository));
  }

  private Wishlist wishlist(int size) {
    List<MoviesWishlists> movies = new ArrayList<>();
    for (int i = 0; i < size; i++)
      movies.add(
          MoviesWishlists.builder()
              .id(new MoviesWishlistsKey(id, (long) i))
              .movieWishlist(MovieWishlist.builder().tmdbId((long) i).build())
              .wishlistOrder(i + 1)
              .build());
    return Wishlist.builder()
        .id(id)
        .user(new WishlistOwner(owner, null))
        .name("Watch next")
        .moviesWishlists(movies)
        .shareable(false)
        .build();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 9})
  void belowTenListsCreatesPrivateWishlist(int count) throws Exception {
    when(repository.findByUserIdOrderByCreatedAtDesc(owner))
        .thenReturn(Collections.nCopies(count, wishlist(0)));
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    Wishlist saved = create.execute(new CreateWishlistCommand("New list", owner));
    assertThat(saved.getName()).isEqualTo("New list");
    assertThat(saved.isShareable()).isFalse();
    assertThat(saved.getUser().getId()).isEqualTo(owner);
  }

  @ParameterizedTest
  @ValueSource(ints = {10, 11})
  void tenOrMoreListsRejectWithoutWrite(int count) {
    when(repository.findByUserIdOrderByCreatedAtDesc(owner))
        .thenReturn(Collections.nCopies(count, wishlist(0)));
    assertThatThrownBy(() -> create.execute(new CreateWishlistCommand("New", owner)))
        .isInstanceOf(WishlistUserReachedLimitException.class);
    verify(repository, never()).save(any());
  }

  @Test
  void legacyCreateMapsAnyIntegrityFailureToDuplicateName() {
    when(repository.save(any()))
        .thenThrow(
            new WishlistPersistenceConflict(
                new IllegalStateException("synthetic unrelated constraint")));
    assertThatThrownBy(() -> create.execute(new CreateWishlistCommand("New", owner)))
        .isInstanceOf(WishlistDuplicatedException.class);
  }

  @Test
  void ownerCanReadPrivateListUsingValueEqualUuid() throws Exception {
    Wishlist w = wishlist(0);
    when(repository.findById(id)).thenReturn(Optional.of(w));
    assertThat(search.findById(id, UUID.fromString(owner.toString()))).isSameAs(w);
  }

  @Test
  void nonOwnerCannotDiscoverPrivateList() {
    when(repository.findById(id)).thenReturn(Optional.of(wishlist(0)));
    assertThatThrownBy(
            () -> search.findById(id, UUID.fromString("00000000-0000-0000-0000-000000000099")))
        .isInstanceOf(WishlistNotFoundException.class);
  }

  @Test
  void nonOwnerCanReadSharedList() throws Exception {
    Wishlist w = wishlist(0);
    w.setShareable(true);
    when(repository.findById(id)).thenReturn(Optional.of(w));
    assertThat(search.findById(id, UUID.fromString("00000000-0000-0000-0000-000000000099")))
        .isSameAs(w);
  }

  @Test
  void missingWishlistIsNotFound() {
    assertThatThrownBy(() -> search.findById(id, owner))
        .isInstanceOf(WishlistNotFoundException.class);
  }

  @Test
  void deletingSomeoneElsesListIsNoOp() {
    when(repository.findById(id)).thenReturn(Optional.of(wishlist(2)));
    delete.execute(
        new DeleteWishlistCommand(id, UUID.fromString("00000000-0000-0000-0000-000000000099")));
    verifyNoInteractions(moviesWishlistsRepository);
    verify(repository, never()).delete(any());
  }

  @Test
  void ownerDeleteRemovesJoinRowsBeforeWishlist() {
    Wishlist w = wishlist(2);
    when(repository.findById(id)).thenReturn(Optional.of(w));
    delete.execute(new DeleteWishlistCommand(id, owner));
    InOrder order = inOrder(repository, moviesWishlistsRepository);
    order.verify(repository).findById(id);
    order.verify(moviesWishlistsRepository).deleteAll(w.getMoviesWishlists());
    order.verify(repository).delete(w);
  }

  @Test
  void addAtHundredRejectsBeforeCatalogCalls() {
    when(repository.findById(id)).thenReturn(Optional.of(wishlist(100)));
    assertThatThrownBy(() -> update.addMovieToWishlist(id, owner, 999L))
        .isInstanceOf(WishlistNotFoundException.class);
    verifyNoInteractions(movieWishlistRepository, moviesWishlistsRepository, kitRepository);
    verify(repository, never()).save(any());
  }

  @Test
  void addAtNinetyNinePreservesOrderAndAppendsHundredth() throws Exception {
    Wishlist w = wishlist(99);
    when(repository.findById(id)).thenReturn(Optional.of(w));
    when(movieWishlistRepository.findById(999L))
        .thenReturn(Optional.of(MovieWishlist.builder().tmdbId(999L).build()));
    when(moviesWishlistsRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    Wishlist saved = update.addMovieToWishlist(id, owner, 999L);
    assertThat(saved.getMoviesWishlists()).hasSize(100);
    assertThat(saved.getMoviesWishlists().getLast().getWishlistOrder()).isEqualTo(100);
    assertThat(saved.getMoviesWishlists().getLast().getId())
        .isEqualTo(new MoviesWishlistsKey(id, 999L));
    assertThat(saved.getUpdatedAt()).isNotNull();
    verifyNoInteractions(kitRepository);
  }

  @Test
  void legacyAddMovieDoesNotCheckOwner() throws Exception {
    Wishlist w = wishlist(0);
    when(repository.findById(id)).thenReturn(Optional.of(w));
    when(movieWishlistRepository.findById(999L))
        .thenReturn(Optional.of(MovieWishlist.builder().tmdbId(999L).build()));
    when(moviesWishlistsRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    Wishlist result =
        update.addMovieToWishlist(
            id, UUID.fromString("00000000-0000-0000-0000-000000000099"), 999L);
    assertThat(result.getUser().getId()).isEqualTo(owner);
    assertThat(result.getMoviesWishlists()).hasSize(1);
    verify(repository).save(w);
  }

  @Test
  void missingCatalogMovieIsSilentlyOmitted() throws Exception {
    Wishlist w = wishlist(0);
    when(repository.findById(id)).thenReturn(Optional.of(w));
    when(moviesWishlistsRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    assertThat(update.addMovieToWishlist(id, owner, 999L).getMoviesWishlists()).isEmpty();
    verify(kitRepository).movieSummary(999L);
    verify(movieWishlistRepository, never()).save(any());
  }

  @Test
  void updateRejectsNonOwnerBeforeWritesOrCatalogCalls() {
    when(repository.findById(id)).thenReturn(Optional.of(wishlist(0)));
    assertThatThrownBy(
            () ->
                update.update(wishlist(0), UUID.fromString("00000000-0000-0000-0000-000000000099")))
        .isInstanceOf(UserNotAuthorizedException.class);
    verifyNoInteractions(movieWishlistRepository, moviesWishlistsRepository, kitRepository);
    verify(repository, never()).save(any());
  }

  @Test
  void legacyUpdateChecksStoredSizeButNotIncomingHundredAndOneItems() throws Exception {
    Wishlist current = wishlist(0);
    Wishlist request = wishlist(101);
    when(repository.findById(id)).thenReturn(Optional.of(current));
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    Wishlist saved = update.update(request, owner);
    assertThat(saved.getMoviesWishlists()).hasSize(101);
    assertThat(saved.getMoviesWishlists().getLast().getWishlistOrder()).isEqualTo(101);
    verify(moviesWishlistsRepository).saveAll(request.getMoviesWishlists());
  }

  @Test
  void updateStoredSizeOverHundredIsReportedAsNotFound() {
    when(repository.findById(id)).thenReturn(Optional.of(wishlist(101)));
    assertThatThrownBy(() -> update.update(wishlist(0), owner))
        .isInstanceOf(WishlistNotFoundException.class);
    verifyNoInteractions(movieWishlistRepository, moviesWishlistsRepository, kitRepository);
  }
}
