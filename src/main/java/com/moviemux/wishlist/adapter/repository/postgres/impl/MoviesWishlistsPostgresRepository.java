package com.moviemux.wishlist.adapter.repository.postgres.impl;

import com.moviemux.wishlist.adapter.repository.postgres.mapper.WishlistEntityMapper;
import com.moviemux.wishlist.domain.exception.WishlistPersistenceConflict;
import com.moviemux.wishlist.domain.model.MoviesWishlists;
import com.moviemux.wishlist.domain.repository.MoviesWishlistsRepository;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MoviesWishlistsPostgresRepository implements MoviesWishlistsRepository {
  private final com.moviemux.wishlist.adapter.repository.MoviesWishlistsRepository repository;
  private final WishlistEntityMapper mapper;

  public MoviesWishlistsPostgresRepository(
      com.moviemux.wishlist.adapter.repository.MoviesWishlistsRepository repository,
      WishlistEntityMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
  }

  @Override
  @Transactional
  public List<MoviesWishlists> saveAll(List<MoviesWishlists> memberships) {
    try {
      return repository
          .saveAllAndFlush(memberships.stream().map(mapper::toEntity).toList())
          .stream()
          .map(mapper::toDomain)
          .toList();
    } catch (DataIntegrityViolationException failure) {
      if (WishlistConstraintViolations.isUniqueViolation(
          failure, "movie_wishlist_user_wishlist_pkey")) {
        throw new WishlistPersistenceConflict(failure);
      }
      throw failure;
    }
  }

  @Override
  @Transactional
  public void deleteAll(List<MoviesWishlists> memberships) {
    var ids =
        memberships.stream()
            .map(
                item ->
                    new com.moviemux.wishlist.adapter.repository.postgres.entity
                        .MoviesWishlistsKeyEntity(
                        item.getId().getWishlistId(), item.getId().getMovieId()))
            .toList();
    var entities = repository.findAllById(ids);
    for (var entity : entities) {
      // Keep both managed collections consistent: wishlist cascade must not persist a removed row.
      if (entity.getWishlist() != null && entity.getWishlist().getMoviesWishlists() != null) {
        entity
            .getWishlist()
            .getMoviesWishlists()
            .removeIf(item -> item.getId().equals(entity.getId()));
      }
      if (entity.getMovieWishlist() != null && entity.getMovieWishlist().getWishlists() != null) {
        entity
            .getMovieWishlist()
            .getWishlists()
            .removeIf(item -> item.getId().equals(entity.getId()));
      }
    }
    repository.deleteAll(entities);
    repository.flush();
  }
}
