package com.moviemux.wishlist.adapter.repository.postgres.mapper;

import com.moviemux.wishlist.domain.model.MovieWishlist;
import com.moviemux.wishlist.domain.model.MoviesWishlists;
import com.moviemux.wishlist.domain.model.MoviesWishlistsKey;
import com.moviemux.wishlist.domain.model.Wishlist;
import com.moviemux.wishlist.domain.model.WishlistOwner;
import org.springframework.stereotype.Component;

/** All lazy associations are read by the adapter inside its persistence boundary. */
@Component
public class WishlistEntityMapper {
  public Wishlist toDomain(com.moviemux.wishlist.domain.Wishlist entity) {
    if (entity == null) return null;
    return Wishlist.builder()
        .id(entity.getId())
        .name(entity.getName())
        .user(
            entity.getUser() == null
                ? null
                : new WishlistOwner(entity.getUser().getId(), entity.getUser().getName()))
        .shareable(entity.isShareable())
        .createdAt(entity.getCreatedAt())
        .updatedAt(entity.getUpdatedAt())
        .moviesWishlists(
            entity.getMoviesWishlists() == null
                ? null
                : entity.getMoviesWishlists().stream().map(this::toDomain).toList())
        .build();
  }

  public MovieWishlist toDomain(com.moviemux.wishlist.domain.MovieWishlist entity) {
    if (entity == null) return null;
    return MovieWishlist.builder()
        .tmdbId(entity.getTmdbId())
        .title(entity.getTitle())
        .titleEnglish(entity.getTitleEnglish())
        .urlImage(entity.getUrlImage())
        .releaseDate(entity.getReleaseDate())
        .referenced(entity.getWishlists() != null && !entity.getWishlists().isEmpty())
        .build();
  }

  public MoviesWishlists toDomain(com.moviemux.wishlist.domain.MoviesWishlists entity) {
    if (entity == null) return null;
    return MoviesWishlists.builder()
        .id(
            entity.getId() == null
                ? null
                : new MoviesWishlistsKey(
                    entity.getId().getWishlistId(), entity.getId().getMovieId()))
        .wishlist(
            entity.getWishlist() == null
                ? null
                : Wishlist.builder().id(entity.getWishlist().getId()).build())
        .movieWishlist(toDomain(entity.getMovieWishlist()))
        .wishlistOrder(entity.getWishlistOrder())
        .build();
  }

  public com.moviemux.wishlist.domain.Wishlist toEntity(Wishlist model) {
    var entity =
        com.moviemux.wishlist.domain.Wishlist.builder()
            .id(model.getId())
            .name(model.getName())
            .shareable(model.isShareable())
            .createdAt(model.getCreatedAt())
            .updatedAt(model.getUpdatedAt())
            .user(
                model.getUser() == null
                    ? null
                    : com.moviemux.user.domain.User.builder()
                        .id(model.getUser().id())
                        .name(model.getUser().name())
                        .build())
            .build();
    entity.setMoviesWishlists(
        model.getMoviesWishlists() == null
            ? null
            : model.getMoviesWishlists().stream()
                .map(
                    item -> {
                      var membership = toEntity(item);
                      membership.setWishlist(entity);
                      return membership;
                    })
                .toList());
    return entity;
  }

  public com.moviemux.wishlist.domain.MovieWishlist toEntity(MovieWishlist model) {
    return com.moviemux.wishlist.domain.MovieWishlist.builder()
        .tmdbId(model.getTmdbId())
        .title(model.getTitle())
        .titleEnglish(model.getTitleEnglish())
        .urlImage(model.getUrlImage())
        .releaseDate(model.getReleaseDate())
        .build();
  }

  public com.moviemux.wishlist.domain.MoviesWishlists toEntity(MoviesWishlists model) {
    return com.moviemux.wishlist.domain.MoviesWishlists.builder()
        .id(
            model.getId() == null
                ? null
                : new com.moviemux.wishlist.domain.MoviesWishlistsKey(
                    model.getId().getWishlistId(), model.getId().getMovieId()))
        .wishlist(
            model.getWishlist() == null
                ? null
                : com.moviemux.wishlist.domain.Wishlist.builder()
                    .id(model.getWishlist().getId())
                    .build())
        .movieWishlist(model.getMovieWishlist() == null ? null : toEntity(model.getMovieWishlist()))
        .wishlistOrder(model.getWishlistOrder())
        .build();
  }
}
