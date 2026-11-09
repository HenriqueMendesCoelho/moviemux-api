package com.moviemux.wishlist.adapter.repository.jpa;

import com.moviemux.wishlist.domain.MoviesWishlists;
import com.moviemux.wishlist.domain.MoviesWishlistsKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MoviesWishlistsJpaRepository
    extends JpaRepository<MoviesWishlists, MoviesWishlistsKey> {}
