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

/** Wishlist ownership, quota and item-boundary regression coverage. */
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
              .wishlist(Wishlist.builder().id(id).build())
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
    InOrder order = inOrder(repository);
    order.verify(repository).lockOwner(owner);
    order.verify(repository).findByUserIdOrderByCreatedAtDesc(owner);
    order.verify(repository).save(any());
  }

  @ParameterizedTest
  @ValueSource(ints = {10, 11})
  void tenOrMoreListsRejectWithoutWrite(int count) {
    when(repository.findByUserIdOrderByCreatedAtDesc(owner))
        .thenReturn(Collections.nCopies(count, wishlist(0)));
    assertThatThrownBy(() -> create.execute(new CreateWishlistCommand("New", owner)))
        .isInstanceOf(WishlistUserReachedLimitException.class);
    verify(repository, never()).save(any());
    InOrder order = inOrder(repository);
    order.verify(repository).lockOwner(owner);
    order.verify(repository).findByUserIdOrderByCreatedAtDesc(owner);
  }

  @Test
  void createMapsRecognizedPersistenceConflictToDuplicateName() {
    when(repository.save(any()))
        .thenThrow(
            new WishlistPersistenceConflict(
                new IllegalStateException("synthetic recognized uniqueness conflict")));
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
    delete.execute(new DeleteWishlistCommand(id, UUID.fromString(owner.toString())));
    InOrder order = inOrder(repository, moviesWishlistsRepository);
    order.verify(repository).findById(id);
    order.verify(moviesWishlistsRepository).deleteAll(w.getMoviesWishlists());
    order.verify(repository).delete(w);
  }

  @Test
  void addAtHundredRejectsBeforeCatalogCalls() {
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(wishlist(100)));
    assertThatThrownBy(() -> update.addMovieToWishlist(id, owner, 999L))
        .isInstanceOf(WishlistNotFoundException.class);
    verifyNoInteractions(movieWishlistRepository, moviesWishlistsRepository, kitRepository);
    verify(repository, never()).save(any());
  }

  @Test
  void addAtNinetyNinePreservesOrderAndAppendsHundredth() throws Exception {
    Wishlist w = wishlist(99);
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(w));
    when(movieWishlistRepository.findById(999L))
        .thenReturn(Optional.of(MovieWishlist.builder().tmdbId(999L).build()));
    when(moviesWishlistsRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    Wishlist saved = update.addMovieToWishlist(id, UUID.fromString(owner.toString()), 999L);
    assertThat(saved.getMoviesWishlists()).hasSize(100);
    assertThat(saved.getMoviesWishlists().getLast().getWishlistOrder()).isEqualTo(100);
    assertThat(saved.getMoviesWishlists().getLast().getId())
        .isEqualTo(new MoviesWishlistsKey(id, 999L));
    assertThat(saved.getUpdatedAt()).isNotNull();
    verifyNoInteractions(kitRepository);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void addMovieRejectsNonOwnerBeforeCatalogOrWritesEvenWhenShared(boolean shared) {
    Wishlist w = wishlist(0);
    w.setShareable(shared);
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(w));
    assertThatThrownBy(
            () ->
                update.addMovieToWishlist(
                    id, UUID.fromString("00000000-0000-0000-0000-000000000099"), 999L))
        .isInstanceOf(WishlistNotFoundException.class);
    verifyNoInteractions(movieWishlistRepository, moviesWishlistsRepository, kitRepository);
    verify(repository, never()).save(any());
    assertThat(w.getMoviesWishlists()).isEmpty();
  }

  @Test
  void missingWishlistAndMissingOwnerRejectBeforeCatalogOrWrites() {
    assertThatThrownBy(() -> update.addMovieToWishlist(id, owner, 999L))
        .isInstanceOf(WishlistNotFoundException.class);
    Wishlist withoutOwner = wishlist(0);
    withoutOwner.setUser(null);
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(withoutOwner));
    assertThatThrownBy(() -> update.addMovieToWishlist(id, owner, 999L))
        .isInstanceOf(WishlistNotFoundException.class);
    verifyNoInteractions(movieWishlistRepository, moviesWishlistsRepository, kitRepository);
    verify(repository, never()).save(any());
  }

  @Test
  void missingCatalogMovieIsSilentlyOmitted() throws Exception {
    Wishlist w = wishlist(0);
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(w));
    when(moviesWishlistsRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    assertThat(update.addMovieToWishlist(id, owner, 999L).getMoviesWishlists()).isEmpty();
    verify(kitRepository).movieSummary(999L);
    verify(movieWishlistRepository, never()).save(any());
  }

  @Test
  void updateRejectsNonOwnerBeforeWritesOrCatalogCalls() {
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(wishlist(0)));
    assertThatThrownBy(
            () ->
                update.update(wishlist(0), UUID.fromString("00000000-0000-0000-0000-000000000099")))
        .isInstanceOf(UserNotAuthorizedException.class);
    verifyNoInteractions(movieWishlistRepository, moviesWishlistsRepository, kitRepository);
    verify(repository, never()).save(any());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 99, 100, 101})
  void updateRejectsIncomingHundredAndOneBeforeWritesOrCatalogRegardlessOfStoredSize(int size) {
    Wishlist current = wishlist(size);
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(current));
    assertThatThrownBy(() -> update.update(wishlist(101), owner))
        .isInstanceOf(WishlistNotFoundException.class);
    verifyNoInteractions(movieWishlistRepository, moviesWishlistsRepository, kitRepository);
    verify(repository, never()).save(any());
    assertThat(current.getMoviesWishlists()).hasSize(size);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 99, 100, 101})
  void updateAcceptsIncomingHundredRegardlessOfStoredSizeAndUuidInstance(int size)
      throws Exception {
    Wishlist current = wishlist(size);
    Wishlist request = wishlist(100);
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(current));
    when(movieWishlistRepository.findById(anyLong()))
        .thenAnswer(i -> Optional.of(MovieWishlist.builder().tmdbId(i.getArgument(0)).build()));
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    Wishlist saved = update.update(request, UUID.fromString(owner.toString()));
    assertThat(saved.getMoviesWishlists()).hasSize(100);
    assertThat(saved.getMoviesWishlists().getLast().getWishlistOrder()).isEqualTo(100);
    assertThat(saved.getUpdatedAt()).isNotNull();
    verify(moviesWishlistsRepository).saveAll(request.getMoviesWishlists());
    verifyNoInteractions(kitRepository);
    InOrder order = inOrder(repository, movieWishlistRepository, moviesWishlistsRepository);
    order.verify(repository).findByIdForUpdate(id);
    order.verify(movieWishlistRepository).findById(0L);
    order.verify(moviesWishlistsRepository).saveAll(any());
    order.verify(repository).save(any());
  }

  @Test
  void updateCanRepairOversizedStoredListUsingIncomingEmptyCollection() throws Exception {
    Wishlist current = wishlist(101);
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(current));
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    assertThat(update.update(wishlist(0), owner).getMoviesWishlists()).isEmpty();
    verifyNoInteractions(movieWishlistRepository, kitRepository);
    verify(moviesWishlistsRepository).deleteAll(argThat(items -> items.size() == 101));
    verify(moviesWishlistsRepository).saveAll(List.of());
  }

  @Test
  void updateLoadsNewIncomingMoviesRatherThanRemovedStoredMovies() throws Exception {
    Wishlist current = wishlist(1);
    Wishlist request = wishlist(1);
    MoviesWishlists incoming = request.getMoviesWishlists().getFirst();
    incoming.setId(new MoviesWishlistsKey(id, 999L));
    incoming.setMovieWishlist(MovieWishlist.builder().tmdbId(999L).build());
    request.setMoviesWishlists(List.of(incoming));
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(current));
    when(kitRepository.movieSummary(999L))
        .thenReturn(
            com.moviemux.kronusintegrationtool.domain.model.MovieSummary.builder()
                .tmdbId(999L)
                .portugueseTitle("Incoming title")
                .urlImageEnglish("synthetic-image")
                .build());
    when(movieWishlistRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    Wishlist saved = update.update(request, owner);
    assertThat(saved.getMoviesWishlists())
        .extracting(item -> item.getMovieWishlist().getTmdbId())
        .containsExactly(999L);
    verify(movieWishlistRepository).findById(999L);
    verify(movieWishlistRepository, never()).findById(0L);
    verify(movieWishlistRepository)
        .save(
            argThat(
                movie ->
                    movie.getTmdbId().equals(999L) && movie.getTitle().equals("Incoming title")));
    verify(kitRepository).movieSummary(999L);
    verify(moviesWishlistsRepository)
        .deleteAll(
            argThat(
                items ->
                    items.size() == 1
                        && items.getFirst().getMovieWishlist().getTmdbId().equals(0L)));
  }

  @Test
  void updateRebuildsMembershipIdentityFromAuthorizedWishlistAndMovie() throws Exception {
    UUID otherList = UUID.randomUUID();
    Wishlist request = wishlist(1);
    MoviesWishlists forged = request.getMoviesWishlists().getFirst();
    forged.setId(new MoviesWishlistsKey(otherList, 888L));
    forged.setWishlist(Wishlist.builder().id(otherList).build());
    request.setMoviesWishlists(List.of(forged));
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(wishlist(0)));
    when(movieWishlistRepository.findById(0L))
        .thenReturn(Optional.of(MovieWishlist.builder().tmdbId(0L).build()));
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    Wishlist saved = update.update(request, owner);
    assertThat(saved.getMoviesWishlists().getFirst().getId())
        .isEqualTo(new MoviesWishlistsKey(id, 0L));
    assertThat(saved.getMoviesWishlists().getFirst().getWishlist().getId()).isEqualTo(id);
    verify(moviesWishlistsRepository)
        .saveAll(
            argThat(
                items ->
                    items.size() == 1
                        && items.getFirst().getId().equals(new MoviesWishlistsKey(id, 0L))
                        && items.getFirst().getWishlist().getId().equals(id)));
    verifyNoInteractions(kitRepository);
    assertThat(request.getMoviesWishlists().getFirst().getId().getWishlistId())
        .isEqualTo(otherList);
  }
}
