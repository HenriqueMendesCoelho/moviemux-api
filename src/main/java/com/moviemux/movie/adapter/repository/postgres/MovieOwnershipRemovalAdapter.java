package com.moviemux.movie.adapter.repository.postgres;

import com.moviemux.movie.adapter.repository.MovieRepository;
import com.moviemux.movie.domain.repository.MovieOwnershipRemoval;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MovieOwnershipRemovalAdapter implements MovieOwnershipRemoval {
  private final MovieRepository movies;

  @Override
  public void detach(List<UUID> movieIds) {
    // The removal preparation loaded these entities in the same transaction.
    var owned = movieIds.stream().map(movies::getReferenceById).toList();
    owned.forEach(movie -> movie.setUser(null));
    movies.saveAllAndFlush(owned);
  }
}
