package com.moviemux.wishlist.domain.model;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
public class Wishlist implements Serializable {

  private static final long serialVersionUID = 1763928844019419528L;

  private UUID id;

  private String name;

  private WishlistOwner user;

  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private List<MoviesWishlists> moviesWishlists;

  public List<MoviesWishlists> getMoviesWishlists() {
    return moviesWishlists == null
        ? null
        : moviesWishlists.stream().map(MoviesWishlists::copy).toList();
  }

  public void setMoviesWishlists(List<MoviesWishlists> items) {
    moviesWishlists = items == null ? null : items.stream().map(MoviesWishlists::copy).toList();
  }

  private boolean shareable;

  private OffsetDateTime createdAt;

  private OffsetDateTime updatedAt;

  public Wishlist(
      UUID id,
      String name,
      WishlistOwner user,
      List<MoviesWishlists> moviesWishlists,
      boolean shareable,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.name = name;
    this.user = user;
    setMoviesWishlists(moviesWishlists);
    this.shareable = shareable;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }
}
