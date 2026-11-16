package com.moviemux.wishlist.adapter.repository.postgres.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Embeddable
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoviesWishlistsKeyEntity {

  @Column private UUID wishlistId;

  @Column private Long movieId;
}
