package com.moviemux.wishlist.adapter.controller.mapper;

import com.moviemux.wishlist.adapter.controller.dto.MovieWishlistRequestDto;
import com.moviemux.wishlist.adapter.controller.dto.MovieWishlistResponseDto;
import com.moviemux.wishlist.adapter.controller.dto.WishlistRequestDto;
import com.moviemux.wishlist.adapter.controller.dto.WishlistResponseDto;
import com.moviemux.wishlist.domain.model.MovieWishlist;
import com.moviemux.wishlist.domain.model.MoviesWishlists;
import com.moviemux.wishlist.domain.model.MoviesWishlistsKey;
import com.moviemux.wishlist.domain.model.Wishlist;
import java.util.UUID;

public final class WishlistHttpMapper {
  private WishlistHttpMapper() {}

  public static Wishlist toDomain(WishlistRequestDto request) {
    return Wishlist.builder()
        .id(request.getId())
        .name(request.getName())
        .shareable(request.isShareable())
        .moviesWishlists(
            request.getMoviesWishlists().stream()
                .map(item -> toDomain(item, request.getId()))
                .toList())
        .build();
  }

  public static MoviesWishlists toDomain(MovieWishlistRequestDto request, UUID wishlistId) {
    // The legacy update contract does not accept an image change through this DTO.
    return MoviesWishlists.builder()
        .id(new MoviesWishlistsKey(wishlistId, request.getTmdbId()))
        .wishlist(Wishlist.builder().id(wishlistId).build())
        .movieWishlist(
            MovieWishlist.builder()
                .tmdbId(request.getTmdbId())
                .title(request.getTitle())
                .titleEnglish(request.getTitleEnglish())
                .releaseDate(request.getReleaseDate())
                .build())
        .build();
  }

  public static void populate(WishlistResponseDto response, Wishlist wishlist) {
    response.setId(wishlist.getId());
    response.setName(wishlist.getName());
    response.setShareable(wishlist.isShareable());
    response.setCreatedAt(wishlist.getCreatedAt());
    response.setUpdatedAt(wishlist.getUpdatedAt());
    if (wishlist.getMoviesWishlists() != null && !wishlist.getMoviesWishlists().isEmpty()) {
      response.setMoviesWishlists(
          wishlist.getMoviesWishlists().stream()
              .map(item -> new MovieWishlistResponseDto(item.getMovieWishlist()))
              .toList());
    }
    response.setUser(
        new WishlistResponseDto.WishlistUserResponseDto(
            wishlist.getUser().id(), wishlist.getUser().name()));
  }

  public static void populate(MovieWishlistResponseDto response, MovieWishlist movie) {
    response.setTmdbId(movie.getTmdbId());
    response.setTitle(movie.getTitle());
    response.setTitleEnglish(movie.getTitleEnglish());
    response.setUrlImage(movie.getUrlImage());
    response.setReleaseDate(movie.getReleaseDate());
  }
}
