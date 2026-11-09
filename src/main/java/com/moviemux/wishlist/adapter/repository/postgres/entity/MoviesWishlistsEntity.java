package com.moviemux.wishlist.adapter.repository.postgres.entity;


import jakarta.persistence.*;
import lombok.*;

@Entity(name = "MoviesWishlists")
@Table(
    name = "movie_wishlist_user_wishlist",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"wishlist_id", "movie_id"})})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class MoviesWishlistsEntity {

  @ManyToOne
  @MapsId("wishlistId")
  @JoinColumn(name = "wishlist_id")
  WishlistEntity wishlist;

  @ManyToOne
  @MapsId("movieId")
  @JoinColumn(name = "movie_id")
  MovieWishlistEntity movieWishlist;

  @EmbeddedId private MoviesWishlistsKeyEntity id;

  @Column private Integer wishlistOrder;
}
