package com.moviemux.wishlist.adapter.repository.postgres;

import com.moviemux.wishlist.domain.repository.WishlistOwnershipRemoval;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WishlistOwnershipRemovalAdapter implements WishlistOwnershipRemoval {
  private final EntityManager entities;

  public void removeOwnedBy(UUID userId) {
    entities
        .createQuery(
            "from Wishlist where userId = :userId",
            com.moviemux.wishlist.adapter.repository.postgres.entity.WishlistEntity.class)
        .setParameter("userId", userId)
        .getResultList()
        .forEach(entities::remove);
    entities.flush();
  }
}
