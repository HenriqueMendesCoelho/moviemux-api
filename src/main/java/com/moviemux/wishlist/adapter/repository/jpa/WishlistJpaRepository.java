package com.moviemux.wishlist.adapter.repository.jpa;

import com.moviemux.wishlist.adapter.repository.postgres.entity.WishlistEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface WishlistJpaRepository extends JpaRepository<WishlistEntity, UUID> {

  @Transactional(readOnly = true)
  List<WishlistEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
