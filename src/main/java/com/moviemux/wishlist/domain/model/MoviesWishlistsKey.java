package com.moviemux.wishlist.domain.model;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoviesWishlistsKey {

  private UUID wishlistId;

  private Long movieId;
}
