package com.moviemux.wishlist.adapter.controller.dto;

import com.moviemux.wishlist.domain.model.MovieWishlist;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MovieWishlistResponseDto {

  private Long tmdbId;
  private String title;
  private String titleEnglish;
  private String urlImage;
  private LocalDate releaseDate;

  public MovieWishlistResponseDto(MovieWishlist movie) {
    com.moviemux.wishlist.adapter.controller.mapper.WishlistHttpMapper.populate(this, movie);
  }
}
