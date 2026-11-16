package com.moviemux.wishlistboundary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.moviemux.wishlist.adapter.repository.postgres.entity.MovieWishlistEntity;
import com.moviemux.wishlist.adapter.repository.postgres.entity.MoviesWishlistsEntity;
import com.moviemux.wishlist.adapter.repository.postgres.entity.MoviesWishlistsKeyEntity;
import com.moviemux.wishlist.adapter.repository.postgres.entity.WishlistEntity;
import com.moviemux.wishlist.adapter.repository.postgres.impl.MoviesWishlistsPostgresRepository;
import com.moviemux.wishlist.adapter.repository.postgres.mapper.WishlistEntityMapper;
import com.moviemux.wishlist.domain.model.MoviesWishlists;
import com.moviemux.wishlist.domain.model.MoviesWishlistsKey;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WishlistMembershipRemovalTest {
  @Test
  void removalSynchronizesManagedCollectionsBeforeDeleteAndFlush() {
    UUID wishlistId = UUID.randomUUID();
    var id = new MoviesWishlistsKeyEntity(wishlistId, 7L);
    var otherId = new MoviesWishlistsKeyEntity(wishlistId, 8L);
    var parent = WishlistEntity.builder().id(wishlistId).build();
    var movie = MovieWishlistEntity.builder().tmdbId(7L).build();
    var removed =
        MoviesWishlistsEntity.builder().id(id).wishlist(parent).movieWishlist(movie).build();
    var retained = MoviesWishlistsEntity.builder().id(otherId).wishlist(parent).build();
    parent.setMoviesWishlists(new ArrayList<>(List.of(removed, retained)));
    movie.setWishlists(new ArrayList<>(List.of(removed)));
    var repository = mock(com.moviemux.wishlist.adapter.repository.MoviesWishlistsRepository.class);
    when(repository.findAllById(List.of(id))).thenReturn(List.of(removed));
    doAnswer(
            invocation -> {
              assertThat(parent.getMoviesWishlists()).containsExactly(retained);
              assertThat(movie.getWishlists()).isEmpty();
              return null;
            })
        .when(repository)
        .deleteAll(List.of(removed));

    new MoviesWishlistsPostgresRepository(repository, mock(WishlistEntityMapper.class))
        .deleteAll(
            List.of(MoviesWishlists.builder().id(new MoviesWishlistsKey(wishlistId, 7L)).build()));

    var order = inOrder(repository);
    order.verify(repository).findAllById(List.of(id));
    order.verify(repository).deleteAll(List.of(removed));
    order.verify(repository).flush();
  }
}
