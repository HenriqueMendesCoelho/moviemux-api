package com.moviemux.behavior;

import com.moviemux.kronusintegrationtool.adapter.repository.rest.KronusIntegrationToolRepository;
import com.moviemux.user.domain.User;
import com.moviemux.user.usecase.exception.UserNotAuthorizedException;
import com.moviemux.wishlist.adapter.repository.*;
import com.moviemux.wishlist.domain.*;
import com.moviemux.wishlist.usecase.exception.*;
import com.moviemux.wishlist.usecase.impl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Baseline characterization. Tests named legacy diagnose defects pending an explicitly reviewed fix. */
@ExtendWith(MockitoExtension.class)
class WishlistBehaviorTest {
    @Mock WishlistRepository repository;
    @Mock MovieWishlistRepository movieWishlistRepository;
    @Mock MoviesWishlistsRepository moviesWishlistsRepository;
    @Mock KronusIntegrationToolRepository kitRepository;
    @InjectMocks CreateUserWishlistUseCaseImpl create;
    @InjectMocks SearchUserWishlistUseCaseImpl search;
    @InjectMocks DeleteUserWishlistUseCaseImpl delete;
    @InjectMocks UpdateUserWishlistUseCaseImpl update;
    private final UUID owner=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID id=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private Wishlist wishlist(int size) {
        List<MoviesWishlists> movies=new ArrayList<>();
        for(int i=0;i<size;i++) movies.add(MoviesWishlists.builder()
                .id(new MoviesWishlistsKey(id,(long)i)).movieWishlist(MovieWishlist.builder().tmdbId((long)i).build())
                .wishlistOrder(i+1).build());
        return Wishlist.builder().id(id).user(User.builder().id(owner).build()).name("Watch next")
                .moviesWishlists(movies).shareable(false).build();
    }

    @ParameterizedTest @ValueSource(ints={0,9})
    void belowTenListsCreatesPrivateWishlist(int count) throws Exception {
        when(repository.findByUserIdOrderByCreatedAtDesc(owner)).thenReturn(Collections.nCopies(count,wishlist(0)));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        Wishlist saved=create.create("New list",owner);
        assertThat(saved.getName()).isEqualTo("New list");assertThat(saved.isShareable()).isFalse();
        assertThat(saved.getUser().getId()).isEqualTo(owner);
    }

    @ParameterizedTest @ValueSource(ints={10,11})
    void tenOrMoreListsRejectWithoutWrite(int count) {
        when(repository.findByUserIdOrderByCreatedAtDesc(owner)).thenReturn(Collections.nCopies(count,wishlist(0)));
        assertThatThrownBy(() -> create.create("New",owner)).isInstanceOf(WishlistUserReachedLimitException.class);
        verify(repository,never()).save(any());
    }

