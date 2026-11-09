package com.moviemux.movie.adapter.repository.postgres;

import com.moviemux.movie.domain.repository.MovieActivityRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class MovieActivityRepositoryAdapter implements MovieActivityRepository {
  private final MovieActivityJpaReader reader;

  @Override
  @Transactional(readOnly = true)
  public Facts read(UUID userId) {
    var reviews =
        reader.reviews(userId).stream()
            .map(row -> new Review(row.getNote(), row.getRuntime()))
            .toList();
    return new Facts(reviews, reader.ownedMovies(userId));
  }
}
