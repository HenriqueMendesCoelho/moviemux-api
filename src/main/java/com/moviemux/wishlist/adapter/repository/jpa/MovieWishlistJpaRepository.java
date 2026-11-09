package com.moviemux.wishlist.adapter.repository.jpa;

import com.moviemux.wishlist.domain.MovieWishlist;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MovieWishlistJpaRepository extends JpaRepository<MovieWishlist, Long> {}
