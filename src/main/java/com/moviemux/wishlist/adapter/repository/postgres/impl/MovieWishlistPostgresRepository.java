package com.moviemux.wishlist.adapter.repository.postgres.impl;

import com.moviemux.wishlist.adapter.repository.postgres.mapper.WishlistEntityMapper;
import com.moviemux.wishlist.domain.exception.WishlistPersistenceConflict;
import com.moviemux.wishlist.domain.model.MovieWishlist;
import com.moviemux.wishlist.domain.repository.MovieWishlistRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MovieWishlistPostgresRepository implements MovieWishlistRepository {
  private final com.moviemux.wishlist.adapter.repository.MovieWishlistRepository repository;
  private final WishlistEntityMapper mapper;

  public MovieWishlistPostgresRepository(
      com.moviemux.wishlist.adapter.repository.MovieWishlistRepository repository,
      WishlistEntityMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<MovieWishlist> findById(Long id) {
    return repository.findById(id).map(mapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<MovieWishlist> findAll() {
    return repository.findAll().stream().map(mapper::toDomain).toList();
  }

  @Override
  @Transactional
  public MovieWishlist save(MovieWishlist movie) {
    try {
      return mapper.toDomain(repository.saveAndFlush(mapper.toEntity(movie)));
    } catch (DataIntegrityViolationException failure) {
      if (WishlistConstraintViolations.isUniqueViolation(failure, "movie_wishlist_pkey")) {
        throw new WishlistPersistenceConflict(failure);
      }
      throw failure;
    }
  }

  @Override
  @Transactional
  public void delete(MovieWishlist movie) {
    repository.deleteById(movie.getTmdbId());
  }
}