    @Test void legacyCreateMapsAnyIntegrityFailureToDuplicateName() {
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("synthetic unrelated constraint"));
        assertThatThrownBy(() -> create.create("New",owner)).isInstanceOf(WishlistDuplicatedException.class);
    }

    @Test void ownerCanReadPrivateListUsingValueEqualUuid() throws Exception {
        Wishlist w=wishlist(0);when(repository.findById(id)).thenReturn(Optional.of(w));
        assertThat(search.findById(id,UUID.fromString(owner.toString()))).isSameAs(w);
    }

    @Test void nonOwnerCannotDiscoverPrivateList() {
        when(repository.findById(id)).thenReturn(Optional.of(wishlist(0)));
        assertThatThrownBy(() -> search.findById(id,UUID.fromString("00000000-0000-0000-0000-000000000099"))).isInstanceOf(WishlistNotFoundException.class);
    }

    @Test void nonOwnerCanReadSharedList() throws Exception {
        Wishlist w=wishlist(0);w.setShareable(true);when(repository.findById(id)).thenReturn(Optional.of(w));
        assertThat(search.findById(id,UUID.fromString("00000000-0000-0000-0000-000000000099"))).isSameAs(w);
    }

    @Test void missingWishlistIsNotFound() {
        assertThatThrownBy(() -> search.findById(id,owner)).isInstanceOf(WishlistNotFoundException.class);
    }

    @Test void deletingSomeoneElsesListIsNoOp() {
        when(repository.findById(id)).thenReturn(Optional.of(wishlist(2)));
        delete.delete(id,UUID.fromString("00000000-0000-0000-0000-000000000099"));verifyNoInteractions(moviesWishlistsRepository);
        verify(repository,never()).delete(any());
    }

    @Test void ownerDeleteRemovesJoinRowsBeforeWishlist() {
        Wishlist w=wishlist(2);when(repository.findById(id)).thenReturn(Optional.of(w));
        delete.delete(id,owner);InOrder order=inOrder(repository,moviesWishlistsRepository);
        order.verify(repository).findById(id);order.verify(moviesWishlistsRepository).deleteAll(w.getMoviesWishlists());
        order.verify(repository).delete(w);
    }

    @Test void addAtHundredRejectsBeforeCatalogCalls() {
        when(repository.findById(id)).thenReturn(Optional.of(wishlist(100)));
        assertThatThrownBy(() -> update.addMovieToWishlist(id,owner,999L)).isInstanceOf(WishlistNotFoundException.class);
        verifyNoInteractions(movieWishlistRepository,moviesWishlistsRepository,kitRepository);
        verify(repository,never()).save(any());
    }

    @Test void addAtNinetyNinePreservesOrderAndAppendsHundredth() throws Exception {
        Wishlist w=wishlist(99);when(repository.findById(id)).thenReturn(Optional.of(w));
        when(movieWishlistRepository.findById(999L)).thenReturn(Optional.of(MovieWishlist.builder().tmdbId(999L).build()));
        when(moviesWishlistsRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        Wishlist saved=update.addMovieToWishlist(id,owner,999L);
        assertThat(saved.getMoviesWishlists()).hasSize(100);
        assertThat(saved.getMoviesWishlists().getLast().getWishlistOrder()).isEqualTo(100);
        assertThat(saved.getMoviesWishlists().getLast().getId()).isEqualTo(new MoviesWishlistsKey(id,999L));
        assertThat(saved.getUpdatedAt()).isNotNull();verifyNoInteractions(kitRepository);
    }

    @Test void legacyAddMovieDoesNotCheckOwner() throws Exception {
        Wishlist w=wishlist(0);when(repository.findById(id)).thenReturn(Optional.of(w));
        when(movieWishlistRepository.findById(999L)).thenReturn(Optional.of(MovieWishlist.builder().tmdbId(999L).build()));
        when(moviesWishlistsRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        Wishlist result=update.addMovieToWishlist(id,UUID.fromString("00000000-0000-0000-0000-000000000099"),999L);
        assertThat(result.getUser().getId()).isEqualTo(owner);
        assertThat(result.getMoviesWishlists()).hasSize(1);
        verify(repository).save(w);
    }

    @Test void missingCatalogMovieIsSilentlyOmitted() throws Exception {
        Wishlist w=wishlist(0);when(repository.findById(id)).thenReturn(Optional.of(w));
        when(moviesWishlistsRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(update.addMovieToWishlist(id,owner,999L).getMoviesWishlists()).isEmpty();
        verify(kitRepository).movieSummary(999L);verify(movieWishlistRepository,never()).saveAndFlush(any());
    }

    @Test void updateRejectsNonOwnerBeforeWritesOrCatalogCalls() {
        when(repository.findById(id)).thenReturn(Optional.of(wishlist(0)));
        assertThatThrownBy(() -> update.update(wishlist(0),UUID.fromString("00000000-0000-0000-0000-000000000099"))).isInstanceOf(UserNotAuthorizedException.class);
        verifyNoInteractions(movieWishlistRepository,moviesWishlistsRepository,kitRepository);
        verify(repository,never()).save(any());
    }

    @Test void legacyUpdateChecksStoredSizeButNotIncomingHundredAndOneItems() throws Exception {
        Wishlist current=wishlist(0);Wishlist request=wishlist(101);
        when(repository.findById(id)).thenReturn(Optional.of(current));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        Wishlist saved=update.update(request,owner);
        assertThat(saved.getMoviesWishlists()).hasSize(101);
        assertThat(saved.getMoviesWishlists().getLast().getWishlistOrder()).isEqualTo(101);
        verify(moviesWishlistsRepository).saveAll(request.getMoviesWishlists());
    }

    @Test void updateStoredSizeOverHundredIsReportedAsNotFound() {
        when(repository.findById(id)).thenReturn(Optional.of(wishlist(101)));
        assertThatThrownBy(() -> update.update(wishlist(0),owner)).isInstanceOf(WishlistNotFoundException.class);
        verifyNoInteractions(movieWishlistRepository,moviesWishlistsRepository,kitRepository);
    }
}
