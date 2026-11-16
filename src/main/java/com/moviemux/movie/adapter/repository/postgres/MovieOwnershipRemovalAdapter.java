package com.moviemux.movie.adapter.repository.postgres;

import com.moviemux.movie.domain.repository.MovieOwnershipRemoval;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MovieOwnershipRemovalAdapter implements MovieOwnershipRemoval {
  private final EntityManager entities;

  public void detach(UUID userId) {
    var owned =
        entities
            .createQuery(
                "from Movie where userId = :userId",
                com.moviemux.movie.adapter.repository.postgres.entity.MovieEntity.class)
            .setParameter("userId", userId)
            .getResultList();
    owned.forEach(movie -> movie.setUserId(null));
    var notes =
        entities
            .createQuery(
                "from MovieNote where id.userId = :userId",
                com.moviemux.movie.adapter.repository.postgres.entity.MovieNoteEntity.class)
            .setParameter("userId", userId)
            .getResultList();
    notes.forEach(entities::remove);
    entities.flush();
  }
}
