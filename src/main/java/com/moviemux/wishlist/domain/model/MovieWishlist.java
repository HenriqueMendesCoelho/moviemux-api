package com.moviemux.wishlist.domain.model;

import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MovieWishlist {

  private Long tmdbId;

  private String title;

  private String titleEnglish;

  private String urlImage;

  private LocalDate releaseDate;

  private boolean referenced;
}
