package com.moviemux.wishlist.adapter.repository.postgres.impl;

import com.moviemux.wishlist.adapter.repository.postgres.mapper.WishlistEntityMapper;
import com.moviemux.wishlist.domain.exception.WishlistPersistenceConflict;
import com.moviemux.wishlist.domain.model.Wishlist;
import com.moviemux.wishlist.domain.repository.WishlistRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class WishlistPostgresRepository implements WishlistRepository {
  private final com.moviemux.wishlist.adapter.repository.WishlistRepository repository;
  private final WishlistEntityMapper mapper;

  public WishlistPostgresRepository(
      com.moviemux.wishlist.adapter.repository.WishlistRepository repository,
      WishlistEntityMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Wishlist> findById(UUID id) {
    return repository.findById(id).map(mapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Wishlist> findByUserIdOrderByCreatedAtDesc(UUID userId) {
    return repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
        .map(mapper::toDomain)
        .toList();
  }

  @Override
  @Transactional
  public Wishlist save(Wishlist wishlist) {
    try {
      return mapper.toDomain(repository.saveAndFlush(mapper.toEntity(wishlist)));
    } catch (DataIntegrityViolationException failure) {
      if (WishlistConstraintViolations.isUniqueViolation(
          failure, "user_wishlist_user_id_name_key")) {
        throw new WishlistPersistenceConflict(failure);
      }
      throw failure;
    }
  }

  @Override
  @Transactional
  public void delete(Wishlist wishlist) {
    repository.deleteById(wishlist.getId());
  }
}
