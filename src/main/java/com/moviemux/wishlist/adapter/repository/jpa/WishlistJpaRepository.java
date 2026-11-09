package com.moviemux.wishlist.adapter.repository.jpa;

import com.moviemux.wishlist.adapter.repository.postgres.entity.WishlistEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WishlistJpaRepository extends JpaRepository<WishlistEntity, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select wishlist from Wishlist wishlist where wishlist.id = :id")
  Optional<WishlistEntity> findByIdForUpdate(@Param("id") UUID id);

  @Query(
      value =
          "select 1 from pg_advisory_xact_lock("
              + "hashtextextended('wishlist-owner:' || cast(:ownerId as text), 0))",
      nativeQuery = true)
  Integer lockOwner(@Param("ownerId") UUID ownerId);

  @Transactional(readOnly = true)
  List<WishlistEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
