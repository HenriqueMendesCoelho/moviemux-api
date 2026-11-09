package com.moviemux.wishlist.adapter.repository.jpa;

import com.moviemux.wishlist.adapter.repository.postgres.entity.MoviesWishlistsEntity;
import com.moviemux.wishlist.adapter.repository.postgres.entity.MoviesWishlistsKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MoviesWishlistsJpaRepository
    extends JpaRepository<MoviesWishlistsEntity, MoviesWishlistsKeyEntity> {}
