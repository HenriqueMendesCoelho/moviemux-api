package com.moviemux.movie.domain.usecase.impl;

import com.moviemux.movie.domain.exception.MovieNoteNotFoundException;
import com.moviemux.movie.domain.model.*;
import com.moviemux.movie.domain.repository.MovieRepository;
import com.moviemux.movie.domain.usecase.GetMovieUseCase;
import java.time.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class GetMovieUseCaseImpl implements GetMovieUseCase {
  private final MovieRepository repository;
  private final Clock clock;

  public Movie execute(UUID id, UUID userId) throws MovieNoteNotFoundException {
    Movie movie = repository.findById(id).orElseThrow(MovieNoteNotFoundException::new);
    Duration duration =
        Duration.between(movie.getCreatedAt().toLocalDateTime(), LocalDateTime.now(clock));
    if (duration.getSeconds() >= 1800 && !movie.isShowNotes()) {
      movie.setShowNotes(true);
      repository.save(movie);
    }
    return MovieVisibility.project(movie, userId, true);
  }
}
