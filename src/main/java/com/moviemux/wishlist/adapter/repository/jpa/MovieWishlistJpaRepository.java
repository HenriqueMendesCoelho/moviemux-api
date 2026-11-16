package com.moviemux.wishlist.adapter.repository.jpa;

import com.moviemux.wishlist.adapter.repository.postgres.entity.MovieWishlistEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MovieWishlistJpaRepository extends JpaRepository<MovieWishlistEntity, Long> {}
