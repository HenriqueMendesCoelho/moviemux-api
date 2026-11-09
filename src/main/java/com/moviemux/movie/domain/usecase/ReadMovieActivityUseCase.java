package com.moviemux.movie.domain.usecase;

import com.moviemux.movie.domain.repository.MovieActivityReader;
import com.moviemux.movie.domain.repository.MovieActivityRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReadMovieActivityUseCase implements MovieActivityReader {
  private final MovieActivityRepository repository;

  @Override
  public Activity read(UUID userId) {
    var facts = repository.read(userId);
    int displayTime = facts.reviews().stream().mapToInt(review -> review.runtime()).sum();
    double average =
        facts.reviews().stream().mapToDouble(review -> review.note()).average().orElse(0);
    return new Activity(facts.reviews().size(), facts.ownedMovies().size(), displayTime, average);
  }
}
