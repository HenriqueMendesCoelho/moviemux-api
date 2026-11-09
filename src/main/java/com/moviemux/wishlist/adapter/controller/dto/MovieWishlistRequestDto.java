package com.moviemux.wishlist.adapter.controller.dto;

import com.moviemux.wishlist.domain.model.MoviesWishlists;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MovieWishlistRequestDto {

  private Long tmdbId;
  private String title;
  private String titleEnglish;
  private String urlImage;
  private LocalDate releaseDate;

  public MoviesWishlists toDomain(UUID wishlistId) {
    return com.moviemux.wishlist.adapter.controller.mapper.WishlistHttpMapper.toDomain(
        this, wishlistId);
  }
}
