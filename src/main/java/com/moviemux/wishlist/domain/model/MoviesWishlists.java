package com.moviemux.wishlist.domain.model;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class MoviesWishlists {

  Wishlist wishlist;

  MovieWishlist movieWishlist;

  private MoviesWishlistsKey id;

  private Integer wishlistOrder;

  public MoviesWishlists copy() {
    MovieWishlist movie =
        movieWishlist == null
            ? null
            : MovieWishlist.builder()
                .tmdbId(movieWishlist.getTmdbId())
                .title(movieWishlist.getTitle())
                .titleEnglish(movieWishlist.getTitleEnglish())
                .urlImage(movieWishlist.getUrlImage())
                .releaseDate(movieWishlist.getReleaseDate())
                .referenced(movieWishlist.isReferenced())
                .build();
    MoviesWishlistsKey key =
        id == null ? null : new MoviesWishlistsKey(id.getWishlistId(), id.getMovieId());
    Wishlist parent = wishlist == null ? null : Wishlist.builder().id(wishlist.getId()).build();
    return new MoviesWishlists(parent, movie, key, wishlistOrder);
  }
}
