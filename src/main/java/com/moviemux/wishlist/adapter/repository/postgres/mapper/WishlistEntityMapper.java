package com.moviemux.wishlist.adapter.repository.postgres.mapper;

import com.moviemux.wishlist.domain.model.MovieWishlist;
import com.moviemux.wishlist.domain.model.MoviesWishlists;
import com.moviemux.wishlist.domain.model.MoviesWishlistsKey;
import com.moviemux.wishlist.domain.model.Wishlist;
import com.moviemux.wishlist.domain.model.WishlistOwner;
import org.springframework.stereotype.Component;

/** All lazy associations are read by the adapter inside its persistence boundary. */
@Component
@lombok.RequiredArgsConstructor
public class WishlistEntityMapper {
  private final com.moviemux.user.domain.repository.UserIdentityLookup identities;

  private WishlistOwner owner(java.util.UUID id) {
    return id == null
        ? null
        : identities.find(id).map(user -> new WishlistOwner(user.id(), user.name())).orElse(null);
  }

  public Wishlist toDomain(
      com.moviemux.wishlist.adapter.repository.postgres.entity.WishlistEntity entity) {
    if (entity == null) return null;
    return Wishlist.builder()
        .id(entity.getId())
        .name(entity.getName())
        .user(owner(entity.getUserId()))
        .shareable(entity.isShareable())
        .createdAt(entity.getCreatedAt())
        .updatedAt(entity.getUpdatedAt())
        .moviesWishlists(
            entity.getMoviesWishlists() == null
                ? null
                : entity.getMoviesWishlists().stream().map(this::toDomain).toList())
        .build();
  }

  public MovieWishlist toDomain(
      com.moviemux.wishlist.adapter.repository.postgres.entity.MovieWishlistEntity entity) {
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

  public MoviesWishlists toDomain(
      com.moviemux.wishlist.adapter.repository.postgres.entity.MoviesWishlistsEntity entity) {
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

  public com.moviemux.wishlist.adapter.repository.postgres.entity.WishlistEntity toEntity(
      Wishlist model) {
    var entity =
        com.moviemux.wishlist.adapter.repository.postgres.entity.WishlistEntity.builder()
            .id(model.getId())
            .name(model.getName())
            .shareable(model.isShareable())
            .createdAt(model.getCreatedAt())
            .updatedAt(model.getUpdatedAt())
            .userId(model.getUser() == null ? null : model.getUser().id())
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

  public com.moviemux.wishlist.adapter.repository.postgres.entity.MovieWishlistEntity toEntity(
      MovieWishlist model) {
    return com.moviemux.wishlist.adapter.repository.postgres.entity.MovieWishlistEntity.builder()
        .tmdbId(model.getTmdbId())
        .title(model.getTitle())
        .titleEnglish(model.getTitleEnglish())
        .urlImage(model.getUrlImage())
        .releaseDate(model.getReleaseDate())
        .build();
  }

  public com.moviemux.wishlist.adapter.repository.postgres.entity.MoviesWishlistsEntity toEntity(
      MoviesWishlists model) {
    return com.moviemux.wishlist.adapter.repository.postgres.entity.MoviesWishlistsEntity.builder()
        .id(
            model.getId() == null
                ? null
                : new com.moviemux.wishlist.adapter.repository.postgres.entity
                    .MoviesWishlistsKeyEntity(
                    model.getId().getWishlistId(), model.getId().getMovieId()))
        .wishlist(
            model.getWishlist() == null
                ? null
                : com.moviemux.wishlist.adapter.repository.postgres.entity.WishlistEntity.builder()
                    .id(model.getWishlist().getId())
                    .build())
        .movieWishlist(model.getMovieWishlist() == null ? null : toEntity(model.getMovieWishlist()))
        .wishlistOrder(model.getWishlistOrder())
        .build();
  }
}
